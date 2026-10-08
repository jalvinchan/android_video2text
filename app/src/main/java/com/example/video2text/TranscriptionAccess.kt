package com.example.video2text

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

/** Snapshot at Start: changing purchase status must not interrupt an active job. */
internal data class TranscriptionAccess(val maximumDurationMs: Long? = null, val backgroundAllowed: Boolean = true) {
    fun checkDuration(durationMs: Long?) {
        if (maximumDurationMs != null && durationMs != null && durationMs > maximumDurationMs) {
            throw ProcessingException("Free transcription supports recordings up to 5 minutes. Upgrade to Pro for longer recordings. Your imported file and saved results have been kept.")
        }
    }

    fun checkSamples(samples: Long, offsetMs: Long) {
        // Compare samples directly so sub-millisecond overflow cannot bypass the limit.
        if (maximumDurationMs != null &&
            (offsetMs > maximumDurationMs || samples > (maximumDurationMs - offsetMs) * PCM_RATE / 1000)) {
            checkDuration(maximumDurationMs + 1)
        }
    }

    suspend fun monitorForeground(visible: StateFlow<Boolean>, pause: () -> Unit) {
        if (backgroundAllowed) return
        visible.collect { if (!it) pause() }
    }

    companion object {
        const val FREE_DURATION_MS = 5 * 60 * 1000L
        const val BACKGROUND_PAUSE_MESSAGE = "Free transcription pauses when you leave the app or lock the phone. Saved progress is kept. Return and tap Resume, or upgrade to Pro for background transcription."
        fun forPlan(enabled: Boolean, pro: Boolean): TranscriptionAccess {
            val free = enabled && !pro
            return TranscriptionAccess(if (free) FREE_DURATION_MS else null, backgroundAllowed = !free)
        }
    }
}
