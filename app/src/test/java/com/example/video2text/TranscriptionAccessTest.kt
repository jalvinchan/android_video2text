package com.example.video2text

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class TranscriptionAccessTest {
    @Test fun freeBoundaryIncludesExactlyFiveMinutesAndRejectsOneMoreSample() {
        val access = TranscriptionAccess.forPlan(enabled = true, pro = false)
        assertEquals(300_000L, access.maximumDurationMs)
        assertFalse(access.backgroundAllowed)
        access.checkDuration(TranscriptionAccess.FREE_DURATION_MS)
        access.checkDuration(null)
        val samples = TranscriptionAccess.FREE_DURATION_MS * PCM_RATE / 1000
        access.checkSamples(samples, 0)
        try { access.checkSamples(samples + 1, 0); fail("Extra sample accepted") }
        catch (error: ProcessingException) { assertTrue(error.message!!.contains("Upgrade to Pro")) }
        try { access.checkDuration(TranscriptionAccess.FREE_DURATION_MS + 1); fail("Long recording accepted") }
        catch (_: ProcessingException) { }
    }

    @Test fun timelineOffsetsCountAndProAndUnconfiguredBuildsAreUnrestricted() {
        val access = TranscriptionAccess.forPlan(enabled = true, pro = false)
        access.checkSamples(PCM_RATE.toLong(), TranscriptionAccess.FREE_DURATION_MS - 1000)
        try { access.checkSamples(PCM_RATE + 1L, TranscriptionAccess.FREE_DURATION_MS - 1000); fail("Offset bypassed limit") }
        catch (_: ProcessingException) { }
        for (unrestricted in listOf(TranscriptionAccess.forPlan(true, true), TranscriptionAccess.forPlan(false, false))) {
            assertNull(unrestricted.maximumDurationMs)
            assertTrue(unrestricted.backgroundAllowed)
            unrestricted.checkDuration(Long.MAX_VALUE)
            unrestricted.checkSamples(Int.MAX_VALUE.toLong(), 100_000)
        }
    }

    @Test fun hidingFreeWorkCancelsOnceAndReturningDoesNotUnpauseIt(): Unit = runBlocking {
        val visible = MutableStateFlow(true)
        val token = ProcessingCancellation()
        val monitor = launch { TranscriptionAccess.forPlan(true, false).monitorForeground(visible) { token.cancel() } }
        try {
            yield()
            assertFalse(token.isCancelled())
            visible.value = false
            yield()
            assertTrue(token.isCancelled())
            visible.value = true
            yield()
            assertTrue(token.isCancelled()) // Resume needs a new run; visibility cannot undo cancellation.
        } finally { monitor.cancelAndJoin() }
    }

    @Test fun initiallyHiddenFreeWorkPausesButProAndUnconfiguredWorkDoNot(): Unit = runBlocking {
        val hidden = MutableStateFlow(false)
        val token = ProcessingCancellation()
        val monitor = launch { TranscriptionAccess.forPlan(true, false).monitorForeground(hidden) { token.cancel() } }
        try { yield(); assertTrue(token.isCancelled()) } finally { monitor.cancelAndJoin() }
        for (access in listOf(TranscriptionAccess.forPlan(true, true), TranscriptionAccess.forPlan(false, false))) {
            access.monitorForeground(hidden) { fail("Unrestricted work paused") }
        }
    }
}
