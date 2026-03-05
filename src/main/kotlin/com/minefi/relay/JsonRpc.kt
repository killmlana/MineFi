package com.minefi.relay

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class RelayMessage(
    val id: Long,
    val method: String?,
    val params: JsonObject?,
    val result: Any?,
    val error: JsonObject?,
)

object JsonRpc {

    private val gson = Gson()
    private var idCounter = System.currentTimeMillis() * 1000

    fun publishRequest(id: Long, topic: String, message: String, ttl: Int, tag: Int): Map<String, Any> {
        return mapOf(
            "id" to id,
            "jsonrpc" to "2.0",
            "method" to "irn_publish",
            "params" to mapOf(
                "topic" to topic,
                "message" to message,
                "ttl" to ttl,
                "tag" to tag,
                "prompt" to (tag == 1100),
            ),
        )
    }

    fun subscribeRequest(id: Long, topic: String): Map<String, Any> {
        return mapOf(
            "id" to id,
            "jsonrpc" to "2.0",
            "method" to "irn_subscribe",
            "params" to mapOf("topic" to topic),
        )
    }

    fun unsubscribeRequest(id: Long, topic: String, subscriptionId: String): Map<String, Any> {
        return mapOf(
            "id" to id,
            "jsonrpc" to "2.0",
            "method" to "irn_unsubscribe",
            "params" to mapOf("topic" to topic, "id" to subscriptionId),
        )
    }

    fun parseRelayMessage(json: String): RelayMessage? {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            RelayMessage(
                id = obj.get("id")?.asLong ?: 0,
                method = obj.get("method")?.asString,
                params = obj.getAsJsonObject("params"),
                result = obj.get("result"),
                error = obj.get("error")?.asJsonObject,
            )
        } catch (e: Exception) {
            null
        }
    }

    fun sessionPropose(
        publicKey: String,
        chains: List<String>,
        methods: List<String>,
        events: List<String>,
    ): String {
        val payload = mapOf(
            "id" to nextId(),
            "jsonrpc" to "2.0",
            "method" to "wc_sessionPropose",
            "params" to mapOf(
                "relays" to listOf(mapOf("protocol" to "irn")),
                "requiredNamespaces" to mapOf(
                    "eip155" to mapOf(
                        "chains" to chains,
                        "methods" to methods,
                        "events" to events,
                    ),
                ),
                "optionalNamespaces" to emptyMap<String, Any>(),
                "proposer" to mapOf(
                    "publicKey" to publicKey,
                    "metadata" to mapOf(
                        "name" to "MineFi",
                        "description" to "Minecraft WalletConnect Bridge",
                        "url" to "https://minefi.gg",
                        "icons" to listOf<String>(),
                    ),
                ),
            ),
        )
        return gson.toJson(payload)
    }

    fun sessionSettleResponse(id: Long): String {
        return gson.toJson(mapOf(
            "id" to id,
            "jsonrpc" to "2.0",
            "result" to true,
        ))
    }

    fun sessionRequest(id: Long, chainId: String, method: String, params: List<Any>): String {
        val payload = mapOf(
            "id" to id,
            "jsonrpc" to "2.0",
            "method" to "wc_sessionRequest",
            "params" to mapOf(
                "request" to mapOf(
                    "method" to method,
                    "params" to params,
                ),
                "chainId" to chainId,
            ),
        )
        return gson.toJson(payload)
    }

    fun nextId(): Long = ++idCounter
}
