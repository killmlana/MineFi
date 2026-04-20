package com.minefi.provider

import com.minefi.MineFiPlugin
import com.minefi.map.QrGenerator
import com.google.gson.JsonParser
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import okhttp3.*
import okhttp3.Credentials as OkCredentials
import okhttp3.MediaType.Companion.toMediaType
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

@Suppress("DEPRECATION")
class RazorpayProvider : PaymentProvider {

    override val name = "razorpay"
    override val currencies get() = listOf("razorpay_$currency")

    private lateinit var plugin: MineFiPlugin
    private var keyId = ""
    private var keySecret = ""
    private var currency = "inr"
    private var pollIntervalSeconds = 10L
    private var successUrl = "https://minefi.saikia.me/success"
    private val httpClient = OkHttpClient()
    private val pendingOrders = ConcurrentHashMap<String, PendingOrder>()
    private val playerMapViews = ConcurrentHashMap<UUID, MapView>()

    private data class PendingOrder(
        val orderId: String,
        val playerUuid: UUID,
        val amount: Double,
        val currency: String,
        val future: CompletableFuture<DepositResult>,
    )

    override fun initialize(plugin: MineFiPlugin, config: ConfigurationSection) {
        this.plugin = plugin
        keyId = config.getString("key-id") ?: ""
        keySecret = config.getString("key-secret") ?: ""
        currency = config.getString("currency") ?: "inr"
        pollIntervalSeconds = config.getLong("poll-interval-seconds", 10)
        successUrl = config.getString("success-url") ?: "https://minefi.saikia.me/success"

        if (keyId.isBlank() || keySecret.isBlank()) {
            plugin.logger.warning("Razorpay credentials not configured")
            return
        }

        restorePendingOrders()

        val pollTicks = pollIntervalSeconds * 20
        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, { ->
            pollCompletedOrders()
        }, pollTicks, pollTicks)

