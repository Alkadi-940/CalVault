package dev.calvault.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.*
import org.junit.Test

class CryptoTest {
    @Test fun authenticatedEncryptionRejectsWrongContextAndTampering() {
        val key = Crypto.random(32)
        val context = Crypto.context("vault", "object")
        val encrypted = Crypto.seal(key, "private".toByteArray(), context)
        assertEquals("private", String(Crypto.open(key, encrypted, context)))
        assertThrows(Exception::class.java) { Crypto.open(key, encrypted, Crypto.context("other")) }
        encrypted[15] = (encrypted[15].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { Crypto.open(key, encrypted, context) }
    }
    @Test fun recoveryRoundTripAndChecksum() {
        val secret = Crypto.recoverySecret()
        assertEquals(9, secret.split('-').size)
        assertEquals(32, Crypto.parseRecovery(secret.lowercase()).size)
        val damaged = (if (secret[0] == '0') "1" else "0") + secret.substring(1)
        assertThrows(Exception::class.java) { Crypto.parseRecovery(damaged) }
    }
    @Test fun argon2idRfc9106Vector() {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(19).withParallelism(4).withMemoryAsKB(32).withIterations(3)
            .withSalt(ByteArray(16) { 2 }).withSecret(ByteArray(8) { 3 }).withAdditional(ByteArray(12) { 4 }).build()
        val result = ByteArray(32)
        Argon2BytesGenerator().apply { init(params); generateBytes(ByteArray(32) { 1 }, result) }
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", result.joinToString("") { "%02x".format(it) })
    }
    @Test fun purposesDeriveIndependentKeys() {
        val key = Crypto.random(32)
        val salt = Crypto.random(16)
        assertFalse(Crypto.derive(key, salt, "metadata").contentEquals(Crypto.derive(key, salt, "backup")))
    }
}
