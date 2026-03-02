package com.minefi.relay

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

object RelayAuth {

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generateAuthJwt(relayUrl: String): String {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)

        val privateKey = Ed25519PrivateKeyParameters(seed, 0)
        val publicKey = privateKey.generatePublicKey().encoded

        val iss = encodeDidKey(publicKey)
        val sub = Crypto.generateTopic()
        val now = System.currentTimeMillis() / 1000
        val exp = now + 86400

        val header = encoder.encodeToString("""{"alg":"EdDSA","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(
            """{"iss":"$iss","sub":"$sub","aud":"$relayUrl","iat":$now,"exp":$exp}""".toByteArray()
        )

        val dataToSign = "$header.$payload".toByteArray()
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(dataToSign, 0, dataToSign.size)
        val signature = signer.generateSignature()

        return "$header.$payload.${encoder.encodeToString(signature)}"
    }

    private fun encodeDidKey(publicKey: ByteArray): String {
        val multicodec = byteArrayOf(0xed.toByte(), 0x01) + publicKey
        val encoded = base58Btc(multicodec)
        return "did:key:z$encoded"
    }

    private fun base58Btc(data: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger(1, data)
        val sb = StringBuilder()
        val base = java.math.BigInteger.valueOf(58)
        while (num > java.math.BigInteger.ZERO) {
            val (div, rem) = num.divideAndRemainder(base)
            sb.append(alphabet[rem.toInt()])
            num = div
        }
        for (b in data) {
            if (b == 0.toByte()) sb.append('1') else break
        }
        return sb.reverse().toString()
    }
}
