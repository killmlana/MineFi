package com.minefi.provider

import com.minefi.MineFiPlugin
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

data class DepositResult(
    val success: Boolean,
    val txRef: String,
    val amount: Double,
    val currency: String,
    val message: String? = null,
)

data class WithdrawResult(
    val success: Boolean,
    val txRef: String,
    val amount: Double,
    val currency: String,
    val message: String? = null,
)

interface PaymentProvider {
    val name: String
    val currencies: List<String>

    fun initialize(plugin: MineFiPlugin, config: ConfigurationSection)
    fun shutdown()

    fun connect(player: Player): Boolean
    fun disconnect(player: Player)
    fun isConnected(player: Player): Boolean

    fun startDeposit(player: Player, amount: Double, currency: String): CompletableFuture<DepositResult>
    fun startWithdraw(player: Player, amount: Double, currency: String): CompletableFuture<WithdrawResult>
    fun supportsWithdraw(): Boolean
}
