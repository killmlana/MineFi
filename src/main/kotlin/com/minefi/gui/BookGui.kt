package com.minefi.gui

import com.minefi.MineFiPlugin
import com.minefi.provider.CryptoProvider
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import java.text.SimpleDateFormat
import java.util.Date

object BookGui {

    fun openMainMenu(plugin: MineFiPlugin, player: Player) {
        val pages = mutableListOf<TextComponent>()
        val page = TextComponent("")

        addTitle(page, "MineFi Wallet\n\n\n")

        val crypto = plugin.providers["crypto"]
        if (crypto != null && crypto.isConnected(player)) {
            addButton(page, "  Disconnect Wallet\n\n", "/wallet disconnect", "Unlink your crypto wallet")
        } else {
            addButton(page, "  Connect Wallet\n\n", "/wallet _connect", "Link your crypto wallet")
        }

        addButton(page, "  Add Funds\n\n", "/wallet _deposit_menu", "Deposit via card or crypto")
        addButton(page, "  Withdraw\n\n", "/wallet _withdraw_menu", "Withdraw to your wallet")
        addButton(page, "  Balance & Info\n\n", "/wallet _info", "View your balances")
        addButton(page, "  History\n\n", "/wallet _history", "View past transactions")
        addButton(page, "  Verify Balance\n", "/wallet _verify", "Check balance against chain")

        pages.add(page)
        openBook(player, "MineFi Wallet", pages)
    }

    fun openDepositMenu(plugin: MineFiPlugin, player: Player) {
        val pages = mutableListOf<TextComponent>()

        val stripe = plugin.providers["stripe"]
        if (stripe != null) {
            val cardPage = TextComponent("")
            addTitle(cardPage, "Add Funds\n\n")
            addSubtitle(cardPage, "Pay with Card\n\n\n")
            val amounts = plugin.config.getIntegerList("providers.stripe.amounts").ifEmpty { listOf(5, 10, 25, 50) }
            for ((i, amt) in amounts.withIndex()) {
                val trailing = if (i < amounts.size - 1) "\n\n" else "\n"
                addButton(cardPage, "   \$$amt$trailing", "/wallet _deposit stripe $amt", "Pay \$$amt with card")
            }
            addText(cardPage, "\n\n\n\n")
            pages.add(cardPage)
        }

        val crypto = plugin.providers["crypto"]
        if (crypto != null) {
            val cryptoPage = TextComponent("")
            addTitle(cryptoPage, "Add Funds\n\n")
            addSubtitle(cryptoPage, "Pay with Crypto\n\n\n")
            if (crypto.isConnected(player)) {
                val amts = listOf("0.01", "0.05", "0.1", "1.0")
                for ((i, amt) in amts.withIndex()) {
                    val trailing = if (i < amts.size - 1) "\n\n" else "\n"
                    addButton(cryptoPage, "   $amt ETH$trailing", "/wallet _deposit crypto $amt", "Deposit $amt ETH")
                }
                addText(cryptoPage, "\n\n\n\n")
            } else {
                addMuted(cryptoPage, "Connect wallet first\n")
                addMuted(cryptoPage, "for crypto deposits\n\n")
                addButton(cryptoPage, "  Connect Wallet\n", "/wallet _connect", "Link your crypto wallet")
            }
            pages.add(cryptoPage)
        }

        val razorpay = plugin.providers["razorpay"]
        if (razorpay != null) {
            val rpPage = TextComponent("")
            addTitle(rpPage, "Add Funds\n\n")
            addSubtitle(rpPage, "Pay with UPI / Card\n\n\n")
            val amounts = plugin.config.getIntegerList("providers.razorpay.amounts").ifEmpty { listOf(100, 500, 1000, 5000) }
            val currency = plugin.config.getString("providers.razorpay.currency") ?: "inr"
            val symbol = if (currency == "inr") "\u20B9" else currency.uppercase()
            for ((i, amt) in amounts.withIndex()) {
                val trailing = if (i < amounts.size - 1) "\n\n" else "\n"
                addButton(rpPage, "   $symbol$amt$trailing", "/wallet _deposit razorpay $amt", "Pay $symbol$amt")
            }
            addText(rpPage, "\n\n\n\n")
            pages.add(rpPage)
        }

        if (pages.isEmpty()) {
            val empty = TextComponent("")
            addTitle(empty, "Add Funds\n\n\n")
            addMuted(empty, "No payment providers\nconfigured.\n")
            pages.add(empty)
        }

        openBook(player, "Add Funds", pages)
    }

    fun openWithdrawMenu(plugin: MineFiPlugin, player: Player) {
        val pages = mutableListOf<TextComponent>()
        var currentPage = TextComponent("")
        addTitle(currentPage, "Withdraw Funds\n\n\n")
        var itemsOnPage = 0

        val byChain = plugin.db.getWithdrawableByChain(player.uniqueId)
        if (byChain.isEmpty()) {
            addMuted(currentPage, "No withdrawable funds.\n")
        } else {
            for ((chain, amount) in byChain) {
                if (itemsOnPage >= 4) {
                    pages.add(currentPage)
                    currentPage = TextComponent("")
                    addTitle(currentPage, "Withdraw (cont.)\n\n\n")
                    itemsOnPage = 0
                }
                if (chain.startsWith("stripe")) {
                    addMuted(currentPage, "$chain: \$${amount.toPlainString()}\n")
                    addMuted(currentPage, "  (non-refundable)\n\n")
                } else {
                    addButton(currentPage, "  $chain\n", "/wallet _withdraw $chain $amount", "Withdraw \$${amount.toPlainString()}")
                    addMuted(currentPage, "  \$${amount.toPlainString()} available\n\n")
                }
                itemsOnPage++
            }
        }

        pages.add(currentPage)
        openBook(player, "Withdraw", pages)
    }

