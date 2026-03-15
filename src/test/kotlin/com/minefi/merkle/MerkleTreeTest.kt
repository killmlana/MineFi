package com.minefi.merkle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigInteger

class MerkleTreeTest {

    @Test
    fun `single leaf tree has root equal to double-hashed leaf`() {
        val leaf = BalanceLeaf("0xabc123", BigInteger.valueOf(1000))
        val tree = MerkleTree(listOf(leaf))
        val expected = MerkleTree.hashLeaf(leaf)
        assertArrayEquals(expected, tree.root)
    }

    @Test
    fun `two leaves produce deterministic root`() {
        val leaves = listOf(
            BalanceLeaf("0xaaa", BigInteger.valueOf(100)),
            BalanceLeaf("0xbbb", BigInteger.valueOf(200)),
        )
        val tree1 = MerkleTree(leaves)
        val tree2 = MerkleTree(leaves.reversed())
        assertArrayEquals(tree1.root, tree2.root)
    }

    @Test
    fun `proof verifies correctly`() {
        val leaves = listOf(
            BalanceLeaf("0xaaa", BigInteger.valueOf(100)),
            BalanceLeaf("0xbbb", BigInteger.valueOf(200)),
            BalanceLeaf("0xccc", BigInteger.valueOf(300)),
            BalanceLeaf("0xddd", BigInteger.valueOf(400)),
        )
        val tree = MerkleTree(leaves)
        for (leaf in leaves) {
            val proof = tree.getProof(leaf.address)
            assertNotNull(proof)
            val leafHash = MerkleTree.hashLeaf(leaf)
            assertTrue(MerkleTree.verify(proof!!, tree.root, leafHash))
        }
    }

    @Test
    fun `wrong balance fails verification`() {
        val leaves = listOf(
            BalanceLeaf("0xaaa", BigInteger.valueOf(100)),
            BalanceLeaf("0xbbb", BigInteger.valueOf(200)),
        )
        val tree = MerkleTree(leaves)
        val fakeLeaf = BalanceLeaf("0xaaa", BigInteger.valueOf(999))
        val proof = tree.getProof("0xaaa")!!
        val fakeHash = MerkleTree.hashLeaf(fakeLeaf)
        assertFalse(MerkleTree.verify(proof, tree.root, fakeHash))
    }

    @Test
    fun `unknown address returns null proof`() {
        val tree = MerkleTree(listOf(BalanceLeaf("0xaaa", BigInteger.ONE)))
        assertNull(tree.getProof("0xzzz"))
    }

    @Test
    fun `root is 32 bytes`() {
        val leaves = listOf(
            BalanceLeaf("0xaaa", BigInteger.valueOf(100)),
            BalanceLeaf("0xbbb", BigInteger.valueOf(200)),
        )
        val tree = MerkleTree(leaves)
        assertEquals(32, tree.root.size)
    }
}
