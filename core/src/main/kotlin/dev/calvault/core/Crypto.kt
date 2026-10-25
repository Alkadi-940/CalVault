package dev.calvault.core

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface DeviceKey {
    fun seal(plaintext: ByteArray, context: ByteArray): ByteArray
    fun open(ciphertext: ByteArray, context: ByteArray): ByteArray
}

object Crypto {
    private val random = SecureRandom()
    fun random(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun context(vararg parts: String) = parts.joinToString("|").toByteArray(Charsets.UTF_8)

    fun seal(key: ByteArray, bytes: ByteArray, aad: ByteArray): ByteArray {
        val nonce = random(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(bytes)
    }

    fun open(key: ByteArray, bytes: ByteArray, aad: ByteArray): ByteArray {
        require(bytes.size >= 28) { "Invalid encrypted record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    /** HKDF-SHA256, a single 32-byte output block with explicit purpose separation. */
    fun derive(key: ByteArray, salt: ByteArray, purpose: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(key)
        return try {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.doFinal(context("CalVault-v1", purpose) + byteArrayOf(1))
        } finally { prk.fill(0) }
    }

    fun validateNewPin(pin: CharArray) {
        require(pin.size in 3..9 && pin.all { it in '0'..'9' }) { "Use 3–9 digits" }
    }

    fun pinKey(pin: CharArray, salt: ByteArray): ByteArray {
        // Derivation still accepts older 10–12 digit PINs; new envelopes enforce the current policy.
        require(pin.size in 3..12 && pin.all { it in '0'..'9' }) { "Enter a valid numeric PIN" }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(65536).withIterations(3).withParallelism(4).withSalt(salt).build()
        return try {
            val generator = Argon2BytesGenerator()
            generator.init(parameters)
            ByteArray(32).also { generator.generateBytes(pin, it) }
        } finally { parameters.clear() }
    }

    fun recoverySecret(): String {
        val bytes = random(32)
        return try {
            val raw = (bytes + digest(bytes).copyOf(4)).joinToString("") { "%02X".format(it) }
            raw.chunked(8).joinToString("-")
        } finally { bytes.fill(0) }
    }

    fun parseRecovery(text: String): ByteArray {
        val normalized = text.filterNot { it == '-' || it.isWhitespace() }
        require(normalized.length == 72 && normalized.all { it in "0123456789abcdefABCDEF" }) { "Check your recovery key" }
        val decoded = ByteArray(36) { normalized.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val secret = decoded.copyOf(32)
        val valid = MessageDigest.isEqual(digest(secret).copyOf(4), decoded.copyOfRange(32, 36))
        decoded.fill(0)
        if (!valid) { secret.fill(0); error("Recovery key checksum does not match") }
        return secret
    }
}

/** A maintained authenticated streaming primitive, also providing authenticated random access. */
class StreamCipher(key: ByteArray) {
    private val primitive = AesGcmHkdfStreaming(key, "HmacSha256", 32, 1 shl 20, 0)
    fun encrypt(output: OutputStream, context: ByteArray): OutputStream = primitive.newEncryptingStream(output, context)
    fun decrypt(input: InputStream, context: ByteArray): InputStream = primitive.newDecryptingStream(input, context)
    fun seekable(input: SeekableByteChannel, context: ByteArray): SeekableByteChannel =
        primitive.newSeekableDecryptingChannel(input, context)
}
