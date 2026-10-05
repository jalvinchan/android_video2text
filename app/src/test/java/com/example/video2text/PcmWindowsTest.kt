package com.example.video2text

import org.junit.Assert.*
import org.junit.Test

class PcmWindowsTest {
    @Test fun windowsCoverEverySampleOnceAndKeepDetectorContextBounded() {
        for (total in listOf(0, 1, PCM_WINDOW - 1, PCM_WINDOW, PCM_WINDOW + 1, PCM_WINDOW + PCM_HALO - 1, PCM_WINDOW + PCM_HALO, 2 * PCM_WINDOW, 2 * PCM_WINDOW + PCM_HALO + 7)) {
            var covered = 0
            val windows = PcmWindows { start, coreStart, coreEnd, samples ->
                assertEquals(covered, coreStart)
                assertEquals(maxOf(0, coreStart - PCM_HALO), start)
                assertEquals(minOf(total, coreStart + PCM_WINDOW), coreEnd)
                assertEquals(minOf(total, coreEnd + PCM_HALO), start + samples.size)
                assertTrue(samples.size <= PCM_WINDOW + 2 * PCM_HALO)
                for (index in samples.indices) assertEquals((start + index).toFloat(), samples[index], 0f)
                covered = coreEnd
            }
            repeat(total) { windows.push(it.toFloat()) }
            windows.finish()
            assertEquals(total, covered)
        }
    }

    @Test fun consumerRunsBeforeEndOfInputAndAppliesBackpressure() {
        var fed = 0
        var consumed = 0
        val windows = PcmWindows { _, _, _, _ ->
            consumed++
            // No future samples have been accepted when the consumer runs.
            assertEquals(PCM_WINDOW + PCM_HALO, fed)
        }
        repeat(PCM_WINDOW + PCM_HALO) { fed++; windows.push(0f) }
        assertEquals(1, consumed)
    }
}
