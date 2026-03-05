package com.minefi.relay

import com.google.gson.Gson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class JsonRpcTest {

    private val gson = Gson()

    @Test
    fun `serialize publish request`() {
        val req = JsonRpc.publishRequest(1, "topic123", "encrypted-message", 300, 1100)
        val json = gson.toJson(req)
        assertTrue(json.contains("irn_publish"))
        assertTrue(json.contains("topic123"))
    }

    @Test
    fun `serialize subscribe request`() {
        val req = JsonRpc.subscribeRequest(2, "topic456")
        val json = gson.toJson(req)
        assertTrue(json.contains("irn_subscribe"))
        assertTrue(json.contains("topic456"))
    }

    @Test
    fun `parse subscription message`() {
        val json = """{"id":1,"jsonrpc":"2.0","method":"irn_subscription","params":{"id":"sub1","data":{"topic":"t1","message":"encrypted"}}}"""
        val msg = JsonRpc.parseRelayMessage(json)
        assertNotNull(msg)
        assertEquals("irn_subscription", msg!!.method)
    }

    @Test
    fun `build session propose payload`() {
        val payload = JsonRpc.sessionPropose(
            publicKey = "aabbcc",
            chains = listOf("eip155:1"),
            methods = listOf("eth_sendTransaction", "personal_sign"),
            events = listOf("chainChanged", "accountsChanged"),
        )
        assertTrue(payload.contains("wc_sessionPropose"))
    }
}
