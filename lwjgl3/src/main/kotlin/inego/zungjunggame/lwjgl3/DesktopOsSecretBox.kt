package inego.zungjunggame.lwjgl3

import inego.zungjunggame.state.SecretBox
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop [SecretBox]: AES-256-GCM whose wrapping key lives in the OS credential
 * store (Windows Credential Manager, macOS Keychain, Linux Secret Service).
 *
 * [create] always returns a box, even when the OS store is unavailable: that box
 * fails every `seal`, so the state store drops the write and a secret value stays
 * memory-only rather than becoming plaintext.
 * Returning null here would instead mean "this platform has no secret storage",
 * which is a different thing.
 */
internal class DesktopOsSecretBox(private val box: DesktopOsBox?) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray? = box?.seal(plain)

    override fun open(sealed: ByteArray): ByteArray? = box?.open(sealed)

    companion object {
        fun create(osBox: DesktopOsBox? = DesktopOsBox.create()): DesktopOsSecretBox {
            if (osBox == null) {
                System.err.println("ZjSecretBox: OS credential store unavailable; secrets will not persist")
            }
            return DesktopOsSecretBox(osBox)
        }
    }
}

/**
 * AES-256-GCM box whose wrapping key is kept in [OsSecretStore]. Returns null
 * from [create] when the platform cannot persist securely. Never logs plaintext
 * or ciphertext. Blob format: `version | ivLen | iv | ciphertext+tag`.
 */
internal class DesktopOsBox private constructor(private val key: SecretKey) {
    fun seal(plain: ByteArray): ByteArray? = try {
        val iv = ByteArray(GCM_IV_BYTES).also { RANDOM.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        val out = ByteArray(2 + iv.size + ct.size)
        out[0] = VERSION
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(ct, 0, out, 2 + iv.size, ct.size)
        out
    } catch (t: Throwable) {
        System.err.println("ZjSecretBox: seal: ${t.javaClass.simpleName}")
        null
    }

    fun open(sealed: ByteArray): ByteArray? = try {
        if (sealed.size < 2 || sealed[0] != VERSION) return null
        val ivLen = sealed[1].toInt() and 0xFF
        if (ivLen < GCM_IV_BYTES || sealed.size < 2 + ivLen + GCM_TAG_BYTES) return null
        val iv = sealed.copyOfRange(2, 2 + ivLen)
        val ct = sealed.copyOfRange(2 + ivLen, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ct)
    } catch (t: Throwable) {
        System.err.println("ZjSecretBox: open: ${t.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val KEY_BYTES = 32
        private val RANDOM = SecureRandom()

        fun create(secrets: OsSecretStore? = OsSecretStore.open()): DesktopOsBox? {
            if (secrets == null) return null
            return try {
                val existing = secrets.read()?.let { decodeKey(it) }
                val keyBytes = if (existing != null && existing.size == KEY_BYTES) {
                    existing
                } else {
                    val generated = ByteArray(KEY_BYTES).also { RANDOM.nextBytes(it) }
                    if (!secrets.write(encodeKey(generated))) return null
                    generated
                }
                DesktopOsBox(SecretKeySpec(keyBytes, "AES"))
            } catch (t: Throwable) {
                System.err.println("ZjSecretBox: create: ${t.javaClass.simpleName}")
                null
            }
        }

        private fun encodeKey(key: ByteArray): String = Base64.getEncoder().encodeToString(key)

        private fun decodeKey(value: String): ByteArray? = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
