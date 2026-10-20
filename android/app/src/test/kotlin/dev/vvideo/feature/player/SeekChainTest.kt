package dev.vvideo.feature.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SeekChainTest {
    @Test
    fun tapsInOneDirectionWithinTheWindowAddUp() {
        val chain = SeekChain()
        assertEquals(10_000L, chain.tap(forward = true, nowMs = 10_000))
        assertEquals(10_000L, chain.tap(forward = true, nowMs = 10_000 + SEEK_CHAIN_MS))
        assertEquals(20, chain.seconds)
        assertEquals(10_000L + SEEK_CHAIN_MS, chain.lastTapMs)
    }

    @Test
    fun aLateTapOrAnotherDirectionStartsANewChain() {
        val chain = SeekChain()
        chain.tap(forward = true, nowMs = 10_000)
        chain.tap(forward = true, nowMs = 10_001 + SEEK_CHAIN_MS)
        assertEquals(SEEK_STEP_SECONDS, chain.seconds)
        assertEquals(-10_000L, chain.tap(forward = false, nowMs = 10_002 + SEEK_CHAIN_MS))
        assertEquals(SEEK_STEP_SECONDS, chain.seconds)
        assertFalse(chain.forward)
    }

    @Test
    fun expiryAndResetHideTheFigure() = runBlocking {
        val chain = SeekChain()
        chain.tap(forward = false, nowMs = 10_000)
        chain.expire()
        assertEquals(0, chain.seconds)
        chain.tap(forward = false, nowMs = 20_000)
        chain.reset()
        assertEquals(0, chain.seconds)
    }
}
