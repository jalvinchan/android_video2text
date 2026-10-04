package com.example.video2text

import org.junit.Assert.*
import org.junit.Test

class TranscriptionOptionsTest {
    @Test fun defaultsAndCompatibilityAreIndependentOfLanguageDownloads() {
        assertEquals(TranscriptionOptions(SpeechLanguage.AUTO, SpeechModel.BASE), TranscriptionOptions())
        for (model in SpeechModel.entries) for (language in SpeechLanguage.entries) {
            val options = TranscriptionOptions(language, model)
            if (language == SpeechLanguage.CANTONESE && model != SpeechModel.TURBO) {
                try { options.validate(); fail("Cantonese accepted for $model") }
                catch (_: IllegalArgumentException) { }
            } else options.validate()
        }
    }

    @Test fun aJobSnapshotIsUnaffectedByLaterSelections() {
        val saved = TranscriptionOptions(SpeechLanguage.MANDARIN, SpeechModel.SMALL)
        val job = saved.copy()
        val changed = saved.copy(language = SpeechLanguage.ENGLISH, model = SpeechModel.BASE)
        assertEquals(SpeechLanguage.MANDARIN, job.language)
        assertEquals(SpeechModel.SMALL, job.model)
        assertNotEquals(job, changed)
    }

    @Test fun chineseCharactersRemainUnchangedInBothFormatsAndSearch() {
        val phrase = "普通话，廣東話。你好！"
        val transcript = Transcript("中文.mp4", listOf(TranscriptSegment(1234, 5678, phrase)))
        assertEquals(phrase, TranscriptFormatter.format(transcript, TranscriptFormat.PLAIN_TEXT))
        assertEquals("[00:01] $phrase", TranscriptFormatter.format(transcript, TranscriptFormat.TIMESTAMPS))
        assertEquals(listOf(4..6), searchMatches(phrase, "廣東話"))
    }
}
