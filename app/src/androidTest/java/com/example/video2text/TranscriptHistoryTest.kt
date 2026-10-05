package com.example.video2text

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptHistoryTest {
    private fun withStore(action: (File, TranscriptStore) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            "history-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { action(root, TranscriptStore(root)) } finally { root.deleteRecursively() }
    }

    private fun transcript(name: String, jobId: String? = null) = Transcript(name,
        listOf(TranscriptSegment(123, 456, "Café 你好，廣東話。")), jobId)

    @Test fun historyReopensWithDatesAndIndividualDeletionAndDeduplicatesFinishedJobs() = withStore { root, store ->
        val first = transcript("same.wav", "job-one")
        val second = transcript("same.wav", "job-two")
        val firstEntry = store.save(first, ProcessingCancellation()).single()
        val saved = store.save(second, ProcessingCancellation())
        assertEquals(listOf(second, first), saved.map { it.transcript })
        assertTrue(saved.all { it.completedAtMs != null && it.completedAtMs!! > 0 })
        assertEquals(saved, TranscriptStore(root).loadHistory())
        assertEquals(saved, store.save(first, ProcessingCancellation()))
        assertEquals(listOf(saved.first()), store.delete(firstEntry.id))
        assertEquals(second, TranscriptStore(root).load())
        store.delete(saved.first().id)
        assertTrue(TranscriptStore(root).loadHistory().isEmpty())
    }

    @Test fun legacyWithAndWithoutJobIdMigratesOnlyAfterDurableCommit() {
        for (jobId in listOf(null, "legacy-job")) withStore { root, store ->
            val original = transcript("old.mp4", jobId)
            val legacy = File(root, "latest-transcript.json")
            val json = JSONObject().put("version", 1).put("sourceName", original.sourceName).put("jobId", jobId)
                .put("segments", org.json.JSONArray().put(JSONObject().put("startMs", 123).put("endMs", 456).put("text", original.segments.single().text)))
            legacy.writeText(json.toString())
            val savedAt = legacy.lastModified()
            val blocked = File(root, "transcript-history.json.new").apply { mkdirs() }
            File(blocked, "blocker").writeText("Prevent migration write")
            try { store.loadHistory(); fail("Migration write failure was ignored") } catch (_: java.io.IOException) { }
            assertTrue(legacy.isFile)
            assertEquals(json.toString(), legacy.readText())
            blocked.deleteRecursively()
            val migrated = store.loadHistory().single()
            assertEquals(original, migrated.transcript)
            assertEquals(savedAt, migrated.completedAtMs)
            assertFalse(legacy.exists())
            assertEquals(listOf(migrated), TranscriptStore(root).loadHistory())
            store.delete(migrated.id)
            assertNull(TranscriptStore(root).load())
        }
    }

    @Test fun cancellationFailedWritesAndInterruptedAtomicWriteKeepAllHistory() = withStore { root, store ->
        val saved = store.save(transcript("first.wav"), ProcessingCancellation())
        val bytes = File(root, "transcript-history.json").readBytes()
        try {
            store.save(transcript("cancelled.wav"), ProcessingCancellation().apply { cancel() })
            fail("Cancelled transcript saved")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        val atomic = AtomicFile(File(root, "transcript-history.json"))
        atomic.startWrite().use { it.write("partial".toByteArray()); it.fd.sync() }
        assertEquals(saved, TranscriptStore(root).loadHistory())
        val blocked = File(root, "transcript-history.json.new").apply { mkdirs() }
        File(blocked, "blocker").writeText("Prevent commit")
        val token = ProcessingCancellation()
        try { store.save(transcript("failed.wav"), token); fail("Failed write accepted") } catch (_: java.io.IOException) { }
        assertFalse(token.isCommitted())
        try { store.delete(saved.single().id); fail("Failed deletion accepted") } catch (_: java.io.IOException) { }
        assertArrayEquals(bytes, File(root, "transcript-history.json").readBytes())
        blocked.deleteRecursively()
        assertEquals(saved, TranscriptStore(root).loadHistory())
    }

    @Test fun corruptHistoryCannotBeOverwrittenByANewResult() = withStore { root, store ->
        val file = File(root, "transcript-history.json").apply { writeText("broken") }
        try { store.save(transcript("new.wav"), ProcessingCancellation()); fail("Corrupt history overwritten") }
        catch (_: org.json.JSONException) { }
        assertEquals("broken", file.readText())
    }

    @Test fun finishedJobCleanupRecognizesAnOlderHistoryEntry() = withStore { root, store ->
        val jobs = TranscriptionJobStore(root)
        jobs.directory.mkdirs()
        // Completion cleanup precedes media validation, even after the source was removed.
        File(jobs.directory, "job.json").writeText(JSONObject().put("id", "finished-job").toString())
        store.save(transcript("old.wav", "finished-job"), ProcessingCancellation())
        store.save(transcript("new.wav", "new-job"), ProcessingCancellation())
        assertNull(jobs.loadExcludingCompleted(store.loadHistory().mapNotNull { it.transcript.jobId }.toSet()))
        assertFalse(jobs.directory.exists())
        assertEquals(2, store.loadHistory().size)
    }
}
