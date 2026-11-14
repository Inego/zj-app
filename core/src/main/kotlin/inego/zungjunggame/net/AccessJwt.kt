package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader

/**
 * Reads `exp` from a compact JWT without verifying the signature.
 *
 * Used only to schedule proactive refresh. Authorization is still the server's.
 * Non-JWT access tokens (test fixtures) return null and fall through to 401 refresh.
 */
object AccessJwt {
    fun expiresAtEpochSeconds(token: String): Long? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        val json = Base64Url.decodeToString(parts[1]) ?: return null
        return try {
            val root = JsonReader().parse(json) ?: return null
            val exp = root.get("exp") ?: return null
            when {
                exp.isNumber -> exp.asLong()
                exp.isString -> exp.asString().toLongOrNull()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal object Base64Url {
    fun decodeToString(input: String): String? {
        val bytes = decode(input) ?: return null
        return bytes.decodeToString()
    }

    fun decode(input: String): ByteArray? {
        if (input.isEmpty()) return null
        val out = ArrayList<Byte>(input.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in input) {
            if (c == '=') break
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-', '+' -> 62
                '_', '/' -> 63
                else -> return null
            }
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buf shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    fun encode(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            out.append(table[b0 shr 2])
            out.append(table[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) out.append(table[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) out.append(table[b2 and 63])
            i += 3
        }
        return out.toString()
    }
}
