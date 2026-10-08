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

    @Test fun backupMigrationPreservesRecordsAndKeepsActivityDeviceLocal() = withStore { local, old ->
        old.save(transcript("first.wav", "job-one"), ProcessingCancellation())
        val saved = old.save(transcript("second.wav", "job-two"), ProcessingCancellation())
        old.markActive()
        val destination = File(local, "backup-eligible")
        val migrated = TranscriptStore(destination, local)
        assertEquals(saved, migrated.loadHistory())
        assertFalse(File(local, "transcript-history.json").exists())
        assertTrue(File(destination, "transcript-history.json").isFile)
        assertTrue(migrated.wasInterrupted())
        assertFalse(File(destination, "transcription-active").exists())

        // Simulate restoring only the allowed file to another installation.
        val restored = File(local, "restored").apply { mkdirs() }
        File(destination, "transcript-history.json").copyTo(File(restored, "transcript-history.json"))
        val newLocal = File(local, "new-phone-local").apply { mkdirs() }
        val reopened = TranscriptStore(restored, newLocal)
        assertEquals(saved, reopened.loadHistory())
        assertFalse(reopened.wasInterrupted())
        reopened.delete(saved.first().id)
        assertEquals(listOf(saved.last()), reopened.loadHistory())
    }

    @Test fun failedBackupMigrationKeepsSourceAndCanRetry() = withStore { local, old ->
        val saved = old.save(transcript("keep.wav", "keep-job"), ProcessingCancellation())
        val original = File(local, "transcript-history.json").readBytes()
        val destination = File(local, "backup-eligible").apply { mkdirs() }
        val blocked = File(destination, "transcript-history.json.new").apply { mkdirs() }
        File(blocked, "blocker").writeText("Prevent migration commit")
        try {
            TranscriptStore(destination, local).loadHistory()
            fail("Migration failure was ignored")
        } catch (_: java.io.IOException) { }
        assertArrayEquals(original, File(local, "transcript-history.json").readBytes())
        blocked.deleteRecursively()
        assertEquals(saved, TranscriptStore(destination, local).loadHistory())
        assertFalse(File(local, "transcript-history.json").exists())
    }

    @Test fun backupMigrationRetriesAfterCommitAndPreservesRestoredHistory() = withStore { local, old ->
        val previous = old.save(transcript("old.wav", "old-job"), ProcessingCancellation())
        val destination = File(local, "backup-eligible").apply { mkdirs() }
        File(local, "transcript-history.json").copyTo(File(destination, "transcript-history.json"))
        // Crash after destination commit but before source cleanup, followed by restore.
        val restored = TranscriptStore(destination)
        val combined = restored.save(transcript("restored.wav", "restored-job"), ProcessingCancellation())
        assertEquals(combined, TranscriptStore(destination, local).loadHistory())
        assertFalse(File(local, "transcript-history.json").exists())
        assertEquals(2, combined.size)
        assertEquals(previous.single(), combined.last())
        val migrated = TranscriptStore(destination, local)
        combined.forEach { migrated.delete(it.id) }
        assertTrue(TranscriptStore(destination, local).loadHistory().isEmpty())
    }

    @Test fun corruptOrConflictingMigrationKeepsBothHistories() = withStore { local, old ->
        old.save(transcript("old.wav", "same-job"), ProcessingCancellation())
        val original = File(local, "transcript-history.json").readBytes()
        val destination = File(local, "backup-eligible").apply { mkdirs() }
        val restored = TranscriptStore(destination)
        val saved = restored.save(transcript("different.wav", "same-job"), ProcessingCancellation())
        try {
            TranscriptStore(destination, local).loadHistory()
            fail("Conflicting completed jobs accepted")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(original, File(local, "transcript-history.json").readBytes())
        assertEquals(saved, restored.loadHistory())
        File(local, "transcript-history.json").writeText("broken")
        try {
            TranscriptStore(destination, local).loadHistory()
            fail("Corrupt migration source discarded")
        } catch (_: org.json.JSONException) { }
        assertEquals("broken", File(local, "transcript-history.json").readText())
        assertEquals(saved, restored.loadHistory())
    }

    @Test fun originalSingleResultMigratesDirectlyToBackupFolder() = withStore { local, _ ->
        val original = transcript("legacy.mp4", "legacy-job")
        val legacy = File(local, "latest-transcript.json")
        legacy.writeText(JSONObject().put("version", 1).put("sourceName", original.sourceName)
            .put("jobId", original.jobId).put("segments", org.json.JSONArray().put(JSONObject()
                .put("startMs", 123).put("endMs", 456).put("text", original.segments.single().text))).toString())
        val savedAt = legacy.lastModified()
        val destination = File(local, "backup-eligible")
        val saved = TranscriptStore(destination, local).loadHistory().single()
        assertEquals(original, saved.transcript)
        assertEquals(savedAt, saved.completedAtMs)
        assertFalse(legacy.exists())
        assertEquals(listOf(saved), TranscriptStore(destination, local).loadHistory())
    }
}
