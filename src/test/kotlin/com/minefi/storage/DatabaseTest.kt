package com.minefi.storage

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.math.BigDecimal
import java.util.UUID

class DatabaseTest {

    private lateinit var db: Database
    private val testFile = File("build/test-minefi.db")

    @BeforeEach
    fun setUp() {
        testFile.delete()
        db = Database(testFile)
    }

    @AfterEach
    fun tearDown() {
        db.close()
        testFile.delete()
    }

    @Test
    fun `savePlayer and getPlayer roundtrip`() {
        val uuid = UUID.randomUUID()
        db.savePlayer(uuid, "0xABCDEF1234567890abcdef1234567890ABCDEF12", 1)

        val player = db.getPlayer(uuid)
        assertNotNull(player)
        assertEquals("0xABCDEF1234567890abcdef1234567890ABCDEF12", player!!.walletAddress)
        assertEquals(1, player.chainId)
    }

    @Test
    fun `getPlayer returns null for unknown UUID`() {
        assertNull(db.getPlayer(UUID.randomUUID()))
    }

    @Test
    fun `deletePlayer removes record`() {
        val uuid = UUID.randomUUID()
        db.savePlayer(uuid, "0x1234", 1)
        db.deletePlayer(uuid)
        assertNull(db.getPlayer(uuid))
    }

    @Test
    fun `saveSession and getSession roundtrip`() {
        val uuid = UUID.randomUUID()
        val key = "symmetric-key-bytes".toByteArray()
        db.saveSession(uuid, "topic123", key, System.currentTimeMillis() / 1000 + 3600)

        val session = db.getSession(uuid)
        assertNotNull(session)
        assertEquals("topic123", session!!.topic)
        assertArrayEquals(key, session.symmetricKey)
    }

    @Test
    fun `cleanExpiredSessions removes old sessions`() {
        val uuid = UUID.randomUUID()
        db.saveSession(uuid, "old-topic", byteArrayOf(1, 2, 3), 1000)
        db.cleanExpiredSessions()
        assertNull(db.getSession(uuid))
    }

    @Test
    fun `saveDeposit and getDeposits roundtrip`() {
        val uuid = UUID.randomUUID()
        db.saveDeposit(uuid, "crypto", "eip155:1", BigDecimal("5.0"), "0xabc123")
        val deposits = db.getDeposits(uuid)
        assertEquals(1, deposits.size)
        assertEquals("crypto", deposits[0].provider)
        assertEquals("eip155:1", deposits[0].chain)
        assertEquals(BigDecimal("5.0").compareTo(deposits[0].remaining), 0)
    }

    @Test
    fun `fifoSpend deducts from oldest deposit first`() {
        val uuid = UUID.randomUUID()
        db.saveDeposit(uuid, "crypto", "eip155:1", BigDecimal("3.0"), "tx1")
        db.saveDeposit(uuid, "stripe", "stripe_usd", BigDecimal("5.0"), "pi_123")
        db.fifoSpend(uuid, BigDecimal("4.0"))
        val deposits = db.getDeposits(uuid)
        assertEquals(BigDecimal("0.0").compareTo(deposits[0].remaining), 0)
        assertEquals(BigDecimal("4.0").compareTo(deposits[1].remaining), 0)
    }

    @Test
    fun `getWithdrawableByChain returns remaining per chain`() {
        val uuid = UUID.randomUUID()
        db.saveDeposit(uuid, "crypto", "eip155:1", BigDecimal("5.0"), "tx1")
        db.saveDeposit(uuid, "crypto", "eip155:1", BigDecimal("3.0"), "tx2")
        db.saveDeposit(uuid, "stripe", "stripe_usd", BigDecimal("10.0"), "pi_1")
        val byChain = db.getWithdrawableByChain(uuid)
        assertEquals(BigDecimal("8.0").compareTo(byChain["eip155:1"]), 0)
        assertEquals(BigDecimal("10.0").compareTo(byChain["stripe_usd"]), 0)
    }
}
