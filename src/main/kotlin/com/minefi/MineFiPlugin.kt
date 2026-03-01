package com.minefi

import com.minefi.listeners.PlayerListener
import com.minefi.merkle.BalanceLeaf
import com.minefi.merkle.MerkleTree
import com.minefi.price.PriceService
import com.minefi.provider.CryptoProvider
import com.minefi.provider.PaymentProvider
import com.minefi.provider.RazorpayProvider
import com.minefi.provider.StripeProvider
import com.minefi.storage.Database
import com.minefi.vault.MineFiEconomy
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import org.web3j.utils.Convert
import java.io.File

class MineFiPlugin : JavaPlugin() {

    lateinit var db: Database
    val priceService = PriceService()
    val providers = mutableMapOf<String, PaymentProvider>()
    var currentMerkleTree: MerkleTree? = null

    override fun onEnable() {
        saveDefaultConfig()

        db = Database(File(dataFolder, "minefi.db"))
        db.cleanExpiredSessions()

        val providerTypes = mapOf<String, () -> PaymentProvider>(
            "crypto" to { CryptoProvider() },
            "stripe" to { StripeProvider() },
            "razorpay" to { RazorpayProvider() },
        )

        val providersSection = config.getConfigurationSection("providers")
        if (providersSection != null) {
            for (name in providersSection.getKeys(false)) {
                val section = providersSection.getConfigurationSection(name) ?: continue
                if (!section.getBoolean("enabled", false)) continue
                val factory = providerTypes[name]
                if (factory == null) {
                    logger.warning("Unknown provider type: $name")
                    continue
                }
                try {
                    val provider = factory()
                    provider.initialize(this, section)
                    providers[name] = provider
                    logger.info("$name provider enabled")
                } catch (e: Exception) {
                    logger.warning("Failed to initialize $name provider: ${e.message}")
                }
            }
        }

        val walletCommand = com.minefi.commands.WalletCommand(this)
        getCommand("wallet")?.setExecutor(walletCommand)
        getCommand("wallet")?.tabCompleter = walletCommand
        server.pluginManager.registerEvents(PlayerListener(this), this)

        if (config.getBoolean("vault.enabled", true) && server.pluginManager.getPlugin("Vault") != null) {
            val economy = MineFiEconomy(this)
            server.servicesManager.register(Economy::class.java, economy, this, ServicePriority.Normal)
            logger.info("Vault economy provider registered")
        }

        val crypto = providers["crypto"] as? CryptoProvider
        if (crypto != null) {
            val contractAddress = config.getString("providers.crypto.vault.escrow-contract") ?: ""
            val serverKey = config.getString("providers.crypto.vault.server-hot-wallet-key") ?: ""
            val rootInterval = config.getLong("vault.merkle-root-interval-minutes", 60)
            if (contractAddress.isNotBlank() && serverKey.isNotBlank()) {
                server.scheduler.runTaskTimerAsynchronously(this, { ->
                    publishMerkleRoot(crypto, contractAddress, serverKey)
                }, rootInterval * 60 * 20, rootInterval * 60 * 20)
            }
        }

        logger.info("MineFi enabled")
    }

    private fun publishMerkleRoot(crypto: CryptoProvider, contractAddress: String, serverKey: String) {
        try {
            val allPlayers = db.getAllBalances()
            if (allPlayers.isEmpty()) return

            val leaves = allPlayers.map { (uuid, balance) ->
                val player = db.getPlayer(uuid) ?: return@map null
                val weiBalance = Convert.toWei(balance, Convert.Unit.ETHER).toBigInteger()
                BalanceLeaf(player.walletAddress, weiBalance)
            }.filterNotNull()

            if (leaves.isEmpty()) return

            val tree = MerkleTree(leaves)
            currentMerkleTree = tree

            val txHash = crypto.chainService.vaultUpdateRoot(serverKey, contractAddress, tree.root)
            logger.info("Merkle root published: ${tree.rootHex()} tx=$txHash")
        } catch (e: Exception) {
            logger.warning("Failed to publish Merkle root: ${e.message}")
        }
    }

    override fun onDisable() {
        providers.values.forEach { it.shutdown() }
        db.close()
        logger.info("MineFi disabled")
    }
}
