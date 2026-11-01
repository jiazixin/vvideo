package dev.vvideo.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenPolicyTest {
    @Test
    fun rejectsHeadersThatWouldCrashOkHttpBeforeTransport() {
        for (token in listOf("", " ", "line\nbreak", "\rheader", "\t", "令牌", "a".repeat(4097))) {
            assertFalse(TokenPolicy.valid(token))
            assertFalse(Connection("http://nas.local", token).configured)
        }
        assertTrue(TokenPolicy.valid("token.123~+/=_-"))
        assertTrue(TokenPolicy.valid("a".repeat(4096)))
    }
}
