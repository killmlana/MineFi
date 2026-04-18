package com.minefi.provider

import com.minefi.MineFiPlugin
import com.minefi.map.QrGenerator
import com.google.gson.Gson
import com.google.gson.JsonParser
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import okhttp3.*
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class StripeProvider : PaymentProvider {

    override val name = "stripe"
    override val currencies get() = listOf("stripe_$currency")

    private lateinit var plugin: MineFiPlugin
    private var secretKey = ""
    private var currency = "usd"
    private var pollIntervalSeconds = 30L
    private var successUrl = "https://minefi.pages.dev/success"
    private var cancelUrl = "https://minefi.pages.dev/failure"
    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private val pendingSessions = ConcurrentHashMap<String, PendingCheckout>()

    private data class PendingCheckout(
        val sessionId: String,
        val playerUuid: UUID,
        val amount: Double,
        val currency: String,
        val future: CompletableFuture<DepositResult>,
    )

    override fun initialize(plugin: MineFiPlugin, config: ConfigurationSection) {
        this.plugin = plugin
        secretKey = config.getString("secret-key") ?: ""
        currency = config.getString("currency") ?: "usd"
        pollIntervalSeconds = config.getLong("poll-interval-seconds", 30)
        successUrl = config.getString("success-url") ?: "https://minefi.pages.dev/success"
        cancelUrl = config.getString("cancel-url") ?: "https://minefi.pages.dev/failure"

        if (secretKey.isBlank()) {
            plugin.logger.warning("Stripe secret key not configured")
            return
        }

        restorePendingSessions()

        val pollTicks = pollIntervalSeconds * 20
        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, { ->
            pollCompletedSessions()
        }, pollTicks, pollTicks)

        plugin.logger.info("Stripe provider initialized (polling every ${pollIntervalSeconds}s)")
    }

    override fun shutdown() {
        pendingSessions.values.forEach { it.future.complete(DepositResult(false, "", 0.0, "", "Plugin shutting down")) }
        pendingSessions.clear()
    }

    override fun connect(player: Player): Boolean = true
    override fun disconnect(player: Player) {}
    override fun isConnected(player: Player): Boolean = true
    override fun supportsWithdraw(): Boolean = false

    override fun startDeposit(player: Player, amount: Double, currency: String): CompletableFuture<DepositResult> {
        val future = CompletableFuture<DepositResult>()
        val amountCents = (amount * 100).toLong()

        plugin.server.scheduler.runTaskAsynchronously(plugin) { ->
            try {
                val formBody = FormBody.Builder()
                    .add("payment_method_types[]", "card")
                    .add("line_items[0][price_data][currency]", this.currency)
                    .add("line_items[0][price_data][product_data][name]", "MineFi Balance - ${player.name}")
                    .add("line_items[0][price_data][unit_amount]", amountCents.toString())
                    .add("line_items[0][quantity]", "1")
                    .add("mode", "payment")
                    .add("success_url", "$successUrl?session_id={CHECKOUT_SESSION_ID}")
                    .add("cancel_url", cancelUrl)
                    .add("metadata[player_uuid]", player.uniqueId.toString())
                    .add("metadata[player_name]", player.name)
                    .build()

                val request = Request.Builder()
                    .url("https://api.stripe.com/v1/checkout/sessions")
                    .post(formBody)
                    .header("Authorization", "Bearer $secretKey")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val json = JsonParser.parseString(body).asJsonObject

                if (json.has("id") && json.has("url")) {
                    val sessionId = json.get("id").asString
                    val url = json.get("url").asString

                    pendingSessions[sessionId] = PendingCheckout(
                        sessionId = sessionId,
                        playerUuid = player.uniqueId,
                        amount = amount,
                        currency = "stripe_${this.currency}",
                        future = future,
                    )
                    plugin.db.savePendingPayment(sessionId, player.uniqueId, "stripe", java.math.BigDecimal.valueOf(amount), "stripe_${this.currency}")

                    plugin.server.scheduler.runTask(plugin) { ->
                        player.sendMessage("§a§lPayment link ready!")
                        sendClickableLink(player, url)
                        giveQrMap(player, url)
                    }
                } else {
                    val error = json.getAsJsonObject("error")?.get("message")?.asString ?: "Unknown error"
                    future.complete(DepositResult(false, "", amount, currency, error))
                }
            } catch (e: Exception) {
                future.complete(DepositResult(false, "", amount, currency, e.message))
            }
        }
        return future
    }

    override fun startWithdraw(player: Player, amount: Double, currency: String): CompletableFuture<WithdrawResult> {
        return CompletableFuture.completedFuture(
            WithdrawResult(false, "", amount, currency, "Stripe does not support withdrawals")
        )
    }

    private fun sendClickableLink(player: Player, url: String) {
        val tc = TextComponent("§7[§a§lPay Now§7]")
        tc.clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, url)
        tc.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text("Click to open payment page"))
        player.spigot().sendMessage(tc)
    }

    private fun giveQrMap(player: Player, url: String) {
        val mapView = plugin.server.createMap(player.world)
        mapView.isUnlimitedTracking = true
        mapView.renderers.clear()
        mapView.addRenderer(object : MapRenderer() {
            private var rendered = false
            override fun render(view: MapView, canvas: MapCanvas, p: Player) {
                if (rendered) return
                val pixels = QrGenerator.generateQrPixels(url, 128)
                for (y in 0 until 128) {
                    for (x in 0 until 128) {
                        canvas.setPixel(x, y, if (pixels[y][x]) MapPalette.DARK_GRAY else MapPalette.WHITE)
                    }
                }
                rendered = true
            }
        })

        val mapItem = ItemStack(Material.FILLED_MAP)
        val meta = mapItem.itemMeta as MapMeta
        meta.setDisplayName("§6§lStripe Payment")
        meta.lore = listOf("§7Scan to pay with your phone")
        meta.mapView = mapView
        mapItem.itemMeta = meta
        player.inventory.setItemInMainHand(mapItem)
    }

    private fun restorePendingSessions() {
        val saved = plugin.db.getPendingPayments("stripe")
        for (payment in saved) {
            pendingSessions[payment.sessionId] = PendingCheckout(
                sessionId = payment.sessionId,
                playerUuid = payment.playerUuid,
                amount = payment.amount.toDouble(),
                currency = payment.currency,
                future = CompletableFuture(),
            )
        }
        if (saved.isNotEmpty()) {
            plugin.logger.info("Restored ${saved.size} pending Stripe sessions")
        }
        plugin.db.cleanExpiredPendingPayments()
    }

    private fun pollCompletedSessions() {
        val toRemove = mutableListOf<String>()
        for ((sessionId, checkout) in pendingSessions) {
            try {
                val request = Request.Builder()
                    .url("https://api.stripe.com/v1/checkout/sessions/$sessionId")
                    .header("Authorization", "Bearer $secretKey")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val json = JsonParser.parseString(body).asJsonObject
                val status = json.get("payment_status")?.asString

                if (status == "paid") {
                    val paymentIntent = json.get("payment_intent")?.asString ?: sessionId
                    plugin.server.scheduler.runTask(plugin) { ->
                        val p = plugin.server.getPlayer(checkout.playerUuid)
                        if (p != null) {
                            val held = p.inventory.itemInMainHand
                            if (held.type == Material.FILLED_MAP && held.itemMeta?.displayName == "§6§lStripe Payment") {
                                p.inventory.setItemInMainHand(null)
                            }
                        }
                    }
                    if (!checkout.future.isDone) {
                        checkout.future.complete(DepositResult(
                            success = true,
                            txRef = paymentIntent,
                            amount = checkout.amount,
                            currency = checkout.currency,
                        ))
                    } else {
                        creditRestoredPayment(checkout, paymentIntent)
                    }
                    plugin.db.deletePendingPayment(sessionId)
                    toRemove.add(sessionId)
                } else if (status == "expired" || status == "canceled") {
                    if (!checkout.future.isDone) {
                        checkout.future.complete(DepositResult(false, "", checkout.amount, checkout.currency, "Payment $status"))
                    }
                    plugin.db.deletePendingPayment(sessionId)
                    toRemove.add(sessionId)
                }
            } catch (e: Exception) {
                plugin.logger.warning("Stripe poll error for $sessionId: ${e.message}")
            }
        }
        toRemove.forEach { pendingSessions.remove(it) }
    }

    private fun creditRestoredPayment(checkout: PendingCheckout, txRef: String) {
        val amount = java.math.BigDecimal.valueOf(checkout.amount)
        plugin.db.adjustApprovedBalance(checkout.playerUuid, amount)
        plugin.db.saveDeposit(checkout.playerUuid, "stripe", checkout.currency, amount, txRef)
        plugin.db.saveTransaction(com.minefi.storage.TransactionRecord(
            txHash = txRef,
            playerUuid = checkout.playerUuid,
            fromAddress = "deposit",
            toAddress = "stripe",
            amount = "\$${checkout.amount}",
            blockNumber = 0,
            gasUsed = 0,
            status = true,
            timestamp = System.currentTimeMillis() / 1000,
        ))
        plugin.server.scheduler.runTask(plugin) { ->
            val p = plugin.server.getPlayer(checkout.playerUuid)
            p?.sendMessage("§a§lPayment received! §f\$${checkout.amount} added to your balance.")
        }
        plugin.logger.info("Credited restored Stripe payment: ${checkout.playerUuid} +\$${checkout.amount}")
    }
}
