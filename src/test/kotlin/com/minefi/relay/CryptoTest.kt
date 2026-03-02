package com.minefi.relay

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CryptoTest {

    @Test
    fun `generateKeyPair produces valid X25519 keypair`() {
        val kp = Crypto.generateKeyPair()
        assertNotNull(kp.privateKey)
        assertNotNull(kp.publicKey)
        assertEquals(32, kp.publicKey.size)
    }

    @Test
    fun `deriveSharedSecret is symmetric`() {
        val alice = Crypto.generateKeyPair()
        val bob = Crypto.generateKeyPair()
        val secretA = Crypto.deriveSharedSecret(alice.privateKey, bob.publicKey)
        val secretB = Crypto.deriveSharedSecret(bob.privateKey, alice.publicKey)
        assertArrayEquals(secretA, secretB)
    }

    @Test
    fun `encrypt and decrypt roundtrip`() {
        val key = Crypto.generateSymmetricKey()
        val plaintext = "hello walletconnect"
        val encrypted = Crypto.encrypt(key, plaintext)
        val decrypted = Crypto.decrypt(key, encrypted)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt with wrong key fails`() {
        val key1 = Crypto.generateSymmetricKey()
        val key2 = Crypto.generateSymmetricKey()
        val encrypted = Crypto.encrypt(key1, "secret")
        assertThrows(Exception::class.java) {
            Crypto.decrypt(key2, encrypted)
        }
    }

    @Test
    fun `generateTopic produces 64-char hex string`() {
        val topic = Crypto.generateTopic()
        assertEquals(64, topic.length)
        assertTrue(topic.all { it in "0123456789abcdef" })
    }

    @Test
    fun `deriveSymmetricKey from shared secret produces 32 bytes`() {
        val alice = Crypto.generateKeyPair()
        val bob = Crypto.generateKeyPair()
        val symKey = Crypto.deriveSymmetricKey(alice.privateKey, bob.publicKey)
        assertEquals(32, symKey.size)
    }
}
