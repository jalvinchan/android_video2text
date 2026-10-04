package com.example.video2text

import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    @Test fun plainTextHasOnlySpeechAndPreservesPunctuation() {
        val transcript = Transcript("private-video.mp4", listOf(
            TranscriptSegment(0, 1_000, "  Hello, world!  "),
            TranscriptSegment(1_000, 2_000, "  "),
            TranscriptSegment(12_345, 13_000, "It's a test—yes."),
        ))
        assertEquals("Hello, world! It's a test—yes.", TranscriptFormatter.format(transcript, TranscriptFormat.PLAIN_TEXT))
        assertEquals("[00:00] Hello, world!\n[00:12] It's a test—yes.", TranscriptFormatter.format(transcript, TranscriptFormat.TIMESTAMPS))
    }

    @Test fun bothFormatsContainExactlyTheSameSpeech() {
        val transcript = Transcript("video", listOf(TranscriptSegment(23, 1_234, "One!"), TranscriptSegment(1_300, 2_000, "Two?")))
        val timestamped = TranscriptFormatter.format(transcript, TranscriptFormat.TIMESTAMPS)
            .lineSequence().map { it.substringAfter("] ") }.joinToString(" ")
        assertEquals(TranscriptFormatter.format(transcript, TranscriptFormat.PLAIN_TEXT), timestamped)
    }

    @Test fun hourBoundaryUsesHoursForEveryLine() {
        val transcript = Transcript("video", listOf(
            TranscriptSegment(59_999, 60_000, "Before."),
            TranscriptSegment(3_599_999, 3_600_000, "Boundary."),
            TranscriptSegment(3_600_000, 3_602_000, "After."),
        ))
        assertEquals("[00:00:59] Before.\n[00:59:59] Boundary.\n[01:00:00] After.", TranscriptFormatter.format(transcript, TranscriptFormat.TIMESTAMPS))
    }

    @Test fun emptyTranscriptAndBlankSegmentsExportEmptyText() {
        for (format in TranscriptFormat.entries) {
            assertEquals("", TranscriptFormatter.format(Transcript("video", emptyList()), format))
            assertEquals("", TranscriptFormatter.format(Transcript("video", listOf(TranscriptSegment(0, 1, "\n "))), format))
        }
    }

    @Test fun fileNamesCannotEscapeTheExportDirectory() {
        val name = TranscriptFormatter.fileName(Transcript("../../my:video.mp4", emptyList()), TranscriptFormat.TIMESTAMPS)
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertTrue(name.endsWith("-timestamps.txt"))
    }

    @Test fun searchIsCaseInsensitiveAndDoesNotAlterTheTranscript() {
        val text = "Hello, hello! Goodbye."
        assertEquals(listOf(0..4, 7..11), searchMatches(text, "HELLO"))
        assertEquals(emptyList<IntRange>(), searchMatches(text, ""))
        assertEquals("Hello, hello! Goodbye.", text)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidSegmentTimesAreRejected() {
        TranscriptSegment(2, 1, "Invalid")
    }

    @Test fun cancellationBeforeCommitStopsReplacement() {
        val cancellation = ProcessingCancellation()
        cancellation.cancel()
        var replaced = false
        try { cancellation.commit { replaced = true }; fail("Commit should be cancelled") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(replaced)
    }

    @Test fun cancellationAfterCommitDoesNotUndoSuccess() {
        val cancellation = ProcessingCancellation()
        var replaced = false
        cancellation.commit { replaced = true }
        assertTrue(replaced)
        assertFalse(cancellation.cancel())
        assertFalse(cancellation.isCancelled())
    }

    @Test fun cancellationClosesActiveProviderResources() {
        val cancellation = ProcessingCancellation()
        var closed = 0
        cancellation.withCancellableResource({ closed++ }) { cancellation.cancel(); cancellation.cancel() }
        assertEquals(1, closed)
    }

    @Test fun finishedResourcesAreUnregistered() {
        val cancellation = ProcessingCancellation()
        var closed = false
        cancellation.withCancellableResource({ closed = true }) { }
        cancellation.cancel()
        assertFalse(closed)
    }
}
