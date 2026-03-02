package com.minefi.relay

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class KeyPairData(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

object Crypto {

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generateKeyPair(): KeyPairData {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val kp = kpg.generateKeyPair()
        val pub = kp.public.encoded.takeLast(32).toByteArray()
        val priv = kp.private.encoded
        return KeyPairData(privateKey = priv, publicKey = pub)
    }

    fun deriveSharedSecret(privateKeyEncoded: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519")
        val privKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKeyEncoded))
        val pubKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(wrapX25519PublicKey(peerPublicKey)))
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privKey)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    fun deriveSymmetricKey(privateKeyEncoded: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val shared = deriveSharedSecret(privateKeyEncoded, peerPublicKey)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, null, null))
        val result = ByteArray(32)
        hkdf.generateBytes(result, 0, 32)
        return result
    }

    fun deriveTopicFromKey(symmetricKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(symmetricKey).joinToString("") { "%02x".format(it) }
    }

    fun generateSymmetricKey(): ByteArray {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return key
    }

    fun generateTopic(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun encrypt(symmetricKey: ByteArray, plaintext: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(symmetricKey, "ChaCha20"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray())
        val envelope = byteArrayOf(0x00) + iv + ciphertext
        return Base64.getEncoder().encodeToString(envelope)
    }

    fun decrypt(symmetricKey: ByteArray, encoded: String): String {
        val envelope = Base64.getDecoder().decode(encoded)
        val iv = envelope.copyOfRange(1, 13)
        val ciphertext = envelope.copyOfRange(13, envelope.size)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(symmetricKey, "ChaCha20"), IvParameterSpec(iv))
        return String(cipher.doFinal(ciphertext))
    }

    private fun wrapX25519PublicKey(raw: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03,
            0x2b, 0x65, 0x6e,
            0x03, 0x21, 0x00
        )
        return prefix + raw
    }
}
