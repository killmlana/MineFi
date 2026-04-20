package com.minefi.relay

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.minefi.storage.Database
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

data class PendingPairing(
    val playerUuid: UUID,
    val topic: String,
    val selfPublicKey: ByteArray,
    val selfPrivateKey: ByteArray,
    val symmetricKey: ByteArray,
    val proposeId: Long,
)

data class PendingSession(
    val playerUuid: UUID,
    val sessionTopic: String,
    val sessionKey: ByteArray,
    val pairingTopic: String,
)

data class ActiveSession(
    val playerUuid: UUID,
    val topic: String,
    val symmetricKey: ByteArray,
    val walletAddress: String,
    val chainId: Int,
)

class SessionManager(
    private val relay: RelayClient,
    private val db: Database,
    private val logger: Logger,
    private val chainId: Int,
    private val metadataUrl: String = "https://minefi.saikia.me",
    private val onSessionEstablished: (UUID, String) -> Unit,
    private val onSessionFailed: (UUID, String) -> Unit = { _, _ -> },
    private val onSessionRequest: (UUID, Long, String) -> Unit,
) {

    private data class PendingRequest(val future: CompletableFuture<String>, val createdAt: Long = System.currentTimeMillis())

    private val gson = Gson()
    private val pendingPairings = ConcurrentHashMap<String, PendingPairing>()
    private val pendingSessions = ConcurrentHashMap<String, PendingSession>()
    val activeSessions = ConcurrentHashMap<UUID, ActiveSession>()
    private val pendingRequests = ConcurrentHashMap<Long, PendingRequest>()

    fun initiatePairing(playerUuid: UUID): String {
        val keyPair = Crypto.generateKeyPair()
        val symKey = Crypto.generateSymmetricKey()
        val topic = Crypto.generateTopic()

        val pubKeyHex = keyPair.publicKey.joinToString("") { "%02x".format(it) }
        val proposePayload = JsonRpc.sessionPropose(
            publicKey = pubKeyHex,
            chains = listOf("eip155:$chainId"),
            methods = listOf("eth_sendTransaction", "personal_sign", "eth_signTypedData_v4"),
            events = listOf("chainChanged", "accountsChanged"),
            metadataUrl = metadataUrl,
        )
        val proposeId = JsonParser.parseString(proposePayload).asJsonObject.get("id").asLong

        val pairing = PendingPairing(
            playerUuid = playerUuid,
            topic = topic,
            selfPublicKey = keyPair.publicKey,
            selfPrivateKey = keyPair.privateKey,
            symmetricKey = symKey,
            proposeId = proposeId,
        )
        pendingPairings[topic] = pairing

        relay.addSubscription(topic)

        val encrypted = Crypto.encrypt(symKey, proposePayload)
        relay.publish(topic, encrypted, tag = 1100)

        val symKeyHex = symKey.joinToString("") { "%02x".format(it) }
        val uri = "wc:$topic@2?relay-protocol=irn&symKey=$symKeyHex"

        logger.info("Pairing initiated for $playerUuid on topic ${topic.take(8)}...")
        return uri
    }

    fun handleRelayMessage(topic: String, encryptedMessage: String) {
        val pairing = pendingPairings[topic]
        val pendingSession = pendingSessions[topic]
        val session = activeSessions.values.find { it.topic == topic }

        when {
            pairing != null -> handlePairingResponse(pairing, encryptedMessage)
            pendingSession != null -> handleSessionSettle(pendingSession, encryptedMessage)
            session != null -> handleSessionMessage(session, encryptedMessage)
            else -> logger.fine("Message on unknown topic: ${topic.take(8)}...")
        }
    }

    private fun handlePairingResponse(pairing: PendingPairing, encrypted: String) {
        try {
            val json = Crypto.decrypt(pairing.symmetricKey, encrypted)
            logger.info("Pairing response: ${json.take(200)}")
            val obj = JsonParser.parseString(json).asJsonObject

            val result = obj.getAsJsonObject("result")
            if (result != null) {
                val responderPubKeyHex = result.get("responderPublicKey")?.asString ?: return
                val responderPubKey = responderPubKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

                val sessionKey = Crypto.deriveSymmetricKey(pairing.selfPrivateKey, responderPubKey)
                val sessionTopic = Crypto.deriveTopicFromKey(sessionKey)

                val pending = PendingSession(
                    playerUuid = pairing.playerUuid,
                    sessionTopic = sessionTopic,
                    sessionKey = sessionKey,
                    pairingTopic = pairing.topic,
                )
                pendingSessions[sessionTopic] = pending

                relay.addSubscription(sessionTopic)
                pendingPairings.remove(pairing.topic)

                logger.info("Session approval received, subscribing to session topic ${sessionTopic.take(8)}...")
            }

            val error = obj.getAsJsonObject("error")
            if (error != null) {
                val reason = error.get("message")?.asString ?: "Unknown error"
                logger.warning("Session proposal rejected: $reason")
                onSessionFailed(pairing.playerUuid, reason)
                pendingPairings.remove(pairing.topic)
            }
        } catch (e: Exception) {
            logger.warning("Failed to handle pairing response: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleSessionSettle(pending: PendingSession, encrypted: String) {
        try {
            val json = Crypto.decrypt(pending.sessionKey, encrypted)
            logger.info("Session settle: ${json.take(200)}")
            val obj = JsonParser.parseString(json).asJsonObject

            val method = obj.get("method")?.asString
            val id = obj.get("id")?.asLong ?: 0

            if (method == "wc_sessionSettle") {
                val params = obj.getAsJsonObject("params")
                val namespaces = params?.getAsJsonObject("namespaces")
                val eip155 = namespaces?.getAsJsonObject("eip155")
                val accounts = eip155?.getAsJsonArray("accounts")
                val account = accounts?.get(0)?.asString ?: return

                val walletAddress = account.split(":").lastOrNull() ?: return

                val ackPayload = JsonRpc.sessionSettleResponse(id)
                val ackEncrypted = Crypto.encrypt(pending.sessionKey, ackPayload)
                relay.publish(pending.sessionTopic, ackEncrypted, tag = 1103)

                val session = ActiveSession(
                    playerUuid = pending.playerUuid,
                    topic = pending.sessionTopic,
                    symmetricKey = pending.sessionKey,
                    walletAddress = walletAddress,
                    chainId = chainId,
                )

                activeSessions[pending.playerUuid] = session
                pendingSessions.remove(pending.sessionTopic)

                db.savePlayer(pending.playerUuid, walletAddress, chainId)
                db.saveSession(pending.playerUuid, pending.sessionTopic, pending.sessionKey,
                    System.currentTimeMillis() / 1000 + 86400 * 7)

                logger.info("Session established: ${pending.playerUuid} -> $walletAddress")
                onSessionEstablished(pending.playerUuid, walletAddress)
            }
        } catch (e: Exception) {
            logger.warning("Failed to handle session settle: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleSessionMessage(session: ActiveSession, encrypted: String) {
        try {
            val json = Crypto.decrypt(session.symmetricKey, encrypted)
            val obj = JsonParser.parseString(json).asJsonObject

            val id = obj.get("id")?.asLong ?: return
            val result = obj.get("result")
            val error = obj.getAsJsonObject("error")

            val pending = pendingRequests.remove(id)
            if (pending != null) {
                if (result != null) {
                    pending.future.complete(result.asString)
                } else if (error != null) {
                    pending.future.completeExceptionally(
                        RuntimeException(error.get("message")?.asString ?: "Request rejected")
                    )
                }
            }

            if (result != null) {
                onSessionRequest(session.playerUuid, id, result.asString)
            }
        } catch (e: Exception) {
            logger.warning("Failed to handle session message: ${e.message}")
        }
    }

    fun sendTransactionRequest(playerUuid: UUID, to: String, value: String, data: String = "0x"): Long? {
        val session = activeSessions[playerUuid] ?: return null
        val id = JsonRpc.nextId()
        val payload = JsonRpc.sessionRequest(
            id = id,
            chainId = "eip155:${session.chainId}",
            method = "eth_sendTransaction",
            params = listOf(mapOf(
                "from" to session.walletAddress,
                "to" to to,
                "value" to value,
                "data" to data,
            )),
        )
        val encrypted = Crypto.encrypt(session.symmetricKey, payload)
        relay.publish(session.topic, encrypted, tag = 1108)
        return id
    }

    fun sendSignRequest(playerUuid: UUID, message: String): Long? {
        val session = activeSessions[playerUuid] ?: return null
        val id = JsonRpc.nextId()
        val payload = JsonRpc.sessionRequest(
            id = id,
            chainId = "eip155:${session.chainId}",
            method = "personal_sign",
            params = listOf(message, session.walletAddress),
        )
        val encrypted = Crypto.encrypt(session.symmetricKey, payload)
        relay.publish(session.topic, encrypted, tag = 1108)
        return id
    }

    fun sendTypedDataAndWait(
        playerUuid: UUID,
        typedData: String,
        timeoutSeconds: Long = 60,
    ): String? {
        val session = activeSessions[playerUuid] ?: return null
        val id = JsonRpc.nextId()
        val future = CompletableFuture<String>()
        pendingRequests[id] = PendingRequest(future)

        val payload = JsonRpc.sessionRequest(
            id = id,
            chainId = "eip155:${session.chainId}",
            method = "eth_signTypedData_v4",
            params = listOf(session.walletAddress, typedData),
        )
        val encrypted = Crypto.encrypt(session.symmetricKey, payload)
        relay.publish(session.topic, encrypted, tag = 1108)

        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pendingRequests.remove(id)
            logger.warning("Sign typed data failed or timed out: ${e.message}")
            null
        }
    }

    fun sendTransactionAndWait(
        playerUuid: UUID,
        to: String,
        value: String,
        data: String = "0x",
        timeoutSeconds: Long = 60,
    ): String? {
        val session = activeSessions[playerUuid] ?: return null
        val id = JsonRpc.nextId()
        val future = CompletableFuture<String>()
        pendingRequests[id] = PendingRequest(future)

        val payload = JsonRpc.sessionRequest(
            id = id,
            chainId = "eip155:${session.chainId}",
            method = "eth_sendTransaction",
            params = listOf(mapOf(
                "from" to session.walletAddress,
                "to" to to,
                "value" to value,
                "data" to data,
            )),
        )
        val encrypted = Crypto.encrypt(session.symmetricKey, payload)
        relay.publish(session.topic, encrypted, tag = 1108)

        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pendingRequests.remove(id)
            logger.warning("Transaction request failed or timed out: ${e.message}")
            null
        }
    }

    fun disconnectSession(playerUuid: UUID) {
        val session = activeSessions.remove(playerUuid) ?: return
        relay.removeSubscription(session.topic)
        db.deletePlayer(playerUuid)
        logger.info("Session disconnected for $playerUuid")
    }

    fun cleanupStalePendingRequests(maxAgeMs: Long = 120_000) {
        val now = System.currentTimeMillis()
        val stale = pendingRequests.entries.filter { now - it.value.createdAt > maxAgeMs }
        for (entry in stale) {
            pendingRequests.remove(entry.key)
            entry.value.future.completeExceptionally(RuntimeException("Request expired"))
        }
        if (stale.isNotEmpty()) {
            logger.info("Cleaned up ${stale.size} stale pending requests")
        }
    }

    fun restoreSessions() {
        val sessions = db.getAllActiveSessions()
        for (record in sessions) {
            val player = db.getPlayer(record.uuid) ?: continue
            val session = ActiveSession(
                playerUuid = record.uuid,
                topic = record.topic,
                symmetricKey = record.symmetricKey,
                walletAddress = player.walletAddress,
                chainId = player.chainId,
            )
            activeSessions[record.uuid] = session
            relay.addSubscription(record.topic)
        }
        logger.info("Restored ${sessions.size} sessions from database")
    }
}
