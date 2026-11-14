package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccessJwtTest {
    @Test
    fun readsExpFromCompactJwt() {
        val token = unsignedAccessJwt(exp = 1_800_000_000L)
        assertEquals(1_800_000_000L, AccessJwt.expiresAtEpochSeconds(token))
    }

    @Test
    fun readsStringExp() {
        val header = Base64Url.encode("""{"alg":"none"}""".encodeToByteArray())
        val payload = Base64Url.encode("""{"exp":"1700000000"}""".encodeToByteArray())
        assertEquals(1_700_000_000L, AccessJwt.expiresAtEpochSeconds("$header.$payload.sig"))
    }

    @Test
    fun nonJwtReturnsNull() {
        assertNull(AccessJwt.expiresAtEpochSeconds("plain-token"))
        assertNull(AccessJwt.expiresAtEpochSeconds(""))
        assertNull(AccessJwt.expiresAtEpochSeconds("only.one"))
    }

    @Test
    fun missingExpReturnsNull() {
        val header = Base64Url.encode("""{"alg":"none"}""".encodeToByteArray())
        val payload = Base64Url.encode("""{"sub":"u"}""".encodeToByteArray())
        assertNull(AccessJwt.expiresAtEpochSeconds("$header.$payload.sig"))
    }

    @Test
    fun garbagePayloadReturnsNull() {
        assertNull(AccessJwt.expiresAtEpochSeconds("aaa.not-json.sig"))
    }
}

internal fun unsignedAccessJwt(exp: Long, iat: Long = 1L): String {
    val header = Base64Url.encode("""{"alg":"none","typ":"JWT"}""".encodeToByteArray())
    val payload = Base64Url.encode(
        """{"sub":"u","sid":"s","iss":"zj-server","aud":"zj-game","iat":$iat,"exp":$exp}"""
            .encodeToByteArray(),
    )
    return "$header.$payload.sig"
}