        plugin.logger.info("Razorpay provider initialized (polling every ${pollIntervalSeconds}s)")
    }

    override fun shutdown() {
        pendingOrders.values.forEach {
            if (!it.future.isDone) it.future.complete(DepositResult(false, "", 0.0, "", "Plugin shutting down"))
        }
        pendingOrders.clear()
    }

    override fun connect(player: Player): Boolean = true
    override fun disconnect(player: Player) {}
    override fun isConnected(player: Player): Boolean = true
    override fun supportsWithdraw(): Boolean = false

    override fun startDeposit(player: Player, amount: Double, currency: String): CompletableFuture<DepositResult> {
        val future = CompletableFuture<DepositResult>()
        val amountPaise = (amount * 100).toLong()

        if (amountPaise < 100) {
            future.complete(DepositResult(false, "", amount, currency, "Minimum amount is 1 ${this.currency.uppercase()}"))
            return future
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin) { ->
            try {
                val linkResult = createPaymentLink(amountPaise, player)
                if (linkResult == null) {
                    future.complete(DepositResult(false, "", amount, currency, "Failed to create payment link"))
                    return@runTaskAsynchronously
                }

                val (linkId, linkUrl) = linkResult

                pendingOrders[linkId] = PendingOrder(
                    orderId = linkId,
                    playerUuid = player.uniqueId,
                    amount = amount,
                    currency = "razorpay_${this.currency}",
                    future = future,
                )
                plugin.db.savePendingPayment(linkId, player.uniqueId, "razorpay",
                    java.math.BigDecimal.valueOf(amount), "razorpay_${this.currency}")

                plugin.server.scheduler.runTask(plugin) { ->
                    player.sendMessage("§a§lPayment link ready!")
                    sendClickableLink(player, linkUrl)
                    giveQrMap(player, linkUrl)
                }
            } catch (e: Exception) {
                future.complete(DepositResult(false, "", amount, currency, e.message))
            }
        }
        return future
    }

    override fun startWithdraw(player: Player, amount: Double, currency: String): CompletableFuture<WithdrawResult> {
        return CompletableFuture.completedFuture(
            WithdrawResult(false, "", amount, currency, "Razorpay does not support withdrawals")
        )
    }

    private fun createPaymentLink(amountPaise: Long, player: Player): Pair<String, String>? {
        val notes = """{"player_uuid":"${player.uniqueId}","player_name":"${player.name}"}"""
        val base = """{"amount":$amountPaise,"currency":"${currency.uppercase()}","description":"MineFi Balance - ${player.name}","callback_url":"$successUrl","callback_method":"get","notes":$notes"""

        val upiJson = """$base,"upi_link":true}"""
        val upiBody = RequestBody.create(JSON_MEDIA_TYPE, upiJson)
        val upiRequest = Request.Builder()
            .url("https://api.razorpay.com/v1/payment_links")
            .post(upiBody)
            .header("Authorization", OkCredentials.basic(keyId, keySecret))
            .build()

        val upiResponse = httpClient.newCall(upiRequest).execute()
        val upiResponseBody = upiResponse.body?.string() ?: ""
        val upiObj = JsonParser.parseString(upiResponseBody).asJsonObject

        if (upiObj.has("id") && upiObj.has("short_url")) {
            val id = upiObj.get("id").asString
            val url = upiObj.get("short_url").asString
            plugin.logger.info("Razorpay UPI link created: $id")
            return Pair(id, url)
        }

        val json = """$base}"""
        val body = RequestBody.create(JSON_MEDIA_TYPE, json)
        val request = Request.Builder()
            .url("https://api.razorpay.com/v1/payment_links")
            .post(body)
            .header("Authorization", OkCredentials.basic(keyId, keySecret))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: return null
        val obj = JsonParser.parseString(responseBody).asJsonObject
        val id = obj.get("id")?.asString ?: return null
        val url = obj.get("short_url")?.asString ?: return null
        plugin.logger.info("Razorpay payment link created: $id")
        return Pair(id, url)
    }

    private fun restorePendingOrders() {
        val saved = plugin.db.getPendingPayments("razorpay")
        for (payment in saved) {
            pendingOrders[payment.sessionId] = PendingOrder(
                orderId = payment.sessionId,
                playerUuid = payment.playerUuid,
                amount = payment.amount.toDouble(),
                currency = payment.currency,
                future = CompletableFuture(),
            )
        }
        if (saved.isNotEmpty()) {
            plugin.logger.info("Restored ${saved.size} pending Razorpay orders")
        }
        plugin.db.cleanExpiredPendingPayments()
    }

    private fun pollCompletedOrders() {
        val toRemove = mutableListOf<String>()
        for ((linkId, order) in pendingOrders) {
            try {
                val request = Request.Builder()
                    .url("https://api.razorpay.com/v1/payment_links/$linkId")
                    .header("Authorization", OkCredentials.basic(keyId, keySecret))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: continue
                val obj = JsonParser.parseString(body).asJsonObject
                val status = obj.get("status")?.asString

                when (status) {
                    "paid" -> {
                        val paymentsArr = obj.getAsJsonArray("payments")
                        val paymentId = paymentsArr?.firstOrNull()?.asJsonObject
                            ?.get("payment_id")?.asString
                            ?: linkId

                        plugin.server.scheduler.runTask(plugin) { ->
                            val p = plugin.server.getPlayer(order.playerUuid)
                            if (p != null) {
                                showReceipt(p, order.amount, paymentId)
                            }
                        }

                        if (!order.future.isDone) {
                            order.future.complete(DepositResult(
                                success = true,
                                txRef = paymentId,
                                amount = order.amount,
                                currency = order.currency,
                            ))
                        } else {
                            creditRestoredPayment(order, paymentId)
                        }
                        plugin.db.deletePendingPayment(linkId)
                        toRemove.add(linkId)
                    }
                    "expired", "cancelled" -> {
                        if (!order.future.isDone) {
                            order.future.complete(DepositResult(false, "", order.amount, order.currency, "Payment $status"))
                        }
                        plugin.db.deletePendingPayment(linkId)
                        toRemove.add(linkId)
                    }
                }
            } catch (e: Exception) {
                plugin.logger.warning("Razorpay poll error for $linkId: ${e.message}")
            }
        }
        toRemove.forEach { pendingOrders.remove(it) }
    }

    private fun creditRestoredPayment(order: PendingOrder, txRef: String) {
        val amount = java.math.BigDecimal.valueOf(order.amount)
        plugin.db.adjustApprovedBalance(order.playerUuid, amount)
        plugin.db.saveDeposit(order.playerUuid, "razorpay", order.currency, amount, txRef)
        plugin.db.saveTransaction(com.minefi.storage.TransactionRecord(
            txHash = txRef,
            playerUuid = order.playerUuid,
            fromAddress = "deposit",
            toAddress = "razorpay",
            amount = "${order.amount} ${currency.uppercase()}",
            blockNumber = 0,
            gasUsed = 0,
            status = true,
            timestamp = System.currentTimeMillis() / 1000,
        ))
        plugin.server.scheduler.runTask(plugin) { ->
            val p = plugin.server.getPlayer(order.playerUuid)
            p?.sendMessage("§a§lPayment received! §f${order.amount} ${currency.uppercase()} added to your balance.")
        }
        plugin.logger.info("Credited restored Razorpay payment: ${order.playerUuid} +${order.amount}")
    }

    private fun sendClickableLink(player: Player, url: String) {
        val tc = TextComponent("§7[§a§lPay Now§7]")
        tc.clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, url)
        tc.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text("Click to open payment page"))
        player.spigot().sendMessage(tc)
    }

    private fun showReceipt(player: Player, amount: Double, paymentId: String) {
        val mapView = playerMapViews.remove(player.uniqueId)
        if (mapView != null) {
            mapView.renderers.clear()
            mapView.addRenderer(object : MapRenderer() {
                private var rendered = false
                override fun render(view: MapView, canvas: MapCanvas, p: Player) {
                    if (rendered) return
                    val bg = MapPalette.WHITE
                    val accent = MapPalette.matchColor(0x22, 0xC5, 0x5E)
                    val dark = MapPalette.DARK_GRAY
                    val light = MapPalette.LIGHT_GRAY

                    for (y in 0 until 128) {
                        for (x in 0 until 128) {
                            canvas.setPixel(x, y, bg)
                        }
                    }

                    for (y in 0 until 3) {
                        for (x in 0 until 128) {
                            canvas.setPixel(x, y, accent)
                        }
                    }

                    val cx = 64; val cy = 20
                    for (i in 0..4) {
                        canvas.setPixel(cx - 6 + i, cy + i, accent)
                        canvas.setPixel(cx - 6 + i, cy + i + 1, accent)
                    }
                    for (i in 0..8) {
                        canvas.setPixel(cx - 2 + i, cy + 4 - i, accent)
                        canvas.setPixel(cx - 2 + i, cy + 5 - i, accent)
                    }

                    val font = org.bukkit.map.MinecraftFont.Font

                    canvas.drawText(30, 34, font, "§32;CONFIRMED")

                    for (x in 14 until 114) {
                        canvas.setPixel(x, 46, light)
                    }

                    val symbol = if (currency == "inr") "Rs" else currency.uppercase()
                    canvas.drawText(14, 54, font, "§118;Amount")
                    canvas.drawText(14, 66, font, "§48;$symbol $amount")

                    for (x in 14 until 114) {
                        canvas.setPixel(x, 78, light)
                    }

                    canvas.drawText(14, 86, font, "§118;Payment ID")
                    canvas.drawText(14, 98, font, "§48;${paymentId.take(16)}")

                    for (y in 125 until 128) {
                        for (x in 0 until 128) {
                            canvas.setPixel(x, y, accent)
                        }
                    }

                    rendered = true
                }
            })
        }
        val held = player.inventory.itemInMainHand
        if (held.type == Material.FILLED_MAP && held.itemMeta?.displayName == "§6§lRazorpay Payment") {
            val meta = held.itemMeta as MapMeta
            meta.setDisplayName("§a§lPayment Receipt")
            meta.lore = listOf("§7$amount ${currency.uppercase()}", "§7${paymentId.take(20)}")
            held.itemMeta = meta
        }
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
        playerMapViews[player.uniqueId] = mapView

        val mapItem = ItemStack(Material.FILLED_MAP)
        val meta = mapItem.itemMeta as MapMeta
        meta.setDisplayName("§6§lRazorpay Payment")
        meta.lore = listOf("§7Scan to pay with your phone")
        meta.mapView = mapView
        mapItem.itemMeta = meta
        player.inventory.setItemInMainHand(mapItem)
    }

    private val JSON_MEDIA_TYPE: okhttp3.MediaType = "application/json".toMediaType()
}
