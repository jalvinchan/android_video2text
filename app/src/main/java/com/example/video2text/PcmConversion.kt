package com.example.video2text

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

internal enum class PcmEncoding(val bytes: Int) { INT8(1), INT16(2), INT24(3), INT32(4), FLOAT32(4) }

/** Maps decoder presentation times onto the PCM timeline, retaining gaps and dropping overlaps. */
internal class PcmConversion(
    private val rate: Int,
    private val channels: Int,
    private val encoding: PcmEncoding,
    private val checkCancelled: () -> Unit,
    emit: (Float) -> Unit,
) {
    init { require(channels in 1..8) { "Unsupported number of audio channels: $channels." } }
    private val resampler = PcmResampler(rate, emit)
    private var frames = 0L
    var firstPresentationTimeUs: Long? = null
        private set

    fun accept(buffer: ByteBuffer, presentationTimeUs: Long) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channels * encoding.bytes
        require(buffer.remaining() % frameBytes == 0) { "The decoder returned incomplete audio samples." }
        if (!buffer.hasRemaining()) return
        if (firstPresentationTimeUs == null) firstPresentationTimeUs = presentationTimeUs
        val expected = ((presentationTimeUs - firstPresentationTimeUs!!) * (rate / 1_000_000.0)).roundToLong()
        require(expected >= 0) { "The video contains invalid audio timestamps." }
        val difference = expected - frames
        if (difference > 2) {
            // Prevent hostile timestamps from creating a practically unbounded output file.
            require(expected * 16_000.0 / rate < Int.MAX_VALUE) { "This video is too long to transcribe." }
            repeatFrames(difference) { resampler.push(0f) }
        } else if (difference < -2) {
            val skip = minOf(-difference, (buffer.remaining() / frameBytes).toLong()).toInt()
            buffer.position(buffer.position() + skip * frameBytes)
        }
        var chunkFrames = 0
        while (buffer.remaining() >= frameBytes) {
            if (chunkFrames++ % 2_048 == 0) checkCancelled()
            var mono = 0f
            repeat(channels) { mono += sample(buffer) }
            resampler.push(mono / channels)
            frames++
        }
    }

    fun finish() = resampler.finish()

    private fun repeatFrames(count: Long, emit: () -> Unit) {
        var remaining = count
        while (remaining-- > 0) {
            if (remaining % 2_048 == 0L) checkCancelled()
            emit()
            frames++
        }
    }

    private fun sample(buffer: ByteBuffer): Float = when (encoding) {
        PcmEncoding.INT8 -> ((buffer.get().toInt() and 255) - 128) / 128f
        PcmEncoding.INT16 -> buffer.short / 32768f
        PcmEncoding.INT24 -> {
            val value = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
            value / 8388608f
        }
        PcmEncoding.INT32 -> buffer.int / 2147483648f
        PcmEncoding.FLOAT32 -> buffer.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
    }
}
