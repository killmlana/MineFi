package com.minefi.provider

import com.minefi.MineFiPlugin
import com.minefi.chain.ChainService
import com.minefi.map.QrMapRenderer
import com.minefi.relay.RelayClient
import com.minefi.relay.SessionManager
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class CryptoProvider : PaymentProvider {

    override val name = "crypto"
    override val currencies: List<String> get() = listOf("eip155:$chainId")

    private lateinit var plugin: MineFiPlugin
    lateinit var relayClient: RelayClient
    lateinit var sessionManager: SessionManager
    lateinit var chainService: ChainService
    private var chainId: Int = 1
    val activeRenderers = ConcurrentHashMap<UUID, QrMapRenderer>()

    override fun initialize(plugin: MineFiPlugin, config: ConfigurationSection) {
        this.plugin = plugin
        val projectId = config.getString("walletconnect.project-id") ?: ""
        val relayUrl = config.getString("walletconnect.relay-url") ?: "wss://relay.walletconnect.com"
        val rpcUrl = config.getString("chain.rpc-url") ?: ""
        chainId = config.getInt("chain.chain-id", 1)

        chainService = ChainService(rpcUrl, chainId.toLong())

        relayClient = RelayClient(projectId, relayUrl, plugin.logger) { topic, message ->
            sessionManager.handleRelayMessage(topic, message)
        }

        val metadataUrl = config.getString("walletconnect.metadata-url") ?: "https://minefi.saikia.me"

        sessionManager = SessionManager(
            relay = relayClient,
            db = plugin.db,
            logger = plugin.logger,
            chainId = chainId,
            metadataUrl = metadataUrl,
            onSessionEstablished = { uuid, address ->
                plugin.server.scheduler.runTask(plugin) { ->
                    val player = plugin.server.getPlayer(uuid)
                    player?.sendMessage("§a§lWallet connected! §f${address.take(6)}...${address.takeLast(4)}")
                    activeRenderers.remove(uuid)
                    removeQrMap(player)
                }
            },
            onSessionFailed = { uuid, reason ->
                plugin.server.scheduler.runTask(plugin) { ->
                    val player = plugin.server.getPlayer(uuid)
                    player?.sendMessage("§c§lConnection failed: §f$reason")
                    activeRenderers.remove(uuid)
                    removeQrMap(player)
                }
            },
            onSessionRequest = { uuid, _, result ->
                plugin.server.scheduler.runTask(plugin) { ->
                    val player = plugin.server.getPlayer(uuid)
                    player?.sendMessage("§aTransaction signed: §f${result.take(10)}...")
                }
            },
        )

        relayClient.connect()
        sessionManager.restoreSessions()

        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, { ->
            sessionManager.cleanupStalePendingRequests()
        }, 2400, 2400)

        plugin.logger.info("Crypto provider initialized (chain $chainId)")
    }

    override fun shutdown() {
        relayClient.disconnect()
        chainService.shutdown()
    }

    override fun connect(player: Player): Boolean {
        val uri = sessionManager.initiatePairing(player.uniqueId)
        val mapView = plugin.server.createMap(player.world)
        mapView.isUnlimitedTracking = true
        mapView.renderers.clear()
        val renderer = QrMapRenderer(uri)
        mapView.addRenderer(renderer)
        activeRenderers[player.uniqueId] = renderer

        val mapItem = ItemStack(Material.FILLED_MAP)
        val meta = mapItem.itemMeta as MapMeta
        meta.setDisplayName("§6§lWallet Connect")
        meta.lore = listOf("§7Scan the QR code with your wallet app")
        meta.mapView = mapView
        mapItem.itemMeta = meta
        player.inventory.setItemInMainHand(mapItem)
        player.sendMessage("§aScan the QR code on the map with your wallet app!")
        return true
    }

    override fun disconnect(player: Player) {
        sessionManager.disconnectSession(player.uniqueId)
        activeRenderers.remove(player.uniqueId)
    }

    override fun isConnected(player: Player): Boolean {
        return sessionManager.activeSessions.containsKey(player.uniqueId)
    }

    override fun startDeposit(player: Player, amount: Double, currency: String): CompletableFuture<DepositResult> {
        val future = CompletableFuture<DepositResult>()
        val contractAddress = plugin.config.getString("providers.crypto.vault.escrow-contract") ?: ""
        val session = sessionManager.activeSessions[player.uniqueId]
        if (session == null || contractAddress.isBlank()) {
            future.complete(DepositResult(false, "", amount, currency, "Not connected or contract not configured"))
            return future
        }

        val weiValue = Convert.toWei(BigDecimal.valueOf(amount), Convert.Unit.ETHER).toBigInteger()
        val paddedAddress = session.walletAddress.removePrefix("0x").padStart(64, '0')
        val depositData = "0xf340fa01$paddedAddress"
        val hexValue = "0x" + weiValue.toString(16)

        player.sendMessage("§eCheck your wallet app to approve §6$amount ETH§e...")

        plugin.server.scheduler.runTaskAsynchronously(plugin) { ->
            val txHash = sessionManager.sendTransactionAndWait(
                playerUuid = player.uniqueId, to = contractAddress, value = hexValue, data = depositData,
            )
            if (txHash != null) {
                val receipt = try { chainService.getTransactionReceipt(txHash) } catch (e: Exception) { null }
                if (receipt != null && receipt.status == "0x1") {
                    future.complete(DepositResult(true, txHash, amount, currency))
                } else {
                    future.complete(DepositResult(false, txHash ?: "", amount, currency, "Transaction failed on-chain"))
                }
            } else {
                future.complete(DepositResult(false, "", amount, currency, "Rejected or timed out"))
            }
        }
        return future
    }

    override fun startWithdraw(player: Player, amount: Double, currency: String): CompletableFuture<WithdrawResult> {
        val future = CompletableFuture<WithdrawResult>()
        val contractAddress = plugin.config.getString("providers.crypto.vault.escrow-contract") ?: ""
        val serverKey = plugin.config.getString("providers.crypto.vault.server-hot-wallet-key") ?: ""
        val session = sessionManager.activeSessions[player.uniqueId]

        if (session == null || contractAddress.isBlank() || serverKey.isBlank()) {
            future.complete(WithdrawResult(false, "", amount, currency, "Not configured"))
            return future
        }

        val weiAmount = Convert.toWei(BigDecimal.valueOf(amount), Convert.Unit.ETHER).toBigInteger()

        player.sendMessage("§eSign the withdrawal in your wallet app...")

        plugin.server.scheduler.runTaskAsynchronously(plugin) { ->
            val nonce = chainService.vaultGetNonce(contractAddress, session.walletAddress)
            val typedData = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],"Withdraw":[{"name":"player","type":"address"},{"name":"amount","type":"uint256"},{"name":"nonce","type":"uint256"}]},"primaryType":"Withdraw","domain":{"name":"MineFiVault","version":"1","chainId":$chainId,"verifyingContract":"$contractAddress"},"message":{"player":"${session.walletAddress}","amount":"$weiAmount","nonce":"$nonce"}}"""

            val signature = sessionManager.sendTypedDataAndWait(player.uniqueId, typedData)
            if (signature == null) {
                future.complete(WithdrawResult(false, "", amount, currency, "Signature rejected"))
                return@runTaskAsynchronously
            }

            try {
                val sigBytes = signature.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val txHash = chainService.vaultWithdraw(serverKey, contractAddress, session.walletAddress, weiAmount, sigBytes)
                future.complete(WithdrawResult(true, txHash, amount, currency))
            } catch (e: Exception) {
                future.complete(WithdrawResult(false, "", amount, currency, e.message))
            }
        }
        return future
    }

    override fun supportsWithdraw(): Boolean = true

    private fun removeQrMap(player: Player?) {
        if (player == null) return
        val held = player.inventory.itemInMainHand
        if (held.type == Material.FILLED_MAP && held.itemMeta?.displayName == "§6§lWallet Connect") {
            player.inventory.setItemInMainHand(com.minefi.listeners.PlayerListener.createWalletItem())
        }
    }

    fun isQrMap(item: ItemStack?): Boolean {
        return item != null && item.type == Material.FILLED_MAP && item.itemMeta?.displayName == "§6§lWallet Connect"
    }
}
