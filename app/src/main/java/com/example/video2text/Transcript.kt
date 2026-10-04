package com.example.video2text

import java.util.Locale

data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String) {
    init {
        require(startMs >= 0 && endMs >= startMs)
    }
}

data class Transcript(val sourceName: String, val segments: List<TranscriptSegment>, val jobId: String? = null)

enum class TranscriptFormat { PLAIN_TEXT, TIMESTAMPS }

object TranscriptFormatter {
    fun format(transcript: Transcript, format: TranscriptFormat): String {
        val segments = transcript.segments.filter { it.text.isNotBlank() }
        if (format == TranscriptFormat.PLAIN_TEXT) {
            return segments.joinToString(" ") { it.text.trim() }
        }
        // Use one width throughout a transcript, including the minutes before the first hour.
        val hours = transcript.segments.any { it.endMs >= 3_600_000 }
        return segments.joinToString("\n") {
            "[${timestamp(it.startMs, hours)}] ${it.text.trim()}"
        }
    }

    private fun timestamp(ms: Long, hours: Boolean): String {
        val seconds = ms / 1_000
        return if (hours) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
    }

    fun fileName(transcript: Transcript, format: TranscriptFormat): String {
        val base = transcript.sourceName.substringBeforeLast('.', transcript.sourceName)
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().take(60).ifBlank { "transcript" }
        val suffix = if (format == TranscriptFormat.TIMESTAMPS) "-timestamps" else ""
        return "$base$suffix.txt"
    }
}
