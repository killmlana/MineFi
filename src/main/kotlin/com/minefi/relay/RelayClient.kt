package com.minefi.relay

import com.google.gson.Gson
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class RelayClient(
    private val projectId: String,
    private val relayUrl: String,
    private val logger: Logger,
    private val onMessage: (topic: String, message: String) -> Unit,
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val gson = Gson()
    private var ws: WebSocket? = null
    private val subscriptions = ConcurrentHashMap<String, String>()
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    @Volatile private var running = false

    fun connect() {
        running = true
        val auth = RelayAuth.generateAuthJwt(relayUrl)
        val url = "$relayUrl/?auth=$auth&projectId=$projectId"
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.info("Connected to WalletConnect relay")
                reconnectAttempts = 0
                resubscribeAll()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.warning("Relay connection failed: ${t.message}")
                if (running) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logger.info("Relay connection closed: $reason")
                if (running) scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        running = false
        ws?.close(1000, "Plugin shutdown")
        client.dispatcher.executorService.shutdown()
    }

    fun subscribe(topic: String) {
        val id = JsonRpc.nextId()
        val msg = gson.toJson(JsonRpc.subscribeRequest(id, topic))
        ws?.send(msg)
    }

    fun publish(topic: String, message: String, tag: Int, ttl: Int = 300) {
        val id = JsonRpc.nextId()
        val msg = gson.toJson(JsonRpc.publishRequest(id, topic, message, ttl, tag))
        logger.info("Publishing to topic ${topic.take(8)}... tag=$tag")
        ws?.send(msg)
    }

    private fun handleMessage(text: String) {
        val msg = JsonRpc.parseRelayMessage(text) ?: return

        if (msg.method == "irn_subscription") {
            val params = msg.params ?: return
            val data = params.getAsJsonObject("data") ?: return
            val topic = data.get("topic")?.asString ?: return
            val message = data.get("message")?.asString ?: return
            onMessage(topic, message)
        }
    }

    private fun resubscribeAll() {
        subscriptions.keys.forEach { subscribe(it) }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            logger.severe("Max reconnect attempts reached")
            return
        }
        reconnectAttempts++
        val delay = minOf(1000L * (1 shl reconnectAttempts), 60_000L)
        logger.info("Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        Thread {
            Thread.sleep(delay)
            if (running) connect()
        }.start()
    }

    fun addSubscription(topic: String) {
        subscriptions[topic] = ""
        subscribe(topic)
    }

    fun removeSubscription(topic: String) {
        subscriptions.remove(topic)
    }
}
