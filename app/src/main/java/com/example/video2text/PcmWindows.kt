package com.example.video2text

internal const val PCM_RATE = 16_000
internal const val PCM_WINDOW = 30 * PCM_RATE
internal const val PCM_HALO = PCM_RATE

/** Disjoint 30-second cores with one second of detector context on either side.
 * The consumer blocks decoding, so neither memory nor queued PCM grows with duration.
 */
internal class PcmWindows(private val consume: (start: Int, coreStart: Int, coreEnd: Int, samples: FloatArray) -> Unit) {
    private val buffer = FloatArray(PCM_WINDOW + 2 * PCM_HALO)
    private var size = 0
    private var start = 0
    private var coreStart = 0
    private var finished = false

    fun push(sample: Float) {
        check(!finished)
        buffer[size++] = sample
        if (start.toLong() + size == coreStart.toLong() + PCM_WINDOW + PCM_HALO) {
            emit(coreStart + PCM_WINDOW)
        }
    }

    fun finish() {
        check(!finished)
        val end = start + size
        // EOF may arrive between the core boundary and its full right halo.
        if (end.toLong() > coreStart.toLong() + PCM_WINDOW) emit(coreStart + PCM_WINDOW)
        if (end > coreStart) consume(start, coreStart, end, buffer.copyOf(size))
        finished = true
    }

    private fun emit(end: Int) {
        consume(start, coreStart, end, buffer.copyOf(size))
        val nextStart = end - PCM_HALO
        val retained = start + size - nextStart
        buffer.copyInto(buffer, 0, nextStart - start, size)
        size = retained
        start = nextStart
        coreStart = end
    }
}
