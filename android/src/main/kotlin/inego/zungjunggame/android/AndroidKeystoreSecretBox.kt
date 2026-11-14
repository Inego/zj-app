package inego.zungjunggame.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import inego.zungjunggame.state.SecretBox
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android [SecretBox]: AES-256-GCM whose key lives in the Android Keystore.
 *
 * [create] always returns a box, even when the Keystore is unavailable: that box
 * fails every `seal`, so the state store drops the write and a secret value stays
 * memory-only rather than becoming plaintext.
 * Returning null here would instead mean "this platform has no secret storage".
 */
class AndroidKeystoreSecretBox private constructor(private val box: AndroidKeystoreBox?) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray? = box?.seal(plain)

    override fun open(sealed: ByteArray): ByteArray? = box?.open(sealed)

    companion object {
        private const val TAG = "ZjSecretBox"

        /** Java-friendly entry point for the Android launcher. */
        @JvmStatic
        fun create(): AndroidKeystoreSecretBox {
            val box = AndroidKeystoreBox.create()
            if (box == null) {
                Log.w(TAG, "Keystore unavailable; secrets will not persist")
            }
            return AndroidKeystoreSecretBox(box)
        }
    }
}

/**
 * AES-256-GCM box backed by an Android Keystore secret key. Returns null from
 * [create] when the platform cannot persist securely (Keystore error). Never
 * logs plaintext or ciphertext.
 */
internal class AndroidKeystoreBox private constructor(private val key: SecretKey) {
    fun seal(plain: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        val out = ByteArray(2 + iv.size + ct.size)
        out[0] = VERSION
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(ct, 0, out, 2 + iv.size, ct.size)
        out
    } catch (t: Throwable) {
        Log.w(TAG, "seal: ${t.javaClass.simpleName}")
        null
    }

    fun open(sealed: ByteArray): ByteArray? = try {
        if (sealed.size < 2 || sealed[0] != VERSION) return null
        val ivLen = sealed[1].toInt() and 0xFF
        if (ivLen < 12 || sealed.size < 2 + ivLen + GCM_TAG_BYTES) return null
        val iv = sealed.copyOfRange(2, 2 + ivLen)
        val ct = sealed.copyOfRange(2 + ivLen, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ct)
    } catch (t: Throwable) {
        Log.w(TAG, "open: ${t.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "ZjSecretBox"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "inego.zungjunggame.auth.refresh"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16

        fun create(): AndroidKeystoreBox? {
            return try {
                AndroidKeystoreBox(getOrCreateKey())
            } catch (t: Throwable) {
                Log.w(TAG, "create: ${t.javaClass.simpleName}")
                null
            }
        }

        private fun getOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(ALIAS, null)
            if (existing is KeyStore.SecretKeyEntry) return existing.secretKey
            if (existing != null) ks.deleteEntry(ALIAS)
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }
    }
}
