package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GoogleVerifierTest {
    @Test
    fun sha256HexMatchesServerVector() {
        // Same digest the server uses for verifier "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GoogleVerifier.sha256Hex("abc"),
        )
    }

    @Test
    fun sha256HexEmptyAndLongerInputs() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            GoogleVerifier.sha256Hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GoogleVerifier.sha256Hex("abc"),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            GoogleVerifier.sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun sha256HexIsCaseSensitive() {
        assertNotEquals(GoogleVerifier.sha256Hex("abc"), GoogleVerifier.sha256Hex("ABC"))
    }

    @Test
    fun generateIs64LowercaseHex() {
        val verifier = GoogleVerifier.generate()
        assertEquals(GoogleVerifier.HEX_LEN, verifier.length)
        assertTrue(verifier.all { it in '0'..'9' || it in 'a'..'f' })
        val hash = GoogleVerifier.sha256Hex(verifier)
        assertTrue(GoogleVerifier.isValidHash(hash))
        assertEquals(hash, hash.lowercase())
    }

    @Test
    fun isValidHashRejectsBadLengthAndChars() {
        assertFalse(GoogleVerifier.isValidHash("abc"))
        assertFalse(GoogleVerifier.isValidHash("z".repeat(64)))
        assertTrue(
            GoogleVerifier.isValidHash("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"),
        )
    }
}
