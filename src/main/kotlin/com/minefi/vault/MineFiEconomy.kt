package com.minefi.vault

import com.minefi.MineFiPlugin
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import net.milkbowl.vault.economy.EconomyResponse.ResponseType
import org.bukkit.OfflinePlayer
import java.math.BigDecimal

class MineFiEconomy(private val plugin: MineFiPlugin) : Economy {

    override fun isEnabled(): Boolean = true
    override fun getName(): String = "MineFi"
    override fun hasBankSupport(): Boolean = false
    override fun fractionalDigits(): Int = 2
    override fun currencyNamePlural(): String = "dollars"
    override fun currencyNameSingular(): String = "dollar"
    override fun format(amount: Double): String = "$%.2f".format(amount)

    override fun has(player: OfflinePlayer, amount: Double): Boolean {
        return getBalance(player) >= amount
    }

    override fun has(player: OfflinePlayer, worldName: String, amount: Double): Boolean = has(player, amount)

    override fun getBalance(player: OfflinePlayer): Double {
        return plugin.db.getApprovedBalance(player.uniqueId).toDouble()
    }

    override fun getBalance(player: OfflinePlayer, world: String): Double = getBalance(player)

    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse {
        if (amount < 0) return EconomyResponse(0.0, getBalance(player), ResponseType.FAILURE, "Negative amount")

        val deductAmount = BigDecimal.valueOf(amount)
        val newBalance = plugin.db.tryDeduct(player.uniqueId, deductAmount)
            ?: return EconomyResponse(0.0, getBalance(player), ResponseType.FAILURE, "Insufficient approved balance")

        plugin.db.fifoSpend(player.uniqueId, deductAmount)
        return EconomyResponse(amount, newBalance.toDouble(), ResponseType.SUCCESS, null)
    }

    override fun withdrawPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse =
        withdrawPlayer(player, amount)

    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse {
        if (amount < 0) return EconomyResponse(0.0, getBalance(player), ResponseType.FAILURE, "Negative amount")

        val deposit = BigDecimal.valueOf(amount)
        val newBalance = plugin.db.adjustApprovedBalance(player.uniqueId, deposit)
        return EconomyResponse(amount, newBalance.toDouble(), ResponseType.SUCCESS, null)
    }

    override fun depositPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse =
        depositPlayer(player, amount)

    override fun hasAccount(player: OfflinePlayer): Boolean {
        return plugin.db.getApprovedBalance(player.uniqueId) > BigDecimal.ZERO ||
            plugin.db.getPlayer(player.uniqueId) != null
    }

    override fun hasAccount(player: OfflinePlayer, worldName: String): Boolean = hasAccount(player)
    override fun createPlayerAccount(player: OfflinePlayer): Boolean = false
    override fun createPlayerAccount(player: OfflinePlayer, worldName: String): Boolean = false

    @Deprecated("Use OfflinePlayer variant") override fun has(playerName: String, amount: Double) = false
    @Deprecated("Use OfflinePlayer variant") override fun has(playerName: String, worldName: String, amount: Double) = false
    @Deprecated("Use OfflinePlayer variant") override fun getBalance(playerName: String) = 0.0
    @Deprecated("Use OfflinePlayer variant") override fun getBalance(playerName: String, world: String) = 0.0
    @Deprecated("Use OfflinePlayer variant") override fun withdrawPlayer(playerName: String, amount: Double) =
        EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "Use UUID methods")
    @Deprecated("Use OfflinePlayer variant") override fun withdrawPlayer(playerName: String, worldName: String, amount: Double) =
        EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "Use UUID methods")
    @Deprecated("Use OfflinePlayer variant") override fun depositPlayer(playerName: String, amount: Double) =
        EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "Use UUID methods")
    @Deprecated("Use OfflinePlayer variant") override fun depositPlayer(playerName: String, worldName: String, amount: Double) =
        EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "Use UUID methods")
    @Deprecated("Use OfflinePlayer variant") override fun hasAccount(playerName: String) = false
    @Deprecated("Use OfflinePlayer variant") override fun hasAccount(playerName: String, worldName: String) = false
    @Deprecated("Use OfflinePlayer variant") override fun createPlayerAccount(playerName: String) = false
    @Deprecated("Use OfflinePlayer variant") override fun createPlayerAccount(playerName: String, worldName: String) = false

    override fun createBank(name: String, player: OfflinePlayer) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun createBank(name: String, player: String) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun deleteBank(name: String) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun bankBalance(name: String) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun bankHas(name: String, amount: Double) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun bankWithdraw(name: String, amount: Double) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun bankDeposit(name: String, amount: Double) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun isBankOwner(name: String, player: OfflinePlayer) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun isBankOwner(name: String, playerName: String) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun isBankMember(name: String, player: OfflinePlayer) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun isBankMember(name: String, playerName: String) = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun getBanks(): List<String> = emptyList()
}
