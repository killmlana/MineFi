package com.minefi.merkle

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Security

data class BalanceLeaf(
    val address: String,
    val balance: BigInteger,
)

class MerkleTree(leaves: List<BalanceLeaf>) {

    val root: ByteArray
    private val layers: List<List<ByteArray>>
    private val leafHashes: List<ByteArray>
    private val leafIndex: Map<String, Int>

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        val sorted = leaves.sortedBy { it.address.lowercase() }
        leafIndex = sorted.mapIndexed { i, leaf -> leaf.address.lowercase() to i }.toMap()
        leafHashes = sorted.map { hashLeaf(it) }

        val allLayers = mutableListOf(leafHashes)
        var current = leafHashes
        while (current.size > 1) {
            val next = mutableListOf<ByteArray>()
            for (i in current.indices step 2) {
                val left = current[i]
                val right = if (i + 1 < current.size) current[i + 1] else left
                next.add(hashPair(left, right))
            }
            allLayers.add(next)
            current = next
        }
        layers = allLayers
        root = if (current.isNotEmpty()) current[0] else ByteArray(32)
    }

    fun getProof(address: String): List<ByteArray>? {
        val idx = leafIndex[address.lowercase()] ?: return null
        val proof = mutableListOf<ByteArray>()
        var index = idx
        for (layerIdx in 0 until layers.size - 1) {
            val layer = layers[layerIdx]
            val siblingIdx = if (index % 2 == 0) index + 1 else index - 1
            if (siblingIdx < layer.size) {
                proof.add(layer[siblingIdx])
            } else {
                proof.add(layer[index])
            }
            index /= 2
        }
        return proof
    }

    fun rootHex(): String = root.joinToString("") { "%02x".format(it) }

    companion object {
        fun hashLeaf(leaf: BalanceLeaf): ByteArray {
            val addressBytes = leaf.address.removePrefix("0x").lowercase().padStart(64, '0')
            val balanceBytes = leaf.balance.toString(16).padStart(64, '0')
            val packed = (addressBytes + balanceBytes).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return keccak256(keccak256(packed))
        }

        fun hashPair(a: ByteArray, b: ByteArray): ByteArray {
            val (left, right) = if (a.toBigInt() <= b.toBigInt()) Pair(a, b) else Pair(b, a)
            return keccak256(left + right)
        }

        fun verify(proof: List<ByteArray>, root: ByteArray, leaf: ByteArray): Boolean {
            var hash = leaf
            for (sibling in proof) {
                hash = hashPair(hash, sibling)
            }
            return hash.contentEquals(root)
        }

        private fun keccak256(data: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("KECCAK-256", "BC")
            return digest.digest(data)
        }

        private fun ByteArray.toBigInt(): BigInteger = BigInteger(1, this)
    }
}
