package inego.zungjunggame.state

/**
 * Lowercase hex for the byte blobs a preferences-style container holds.
 *
 * `Preferences` stores strings, so every state blob is hex-encoded on the way
 * in and decoded on the way out. Both [PreferencesStateBackend] and the
 * browser's per-key `localStorage` backend use this one encoding, so the values
 * one wrote are the values the other reads — an existing session survives the
 * switch.
 */
object HexCodec {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val text = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            text.append(DIGITS[value ushr 4]).append(DIGITS[value and 0x0F])
        }
        return text.toString()
    }

    /** The bytes [text] spells, or null when it is not an even run of hex digits. */
    fun decode(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val bytes = ByteArray(text.length / 2)
        for (i in bytes.indices) {
            val hi = digit(text[i * 2])
            val lo = digit(text[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return bytes
    }

    private fun digit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }
}
