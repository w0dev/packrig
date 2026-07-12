package net.packset.app.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypt/decrypt seam so the QRZ controller is JVM-testable with a fake. */
interface QrzKeyCipher {
    /** Base64(iv + ciphertext), or null if the Keystore is unavailable. */
    fun encrypt(plaintext: String): String?

    /** Plaintext, or null on any failure (missing key, restored backup, garbage). */
    fun decrypt(encoded: String): String?
}

/**
 * AES-256-GCM under an AndroidKeyStore key (alias "qrz_api_key"). The key is
 * non-exportable and never leaves the device, so ciphertext restored from a
 * cloud backup onto another device simply decrypts to null — the feature
 * degrades to "no key configured" instead of leaking the API key.
 */
object KeystoreCipher : QrzKeyCipher {

    private const val ALIAS = "qrz_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    override fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(encoded: String): String? = runCatching {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size > GCM_IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            obtainKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES),
        )
        String(cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
