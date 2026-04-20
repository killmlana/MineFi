package com.minefi.listeners

import com.minefi.MineFiPlugin
import com.minefi.gui.BookGui
import com.minefi.provider.CryptoProvider
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack

class PlayerListener(private val plugin: MineFiPlugin) : Listener {

    companion object {
        private const val WALLET_ITEM_NAME = "§6§lWallet"

        fun createWalletItem(): ItemStack {
            val item = ItemStack(Material.BOOK)
            val meta = item.itemMeta!!
            meta.setDisplayName(WALLET_ITEM_NAME)
            meta.lore = listOf("§7Right-click to open")
            item.itemMeta = meta
            return item
        }

        fun isWalletItem(item: ItemStack?): Boolean {
            if (item == null || item.type != Material.BOOK) return false
            return item.itemMeta?.displayName == WALLET_ITEM_NAME
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val hasWallet = player.inventory.contents.any { isWalletItem(it) }
        if (!hasWallet) {
            player.inventory.addItem(createWalletItem())
        }

        val crypto = plugin.providers["crypto"] as? CryptoProvider ?: return
        val session = crypto.sessionManager.activeSessions[player.uniqueId]
        if (session != null) {
            player.sendMessage("§aWallet connected: §f${session.walletAddress.take(6)}...${session.walletAddress.takeLast(4)}")
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!event.action.name.contains("RIGHT")) return
        val item = event.item ?: return
        if (!isWalletItem(item)) return

        event.isCancelled = true
        BookGui.openMainMenu(plugin, event.player)
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val item = event.itemDrop.itemStack
        if (isWalletItem(item)) {
            event.isCancelled = true
            return
        }
        val crypto = plugin.providers["crypto"] as? CryptoProvider ?: return
        if (crypto.isQrMap(item)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val crypto = plugin.providers["crypto"] as? CryptoProvider ?: return
        crypto.activeRenderers.remove(event.player.uniqueId)
    }
}