    fun openInfoPage(plugin: MineFiPlugin, player: Player) {
        val pages = mutableListOf<TextComponent>()

        val page1 = TextComponent("")
        addTitle(page1, "Wallet Info\n\n\n")

        val balance = plugin.db.getApprovedBalance(player.uniqueId)
        addLabel(page1, "Spendable: ")
        addHighlight(page1, "\$${balance.toPlainString()}\n\n\n")

        val crypto = plugin.providers["crypto"]
        if (crypto != null && crypto.isConnected(player)) {
            val cp = crypto as CryptoProvider
            val session = cp.sessionManager.activeSessions[player.uniqueId]
            if (session != null) {
                addLabel(page1, "Address:\n")
                addMuted(page1, "${session.walletAddress.take(20)}\n")
                addMuted(page1, "...${session.walletAddress.takeLast(8)}\n\n")
                addLabel(page1, "Chain ID: ")
                addMuted(page1, "${session.chainId}\n")
            }
        } else {
            addMuted(page1, "No wallet connected\n")
        }

        pages.add(page1)

        val byChain = plugin.db.getWithdrawableByChain(player.uniqueId)
        if (byChain.isNotEmpty()) {
            val page2 = TextComponent("")
            addTitle(page2, "Deposit Breakdown\n\n\n")
            for ((chain, amount) in byChain) {
                addLabel(page2, "  $chain\n")
                addHighlight(page2, "  \$${amount.toPlainString()}\n\n")
            }
            pages.add(page2)
        }

        openBook(player, "Wallet Info", pages)
    }

    fun openHistory(plugin: MineFiPlugin, player: Player) {
        val txs = plugin.db.getTransactions(player.uniqueId)
        val pages = mutableListOf<TextComponent>()

        if (txs.isEmpty()) {
            val empty = TextComponent("")
            addTitle(empty, "History\n\n\n")
            addMuted(empty, "No transactions yet.\n")
            pages.add(empty)
            openBook(player, "History", pages)
            return
        }

        val dateFormat = SimpleDateFormat("MM/dd HH:mm")

        for (chunk in txs.chunked(3)) {
            val page = TextComponent("")
            addTitle(page, "History\n\n")
            for (tx in chunk) {
                val statusColor = if (tx.status) ChatColor.DARK_GREEN else ChatColor.DARK_RED
                val statusText = if (tx.status) "OK" else "FAIL"
                val date = dateFormat.format(Date(tx.timestamp * 1000))

                addLabel(page, "$date\n")
                addMuted(page, "To: ${tx.toAddress.take(8)}...${tx.toAddress.takeLast(4)}\n")
                addHighlight(page, "${tx.amount}\n")

                val status = TextComponent("[$statusText] ")
                status.color = statusColor
                status.isBold = true
                page.addExtra(status)

                addMuted(page, "${tx.txHash.take(14)}...\n\n")
            }
            pages.add(page)
        }

        openBook(player, "History", pages)
    }

    private fun openBook(player: Player, title: String, pages: List<TextComponent>) {
        val book = ItemStack(Material.WRITTEN_BOOK)
        val meta = book.itemMeta as BookMeta
        meta.title = title
        meta.author = "MineFi"
        for (page in pages) {
            meta.spigot().addPage(arrayOf(page))
        }
        book.itemMeta = meta
        player.openBook(book)
    }

    private fun addTitle(page: TextComponent, text: String) {
        val tc = TextComponent(text)
        tc.isBold = true
        tc.color = ChatColor.DARK_BLUE
        page.addExtra(tc)
    }

    private fun addSubtitle(page: TextComponent, text: String) {
        val tc = TextComponent(text)
        tc.isBold = true
        tc.color = ChatColor.DARK_PURPLE
        page.addExtra(tc)
    }

    private fun addButton(page: TextComponent, text: String, command: String, hover: String) {
        val tc = TextComponent(text)
        tc.color = ChatColor.DARK_GREEN
        tc.isBold = true
        tc.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, command)
        tc.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(hover))
        page.addExtra(tc)
    }

    private fun addLabel(page: TextComponent, text: String) {
        val tc = TextComponent(text)
        tc.color = ChatColor.BLACK
        page.addExtra(tc)
    }

    private fun addHighlight(page: TextComponent, text: String) {
        val tc = TextComponent(text)
        tc.color = ChatColor.DARK_RED
        tc.isBold = true
        page.addExtra(tc)
    }

    private fun addText(page: TextComponent, text: String) {
        page.addExtra(TextComponent(text))
    }

    private fun addMuted(page: TextComponent, text: String) {
        val tc = TextComponent(text)
        tc.color = ChatColor.DARK_GRAY
        tc.isItalic = true
        page.addExtra(tc)
    }
}
