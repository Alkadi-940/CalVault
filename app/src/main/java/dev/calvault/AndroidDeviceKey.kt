package dev.calvault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.calvault.core.DeviceKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidDeviceKey(private val alias: String = "calvault.device.v1") : DeviceKey {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        val store = store()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Device key unavailable. Use your recovery key." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun seal(plaintext: ByteArray, context: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try { cipher.init(Cipher.ENCRYPT_MODE, key(true)) }
        catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // Only seal is allowed to replace a device key, after setup or recovery authentication.
            store().deleteEntry(alias)
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
        }
        cipher.updateAAD(context)
        return cipher.iv + cipher.doFinal(plaintext)
    }
    override fun open(ciphertext: ByteArray, context: ByteArray): ByteArray {
        require(ciphertext.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        cipher.updateAAD(context)
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }
}
