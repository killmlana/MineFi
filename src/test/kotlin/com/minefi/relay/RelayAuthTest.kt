package com.minefi.relay

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.Base64

class RelayAuthTest {

    @Test
    fun `generateAuthJwt produces valid three-part JWT`() {
        val jwt = RelayAuth.generateAuthJwt("wss://relay.walletconnect.com")
        val parts = jwt.split(".")
        assertEquals(3, parts.size)
    }

    @Test
    fun `JWT header has EdDSA alg`() {
        val jwt = RelayAuth.generateAuthJwt("wss://relay.walletconnect.com")
        val header = String(Base64.getUrlDecoder().decode(jwt.split(".")[0]))
        assertTrue(header.contains("EdDSA"))
    }

    @Test
    fun `JWT payload has did key issuer`() {
        val jwt = RelayAuth.generateAuthJwt("wss://relay.walletconnect.com")
        val payload = String(Base64.getUrlDecoder().decode(jwt.split(".")[1]))
        assertTrue(payload.contains("did:key:z"))
    }

    @Test
    fun `JWT payload has correct audience`() {
        val jwt = RelayAuth.generateAuthJwt("wss://relay.walletconnect.com")
        val payload = String(Base64.getUrlDecoder().decode(jwt.split(".")[1]))
        assertTrue(payload.contains("wss://relay.walletconnect.com"))
    }
}
