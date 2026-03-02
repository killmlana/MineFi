package com.minefi.storage

import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

data class PlayerRecord(
    val uuid: UUID,
    val walletAddress: String,
    val chainId: Int,
    val connectedAt: Long,
)

data class SessionRecord(
    val uuid: UUID,
    val topic: String,
    val symmetricKey: ByteArray,
    val expiry: Long,
)

data class DepositRecord(
    val id: Long,
    val playerUuid: UUID,
    val provider: String,
    val chain: String,
    val amount: BigDecimal,
    val remaining: BigDecimal,
    val txRef: String,
    val timestamp: Long,
)

data class TransactionRecord(
    val txHash: String,
    val playerUuid: UUID,
    val fromAddress: String,
    val toAddress: String,
    val amount: String,
    val blockNumber: Long,
    val gasUsed: Long,
    val status: Boolean,
    val timestamp: Long,
)

class Database(file: File) {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    init {
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS players (
                player_uuid TEXT PRIMARY KEY,
                wallet_address TEXT NOT NULL,
                chain_id INTEGER NOT NULL,
                connected_at INTEGER NOT NULL
            )
        """)
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS sessions (
                player_uuid TEXT PRIMARY KEY,
                session_topic TEXT NOT NULL,
                symmetric_key BLOB NOT NULL,
                expiry INTEGER NOT NULL
            )
        """)
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS pending_payments (
                session_id TEXT PRIMARY KEY,
                player_uuid TEXT NOT NULL,
                provider TEXT NOT NULL,
                amount TEXT NOT NULL,
                currency TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS balances (
                player_uuid TEXT PRIMARY KEY,
                approved_balance TEXT NOT NULL DEFAULT '0'
            )
        """)
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS transactions (
                tx_hash TEXT PRIMARY KEY,
                player_uuid TEXT NOT NULL,
                from_address TEXT NOT NULL,
                to_address TEXT NOT NULL,
                amount TEXT NOT NULL,
                block_number INTEGER NOT NULL,
                gas_used INTEGER NOT NULL,
                status INTEGER NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """)
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS deposits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                provider TEXT NOT NULL,
                chain TEXT NOT NULL,
                amount TEXT NOT NULL,
                remaining TEXT NOT NULL,
                tx_ref TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """)
    }

    fun savePlayer(uuid: UUID, walletAddress: String, chainId: Int) {
        val stmt = conn.prepareStatement("""
            INSERT OR REPLACE INTO players (player_uuid, wallet_address, chain_id, connected_at)
            VALUES (?, ?, ?, ?)
        """)
        stmt.setString(1, uuid.toString())
        stmt.setString(2, walletAddress)
        stmt.setInt(3, chainId)
        stmt.setLong(4, System.currentTimeMillis() / 1000)
        stmt.executeUpdate()
    }

    fun getPlayer(uuid: UUID): PlayerRecord? {
        val stmt = conn.prepareStatement("SELECT * FROM players WHERE player_uuid = ?")
        stmt.setString(1, uuid.toString())
        val rs = stmt.executeQuery()
        if (!rs.next()) return null
        return PlayerRecord(
            uuid = UUID.fromString(rs.getString("player_uuid")),
            walletAddress = rs.getString("wallet_address"),
            chainId = rs.getInt("chain_id"),
            connectedAt = rs.getLong("connected_at"),
        )
    }

    fun deletePlayer(uuid: UUID) {
        val stmt = conn.prepareStatement("DELETE FROM players WHERE player_uuid = ?")
        stmt.setString(1, uuid.toString())
        stmt.executeUpdate()
        deleteSession(uuid)
    }

    fun saveSession(uuid: UUID, topic: String, symmetricKey: ByteArray, expiry: Long) {
        val stmt = conn.prepareStatement("""
            INSERT OR REPLACE INTO sessions (player_uuid, session_topic, symmetric_key, expiry)
            VALUES (?, ?, ?, ?)
        """)
        stmt.setString(1, uuid.toString())
        stmt.setString(2, topic)
        stmt.setBytes(3, symmetricKey)
        stmt.setLong(4, expiry)
        stmt.executeUpdate()
    }

    fun getSession(uuid: UUID): SessionRecord? {
        val stmt = conn.prepareStatement("SELECT * FROM sessions WHERE player_uuid = ?")
        stmt.setString(1, uuid.toString())
        val rs = stmt.executeQuery()
        if (!rs.next()) return null
        return SessionRecord(
            uuid = UUID.fromString(rs.getString("player_uuid")),
            topic = rs.getString("session_topic"),
            symmetricKey = rs.getBytes("symmetric_key"),
            expiry = rs.getLong("expiry"),
        )
    }

    fun deleteSession(uuid: UUID) {
        val stmt = conn.prepareStatement("DELETE FROM sessions WHERE player_uuid = ?")
        stmt.setString(1, uuid.toString())
        stmt.executeUpdate()
    }

    fun cleanExpiredSessions() {
        val now = System.currentTimeMillis() / 1000
        conn.prepareStatement("DELETE FROM sessions WHERE expiry < ?").apply {
            setLong(1, now)
            executeUpdate()
        }
    }

    fun getAllActiveSessions(): List<SessionRecord> {
        val now = System.currentTimeMillis() / 1000
        val rs = conn.prepareStatement("SELECT * FROM sessions WHERE expiry >= ?").apply {
            setLong(1, now)
        }.executeQuery()
        val results = mutableListOf<SessionRecord>()
        while (rs.next()) {
            results.add(SessionRecord(
                uuid = UUID.fromString(rs.getString("player_uuid")),
                topic = rs.getString("session_topic"),
                symmetricKey = rs.getBytes("symmetric_key"),
                expiry = rs.getLong("expiry"),
            ))
        }
        return results
    }

    fun saveTransaction(tx: TransactionRecord) {
        val stmt = conn.prepareStatement("""
            INSERT OR REPLACE INTO transactions (tx_hash, player_uuid, from_address, to_address, amount, block_number, gas_used, status, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, tx.txHash)
        stmt.setString(2, tx.playerUuid.toString())
        stmt.setString(3, tx.fromAddress)
        stmt.setString(4, tx.toAddress)
        stmt.setString(5, tx.amount)
        stmt.setLong(6, tx.blockNumber)
        stmt.setLong(7, tx.gasUsed)
        stmt.setInt(8, if (tx.status) 1 else 0)
        stmt.setLong(9, tx.timestamp)
        stmt.executeUpdate()
    }

    fun getTransactions(uuid: UUID, limit: Int = 50): List<TransactionRecord> {
        val rs = conn.prepareStatement(
            "SELECT * FROM transactions WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT ?"
        ).apply {
            setString(1, uuid.toString())
            setInt(2, limit)
        }.executeQuery()
        val results = mutableListOf<TransactionRecord>()
        while (rs.next()) {
            results.add(TransactionRecord(
                txHash = rs.getString("tx_hash"),
                playerUuid = UUID.fromString(rs.getString("player_uuid")),
                fromAddress = rs.getString("from_address"),
                toAddress = rs.getString("to_address"),
                amount = rs.getString("amount"),
                blockNumber = rs.getLong("block_number"),
                gasUsed = rs.getLong("gas_used"),
                status = rs.getInt("status") == 1,
                timestamp = rs.getLong("timestamp"),
            ))
        }
        return results
    }

    fun saveDeposit(uuid: UUID, provider: String, chain: String, amount: BigDecimal, txRef: String) {
        val stmt = conn.prepareStatement("""
            INSERT INTO deposits (player_uuid, provider, chain, amount, remaining, tx_ref, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, uuid.toString())
        stmt.setString(2, provider)
        stmt.setString(3, chain)
        stmt.setString(4, amount.toPlainString())
        stmt.setString(5, amount.toPlainString())
        stmt.setString(6, txRef)
        stmt.setLong(7, System.currentTimeMillis() / 1000)
        stmt.executeUpdate()
    }

    fun getDeposits(uuid: UUID): List<DepositRecord> {
        val rs = conn.prepareStatement(
            "SELECT * FROM deposits WHERE player_uuid = ? ORDER BY timestamp ASC"
        ).apply { setString(1, uuid.toString()) }.executeQuery()
        val results = mutableListOf<DepositRecord>()
        while (rs.next()) {
            results.add(DepositRecord(
                id = rs.getLong("id"),
                playerUuid = UUID.fromString(rs.getString("player_uuid")),
                provider = rs.getString("provider"),
                chain = rs.getString("chain"),
                amount = BigDecimal(rs.getString("amount")),
                remaining = BigDecimal(rs.getString("remaining")),
                txRef = rs.getString("tx_ref"),
                timestamp = rs.getLong("timestamp"),
            ))
        }
        return results
    }

    @Synchronized
    fun fifoSpend(uuid: UUID, amount: BigDecimal) {
        try {
            conn.autoCommit = false
            var left = amount
            val deposits = getDeposits(uuid).filter { it.remaining > BigDecimal.ZERO }
            for (dep in deposits) {
                if (left <= BigDecimal.ZERO) break
                val deduct = left.min(dep.remaining)
                val newRemaining = dep.remaining - deduct
                conn.prepareStatement("UPDATE deposits SET remaining = ? WHERE id = ?").apply {
                    setString(1, newRemaining.toPlainString())
                    setLong(2, dep.id)
                    executeUpdate()
                }
                left -= deduct
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun getWithdrawableByChain(uuid: UUID): Map<String, BigDecimal> {
        val rs = conn.prepareStatement("""
            SELECT chain, SUM(CAST(remaining AS REAL)) as total
            FROM deposits WHERE player_uuid = ? AND CAST(remaining AS REAL) > 0
            GROUP BY chain
        """).apply { setString(1, uuid.toString()) }.executeQuery()
        val results = mutableMapOf<String, BigDecimal>()
        while (rs.next()) {
            results[rs.getString("chain")] = BigDecimal(rs.getString("total"))
        }
        return results
    }

    fun getApprovedBalance(uuid: UUID): BigDecimal {
        val stmt = conn.prepareStatement("SELECT approved_balance FROM balances WHERE player_uuid = ?")
        stmt.setString(1, uuid.toString())
        val rs = stmt.executeQuery()
        if (!rs.next()) return BigDecimal.ZERO
        return BigDecimal(rs.getString("approved_balance"))
    }

    fun setApprovedBalance(uuid: UUID, balance: BigDecimal) {
        val stmt = conn.prepareStatement("""
            INSERT OR REPLACE INTO balances (player_uuid, approved_balance)
            VALUES (?, ?)
        """)
        stmt.setString(1, uuid.toString())
        stmt.setString(2, balance.toPlainString())
        stmt.executeUpdate()
    }

    fun getAllBalances(): Map<UUID, BigDecimal> {
        val rs = conn.prepareStatement("SELECT player_uuid, approved_balance FROM balances WHERE approved_balance != '0'")
            .executeQuery()
        val results = mutableMapOf<UUID, BigDecimal>()
        while (rs.next()) {
            results[UUID.fromString(rs.getString("player_uuid"))] = BigDecimal(rs.getString("approved_balance"))
        }
        return results
    }

    @Synchronized
    fun adjustApprovedBalance(uuid: UUID, delta: BigDecimal): BigDecimal {
        try {
            conn.autoCommit = false
            val current = getApprovedBalance(uuid)
            val newBalance = (current + delta).coerceAtLeast(BigDecimal.ZERO)
            setApprovedBalance(uuid, newBalance)
            conn.commit()
            return newBalance
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    fun tryDeduct(uuid: UUID, amount: BigDecimal): BigDecimal? {
        try {
            conn.autoCommit = false
            val current = getApprovedBalance(uuid)
            if (current < amount) {
                conn.rollback()
                return null
            }
            val newBalance = current - amount
            setApprovedBalance(uuid, newBalance)
            conn.commit()
            return newBalance
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    data class PendingPayment(
        val sessionId: String,
        val playerUuid: UUID,
        val provider: String,
        val amount: BigDecimal,
        val currency: String,
        val createdAt: Long,
    )

    fun savePendingPayment(sessionId: String, uuid: UUID, provider: String, amount: BigDecimal, currency: String) {
        val stmt = conn.prepareStatement("""
            INSERT OR REPLACE INTO pending_payments (session_id, player_uuid, provider, amount, currency, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, sessionId)
        stmt.setString(2, uuid.toString())
        stmt.setString(3, provider)
        stmt.setString(4, amount.toPlainString())
        stmt.setString(5, currency)
        stmt.setLong(6, System.currentTimeMillis() / 1000)
        stmt.executeUpdate()
    }

    fun getPendingPayments(provider: String): List<PendingPayment> {
        val rs = conn.prepareStatement(
            "SELECT * FROM pending_payments WHERE provider = ?"
        ).apply { setString(1, provider) }.executeQuery()
        val results = mutableListOf<PendingPayment>()
        while (rs.next()) {
            results.add(PendingPayment(
                sessionId = rs.getString("session_id"),
                playerUuid = UUID.fromString(rs.getString("player_uuid")),
                provider = rs.getString("provider"),
                amount = BigDecimal(rs.getString("amount")),
                currency = rs.getString("currency"),
                createdAt = rs.getLong("created_at"),
            ))
        }
        return results
    }

    fun deletePendingPayment(sessionId: String) {
        conn.prepareStatement("DELETE FROM pending_payments WHERE session_id = ?")
            .apply { setString(1, sessionId); executeUpdate() }
    }

    fun cleanExpiredPendingPayments(maxAgeSeconds: Long = 86400) {
        val cutoff = System.currentTimeMillis() / 1000 - maxAgeSeconds
        conn.prepareStatement("DELETE FROM pending_payments WHERE created_at < ?")
            .apply { setLong(1, cutoff); executeUpdate() }
    }

    fun close() {
        conn.close()
    }
}
