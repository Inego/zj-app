package inego.zungjunggame.net

import inego.zungjunggame.platform.newRandom

/**
 * Client-side Google sign-in verifier.
 *
 * The verifier never leaves the device except in [AuthApi.googleExchange].
 * [AuthApi.googleChallenge] sends only [sha256Hex] of it. Matches the server's
 * SHA-256 of the verifier's UTF-8 bytes, rendered as lowercase hex.
 */
object GoogleVerifier {
    const val BYTE_LEN = 32
    const val HEX_LEN = 64

    /** 32 random bytes as lowercase hex. TeaVM-safe PRNG (see [newRandom]). */
    fun generate(): String {
        val bytes = ByteArray(BYTE_LEN)
        newRandom().nextBytes(bytes)
        return toHex(bytes)
    }

    fun sha256Hex(value: String): String = toHex(sha256(value.encodeToByteArray()))

    fun isValidHash(hex: String): Boolean =
        hex.length == HEX_LEN && hex.all { it in HEX_DIGITS }
}

private val HEX_DIGITS = "0123456789abcdefABCDEF".toSet()

private val SHA256_K = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)

private fun sha256(message: ByteArray): ByteArray {
    val h = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19.toInt(),
    )
    val padded = padSha256(message)
    val w = IntArray(64)
    var offset = 0
    while (offset < padded.size) {
        for (i in 0 until 16) {
            val j = offset + i * 4
            w[i] = (padded[j].toInt() and 0xff shl 24) or
                (padded[j + 1].toInt() and 0xff shl 16) or
                (padded[j + 2].toInt() and 0xff shl 8) or
                (padded[j + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
            val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var h7 = h[7]
        for (i in 0 until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h7 + s1 + ch + SHA256_K[i] + w[i]
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            h7 = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += h7
        offset += 64
    }
    val out = ByteArray(32)
    for (i in 0 until 8) {
        val v = h[i]
        out[i * 4] = (v ushr 24).toByte()
        out[i * 4 + 1] = (v ushr 16).toByte()
        out[i * 4 + 2] = (v ushr 8).toByte()
        out[i * 4 + 3] = v.toByte()
    }
    return out
}

private fun padSha256(message: ByteArray): ByteArray {
    val bitLen = message.size.toLong() shl 3
    val withOne = message.size + 1
    var paddedLen = withOne + 8
    val rem = paddedLen % 64
    if (rem != 0) paddedLen += 64 - rem
    val padded = ByteArray(paddedLen)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[paddedLen - 1 - i] = (bitLen ushr (8 * i)).toByte()
    }
    return padded
}

private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) {
        val v = b.toInt() and 0xff
        append("0123456789abcdef"[v ushr 4])
        append("0123456789abcdef"[v and 0x0f])
    }
}
