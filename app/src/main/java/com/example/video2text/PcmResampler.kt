package com.example.video2text

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/** Streaming windowed-sinc conversion. Memory is constant, independent of video duration. */
internal class PcmResampler(private val inputRate: Int, private val emit: (Float) -> Unit) {
    init { require(inputRate in 8_000..192_000) { "Unsupported audio sample rate: $inputRate Hz." } }

    private val ratio = minOf(1.0, 16_000.0 / inputRate)
    private val radius = ceil(16 / ratio).toInt()
    private val history = FloatArray(radius * 2 + 4)
    private val coefficients = Array(256) { phase ->
        val fraction = phase / 256.0
        val taps = DoubleArray(radius * 2 + 1) { tap ->
            val x = tap - radius - fraction
            val sinc = if (kotlin.math.abs(x) < 1e-9) ratio else sin(PI * ratio * x) / (PI * x)
            val window = if (kotlin.math.abs(x) <= radius) 0.5 + 0.5 * cos(PI * x / radius) else 0.0
            sinc * window
        }
        val sum = taps.sum()
        FloatArray(taps.size) { (taps[it] / sum).toFloat() }
    }
    private var inputFrames = 0L
    private var outputFrames = 0L
    private var finished = false

    fun push(sample: Float) {
        check(!finished)
        history[(inputFrames % history.size).toInt()] = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
        inputFrames++
        while (floor(outputFrames * inputRate / 16_000.0).toLong() + radius < inputFrames) output()
    }

    fun finish() {
        check(!finished)
        val count = (inputFrames * 16_000.0 / inputRate).roundToLong()
        while (outputFrames < count) output()
        finished = true
    }

    private fun output() {
        val position = outputFrames * inputRate / 16_000.0
        val center = floor(position).toLong()
        val phase = ((position - center) * 256).toInt().coerceIn(0, 255)
        val taps = coefficients[phase]
        var sample = 0f
        for (tap in taps.indices) {
            val index = center + tap - radius
            if (index in maxOf(0, inputFrames - history.size) until inputFrames) {
                sample += history[(index % history.size).toInt()] * taps[tap]
            }
        }
        emit(sample.coerceIn(-1f, 1f))
        outputFrames++
    }
}
