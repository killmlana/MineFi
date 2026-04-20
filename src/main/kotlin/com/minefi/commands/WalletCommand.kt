package com.minefi.commands

import com.minefi.MineFiPlugin
import com.minefi.gui.BookGui
import com.minefi.merkle.BalanceLeaf
import com.minefi.merkle.MerkleTree
import com.minefi.provider.CryptoProvider
import com.minefi.storage.TransactionRecord
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WalletCommand(private val plugin: MineFiPlugin) : CommandExecutor, TabCompleter {

    private val withdrawCooldowns = ConcurrentHashMap<UUID, Long>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cOnly players can use this command.")
            return true
        }

        if (args.isEmpty()) {
            BookGui.openMainMenu(plugin, sender)
            return true
        }

        when (args[0].lowercase()) {
            "connect", "_connect" -> handleConnect(sender)
            "disconnect" -> handleDisconnect(sender)
            "info", "_info" -> BookGui.openInfoPage(plugin, sender)
            "history", "_history" -> BookGui.openHistory(plugin, sender)
            "verify", "_verify" -> handleVerify(sender)
            "_deposit_menu" -> BookGui.openDepositMenu(plugin, sender)
            "_withdraw_menu" -> BookGui.openWithdrawMenu(plugin, sender)
            "_deposit", "approve" -> handleDeposit(sender, args)
            "_withdraw", "withdraw" -> handleWithdraw(sender, args)
            "send" -> handleSend(sender, args)
            "sign" -> handleSign(sender, args.drop(1).joinToString(" "))
            else -> BookGui.openMainMenu(plugin, sender)
        }
        return true
    }

    private fun handleConnect(player: Player) {
        val crypto = plugin.providers["crypto"]
        if (crypto == null) {
            player.sendMessage("§cCrypto provider not enabled.")
            return
        }
        if (crypto.isConnected(player)) {
            player.sendMessage("§eWallet already connected.")
            return
        }
        crypto.connect(player)
    }

    private fun handleDisconnect(player: Player) {
        val crypto = plugin.providers["crypto"]
        if (crypto != null) {
            crypto.disconnect(player)
        }
        player.sendMessage("§cWallet disconnected.")
    }

    private fun handleDeposit(player: Player, args: Array<out String>) {
        val providerName: String
        val amount: Double

        if (args[0] == "approve" && args.size >= 2) {
            val crypto = plugin.providers["crypto"]
            providerName = "crypto"
            amount = args[1].toDoubleOrNull() ?: run {
                player.sendMessage("§cInvalid amount.")
                return
            }
            if (crypto == null) {
                player.sendMessage("§cCrypto provider not enabled.")
                return
            }
        } else if (args.size >= 3) {
            providerName = args[1]
            amount = args[2].toDoubleOrNull() ?: run {
                player.sendMessage("§cInvalid amount.")
                return
            }
        } else {
            player.sendMessage("§cUsage: /wallet approve <amount>")
            return
        }

        val provider = plugin.providers[providerName]
        if (provider == null) {
            player.sendMessage("§cProvider '$providerName' not available.")
            return
        }

        val currency = provider.currencies.firstOrNull() ?: return

        val future = provider.startDeposit(player, amount, currency)
        future.thenAccept { result ->
            plugin.server.scheduler.runTask(plugin) { ->
                if (result.success) {
                    val usdAmount = when (providerName) {
                        "stripe" -> BigDecimal.valueOf(result.amount)
                        "razorpay" -> plugin.priceService.inrToUsd(BigDecimal.valueOf(result.amount))
                        else -> plugin.priceService.ethToUsd(BigDecimal.valueOf(result.amount))
                    }

                    if (usdAmount <= BigDecimal.ZERO && providerName != "stripe") {
                        player.sendMessage("§cFailed to fetch exchange rate. Deposit not credited.")
                        return@runTask
                    }

                    val walletAddr = if (providerName == "crypto") {
                        (plugin.providers["crypto"] as? com.minefi.provider.CryptoProvider)
                            ?.sessionManager?.activeSessions?.get(player.uniqueId)?.walletAddress
                    } else null

                    plugin.db.adjustApprovedBalance(player.uniqueId, usdAmount)
                    plugin.db.saveDeposit(player.uniqueId, providerName, currency, usdAmount, result.txRef, walletAddr)

                    plugin.db.saveTransaction(TransactionRecord(
                        txHash = result.txRef,
                        playerUuid = player.uniqueId,
                        fromAddress = "deposit",
                        toAddress = providerName,
                        amount = when (providerName) {
                            "stripe" -> "\$${result.amount}"
                            "razorpay" -> "\u20B9${result.amount} (\$${usdAmount.toPlainString()})"
                            else -> "${result.amount} ETH (\$${usdAmount.toPlainString()})"
                        },
                        blockNumber = 0,
                        gasUsed = 0,
                        status = true,
                        timestamp = System.currentTimeMillis() / 1000,
                    ))

                    val newBalance = plugin.db.getApprovedBalance(player.uniqueId)
                    player.sendMessage("§a§l━━━ Funds Added ━━━")
                    when (providerName) {
                        "stripe" -> player.sendMessage("§7Deposited: §6\$${result.amount}")
                        "razorpay" -> {
                            player.sendMessage("§7Deposited: §6\u20B9${result.amount}")
                            player.sendMessage("§7Converted: §6\$${usdAmount.toPlainString()}")
                        }
                        else -> {
                            player.sendMessage("§7Deposited: §6${result.amount} ETH")
                            player.sendMessage("§7Converted: §6\$${usdAmount.toPlainString()}")
                        }
                    }
                    player.sendMessage("§7Spendable: §6\$${newBalance.toPlainString()}")
                    player.sendMessage("§a§l━━━━━━━━━━━━━━━━━━━")
                } else {
                    player.sendMessage("§cDeposit failed: ${result.message ?: "unknown error"}")
                }
            }
        }
    }

    private fun handleWithdraw(player: Player, args: Array<out String>) {
        val chain: String
        val amount: Double

        if (args[0] == "withdraw" && args.size >= 2) {
            chain = "eip155:${plugin.config.getInt("providers.crypto.chain.chain-id", 1)}"
            amount = args[1].toDoubleOrNull() ?: run {
                player.sendMessage("§cInvalid amount.")
                return
            }
        } else if (args.size >= 3) {
            chain = args[1]
            amount = args[2].toDoubleOrNull() ?: run {
                player.sendMessage("§cInvalid amount.")
                return
            }
        } else {
            BookGui.openWithdrawMenu(plugin, player)
            return
        }

        if (chain.startsWith("stripe")) {
            player.sendMessage("§cStripe deposits cannot be withdrawn.")
            return
        }

        val cooldownSeconds = plugin.config.getLong("vault.withdrawal-cooldown-seconds", 300)
        val lastWithdraw = withdrawCooldowns[player.uniqueId] ?: 0
        val elapsed = System.currentTimeMillis() / 1000 - lastWithdraw
        if (elapsed < cooldownSeconds) {
            player.sendMessage("§cWithdrawal on cooldown. Try again in §f${cooldownSeconds - elapsed}s")
            return
        }

        val crypto = plugin.providers["crypto"] as? CryptoProvider
        if (crypto == null || !crypto.isConnected(player)) {
            player.sendMessage("§cCrypto wallet not connected.")
            return
        }

        val usdAmount = BigDecimal.valueOf(amount)
        val ethAmount = plugin.priceService.usdToEth(usdAmount)
        if (ethAmount <= BigDecimal.ZERO) {
            player.sendMessage("§cFailed to fetch ETH price.")
            return
        }

        val deducted = plugin.db.tryDeduct(player.uniqueId, usdAmount)
        if (deducted == null) {
            player.sendMessage("§cInsufficient balance.")
            return
        }

        player.sendMessage("§7Converting \$${usdAmount.toPlainString()} → ${ethAmount.toPlainString()} ETH")

        val future = crypto.startWithdraw(player, ethAmount.toDouble(), chain)
        future.thenAccept { result ->
            plugin.server.scheduler.runTask(plugin) { ->
                if (result.success) {
                    plugin.db.fifoSpend(player.uniqueId, usdAmount)
                    withdrawCooldowns[player.uniqueId] = System.currentTimeMillis() / 1000

                    plugin.db.saveTransaction(TransactionRecord(
                        txHash = result.txRef,
                        playerUuid = player.uniqueId,
                        fromAddress = "withdraw",
                        toAddress = chain,
                        amount = "\$${usdAmount.toPlainString()} (${ethAmount.toPlainString()} ETH)",
                        blockNumber = 0,
                        gasUsed = 0,
                        status = true,
                        timestamp = System.currentTimeMillis() / 1000,
                    ))

                    player.sendMessage("§a§l━━━ Withdrawal Complete ━━━")
                    player.sendMessage("§7Amount: §6\$${usdAmount.toPlainString()}")
                    player.sendMessage("§7Sent: §6${ethAmount.toPlainString()} ETH")
                    player.sendMessage("§7Chain: §f$chain")
                    player.sendMessage("§7TX: §f${result.txRef.take(18)}...")
                    player.sendMessage("§a§l━━━━━━━━━━━━━━━━━━━━━━━━━")
                } else {
                    plugin.db.adjustApprovedBalance(player.uniqueId, usdAmount)
                    player.sendMessage("§cWithdrawal failed: ${result.message}")
                    player.sendMessage("§aBalance restored.")
                }
            }
        }
    }

    private fun handleSend(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            player.sendMessage("§cUsage: /wallet send <address> <amount>")
            return
        }
        val to = args[1]
        if (!to.startsWith("0x") || to.length != 42) {
            player.sendMessage("§cInvalid address.")
            return
        }
        val amount = args[2].toDoubleOrNull()
        if (amount == null || amount <= 0) {
            player.sendMessage("§cInvalid amount.")
            return
        }

        val crypto = plugin.providers["crypto"] as? CryptoProvider
        if (crypto == null || !crypto.isConnected(player)) {
            player.sendMessage("§cCrypto wallet not connected.")
            return
        }

        val session = crypto.sessionManager.activeSessions[player.uniqueId] ?: return

        val weiValue = Convert.toWei(BigDecimal.valueOf(amount), Convert.Unit.ETHER).toBigInteger()
        val hexValue = "0x" + weiValue.toString(16)

        player.sendMessage("§eSending §6$amount ETH §eto §f${to.take(6)}...${to.takeLast(4)}")
        player.sendMessage("§eCheck your wallet app to approve.")

        plugin.server.scheduler.runTaskAsynchronously(plugin) { ->
            val txHash = crypto.sessionManager.sendTransactionAndWait(
                playerUuid = player.uniqueId, to = to, value = hexValue,
            )
            if (txHash != null) {
                val receipt = try { crypto.chainService.getTransactionReceipt(txHash) } catch (e: Exception) { null }

                if (receipt != null) {
                    plugin.db.saveTransaction(TransactionRecord(
                        txHash = txHash, playerUuid = player.uniqueId,
                        fromAddress = session.walletAddress, toAddress = to,
                        amount = "$amount ETH", blockNumber = receipt.blockNumber.toLong(),
                        gasUsed = receipt.gasUsed.toLong(), status = receipt.status == "0x1",
                        timestamp = System.currentTimeMillis() / 1000,
                    ))
                }

                plugin.server.scheduler.runTask(plugin) { ->
                    player.sendMessage("§a§lTransaction confirmed!")
                    player.sendMessage("§7TX: §f${txHash.take(18)}...")
                }
            } else {
                plugin.server.scheduler.runTask(plugin) { ->
                    player.sendMessage("§cTransaction failed or was rejected.")
                }
            }
        }
    }

    private fun handleSign(player: Player, message: String) {
        if (message.isBlank()) {
            player.sendMessage("§cUsage: /wallet sign <message>")
            return
        }
        val crypto = plugin.providers["crypto"] as? CryptoProvider
        if (crypto == null || !crypto.isConnected(player)) {
            player.sendMessage("§cCrypto wallet not connected.")
            return
        }
        crypto.sessionManager.sendSignRequest(player.uniqueId, message)
        player.sendMessage("§eSign request sent to your wallet app.")
    }

    private fun handleVerify(player: Player) {
        val crypto = plugin.providers["crypto"] as? CryptoProvider
        if (crypto == null || !crypto.isConnected(player)) {
            player.sendMessage("§cCrypto wallet not connected.")
            return
        }

        val tree = plugin.currentMerkleTree
        if (tree == null) {
            player.sendMessage("§cNo Merkle root published yet.")
            return
        }

        val session = crypto.sessionManager.activeSessions[player.uniqueId] ?: return
        val balance = plugin.db.getApprovedBalance(player.uniqueId)
        val weiBalance = Convert.toWei(balance, Convert.Unit.ETHER).toBigInteger()
        val leaf = BalanceLeaf(session.walletAddress, weiBalance)
        val proof = tree.getProof(session.walletAddress)

        if (proof == null) {
            player.sendMessage("§cYour address not in the last published root.")
            return
        }

        val valid = MerkleTree.verify(proof, tree.root, MerkleTree.hashLeaf(leaf))

        player.sendMessage("§6§l━━━ Balance Verification ━━━")
        player.sendMessage("§7Balance: §6${balance.toPlainString()} ETH")
        player.sendMessage("§7Root: §f${tree.rootHex().take(16)}...")
        if (valid) {
            player.sendMessage("§a§lVerified — matches on-chain root")
        } else {
            player.sendMessage("§c§lMismatch — changed since last root update")
        }
        player.sendMessage("§6§l━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("connect", "disconnect", "info", "send", "approve", "withdraw", "history", "verify", "sign")
                .filter { it.startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}
