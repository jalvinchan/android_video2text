package com.example.video2text

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class PcmConversionTest {
    @Test fun commonSampleRatesKeepDurationAndAmplitude() {
        for (rate in listOf(8_000, 16_000, 22_050, 44_100, 48_000, 96_000, 192_000)) {
            val output = mutableListOf<Float>()
            val resampler = PcmResampler(rate, output::add)
            repeat(rate / 10) { resampler.push(0.25f) }
            resampler.finish()
            assertEquals("rate=$rate", 1_600, output.size)
            assertTrue("rate=$rate", output.subList(100, 1_500).all { abs(it - 0.25f) < 0.002f })
        }
    }

    @Test fun downsamplingRejectsAudioAboveTheOutputNyquistFrequency() {
        val output = mutableListOf<Float>()
        val resampler = PcmResampler(48_000, output::add)
        repeat(4_800) { resampler.push(sin(2 * PI * 12_000 * it / 48_000).toFloat()) }
        resampler.finish()
        val stable = output.subList(100, 1_500)
        val rms = sqrt(stable.sumOf { it.toDouble() * it } / stable.size)
        assertTrue("Aliased energy: $rms", rms < 0.01)
    }

    @Test fun stereoDownmixAndChunkBoundariesMatchMono() {
        val stereo = mutableListOf<Float>()
        val conversion = PcmConversion(48_000, 2, PcmEncoding.INT16, {}, stereo::add)
        repeat(10) { chunk ->
            val buffer = ByteBuffer.allocate(480 * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(480) { buffer.putShort(16_384); buffer.putShort(0) }
            buffer.flip()
            conversion.accept(buffer, 2_000_000L + chunk * 10_000)
        }
        conversion.finish()
        val mono = mutableListOf<Float>()
        val resampler = PcmResampler(48_000, mono::add)
        repeat(4_800) { resampler.push(0.25f) }
        resampler.finish()
        assertEquals(2_000_000L, conversion.firstPresentationTimeUs)
        assertEquals(mono, stereo)
    }

    @Test fun presentationTimeGapsAndOverlapsPreserveVideoTimeline() {
        val output = mutableListOf<Float>()
        val conversion = PcmConversion(16_000, 1, PcmEncoding.FLOAT32, {}, output::add)
        fun chunk(time: Long, frames: Int, amplitude: Float) {
            val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { buffer.putFloat(amplitude) }; buffer.flip()
            conversion.accept(buffer, time)
        }
        chunk(5_000_000, 160, 0.5f)
        chunk(5_020_000, 160, 0.25f) // 10 ms gap.
        chunk(5_025_000, 160, 0.25f) // 5 ms overlap.
        conversion.finish()
        assertEquals(5_000_000L, conversion.firstPresentationTimeUs)
        assertEquals(560, output.size)
        assertTrue(output.subList(190, 290).all { abs(it) < 0.001f })
        assertTrue(output.subList(350, 450).all { abs(it - 0.25f) < 0.001f })
    }

    @Test fun extendedAudioStreamsWithoutAccumulatingSamples() {
        var count = 0L
        val resampler = PcmResampler(48_000) { count++ }
        repeat(48_000 * 5) { resampler.push(0f) }
        resampler.finish()
        assertEquals(80_000L, count)
    }

    @Test(expected = IllegalArgumentException::class) fun partialSampleFramesAreRejected() {
        PcmConversion(16_000, 2, PcmEncoding.INT16, {}, {}).accept(ByteBuffer.allocate(3), 0)
    }

    @Test(expected = IllegalArgumentException::class) fun hostileTimestampsAreRejected() {
        val conversion = PcmConversion(16_000, 1, PcmEncoding.INT16, {}, {})
        conversion.accept(ByteBuffer.allocate(2), 0)
        conversion.accept(ByteBuffer.allocate(2), Long.MAX_VALUE / 2)
    }
}
