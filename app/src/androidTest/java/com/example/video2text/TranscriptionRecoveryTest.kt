package com.example.video2text

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TranscriptionApplication

    private fun imported(name: String, root: File): Pair<TranscriptionJobStore, TranscriptionJob> {
        val source = File(app.cacheDir, "exports/recovery-${UUID.randomUUID()}/$name").apply { parentFile!!.mkdirs() }
        try {
            instrumentation.context.assets.open(name).use { input -> source.outputStream().use { input.copyTo(it) } }
            val store = TranscriptionJobStore(root)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.exports", source)
            return store to store.import(app, uri, ProcessingCancellation()) {}
        } finally { source.parentFile!!.deleteRecursively() }
    }

    @Test fun guidedAndAutoResumeCompletedSectionsWithoutRepeatingThem(): Unit = runBlocking(Dispatchers.IO) {
        for (language in listOf(SpeechLanguage.MANDARIN, SpeechLanguage.AUTO)) {
            val root = File(app.noBackupFilesDir, "recovery-test-${UUID.randomUUID()}")
            try {
                val (store, imported) = imported("boundary.mp4", root)
                val fixed = store.extract(imported.copy(options = TranscriptionOptions(language)), ProcessingCancellation()) {} // Legacy full-PCM recovery.
                store.save(fixed)
                val cancellation = ProcessingCancellation()
                try {
                    OfflineTranscriber(app).resume(fixed, store, cancellation, { _, _ -> }) { job ->
                        if (job.completedSections == 1) cancellation.cancel()
                    }
                    fail("Expected cancellation after first checkpoint")
                } catch (_: CancellationException) { }
                val reopenedStore = TranscriptionJobStore(root)
                val reopened = reopenedStore.load()!!
                assertTrue(reopened.hasAudio)
                assertEquals(1, reopened.completedSections)
                assertTrue(reopened.sections!!.size > 1)
                assertFalse(reopenedStore.source.exists())
                val pcmModified = reopenedStore.audio.lastModified()
                val originalSection = reopenedStore.readSection(reopened, 0)
                val checkpoint = File(reopenedStore.directory, "section-0.json").readBytes()
                val emittedCounts = mutableListOf<Int>()
                val result = OfflineTranscriber(app).resume(reopened, reopenedStore, ProcessingCancellation(), { _, _ -> }) { emittedCounts += it.completedSections }
                assertTrue(emittedCounts.all { it > 1 })
                assertEquals(pcmModified, reopenedStore.audio.lastModified())
                assertArrayEquals(checkpoint, File(reopenedStore.directory, "section-0.json").readBytes())
                assertEquals(originalSection, result.segments.take(originalSection.size))
                assertTrue(result.segments.zipWithNext().all { (a, b) -> b.startMs >= a.endMs })
                val text = TranscriptFormatter.format(result, TranscriptFormat.PLAIN_TEXT)
                assertTrue(text, Regex("[声聲]明").findAll(text).count() in 5..6)
                assertEquals(reopened.id, result.jobId)
                // All checkpoints complete: a retry uses saved text and never opens the model again.
                val final = reopenedStore.load()!!
                val retry = OfflineTranscriber(app).resume(final, reopenedStore, ProcessingCancellation(), { _, _ -> fail("Repeated completed inference") }) { fail("Repeated checkpoint") }
                assertEquals(result, retry)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun interruptedAudioAndCheckpointWritesKeepOnlyCommittedData() {
        val root = File(app.noBackupFilesDir, "atomic-job-${UUID.randomUUID()}")
        try {
            val (store, imported) = imported("offset-mandarin.mp4", root)
            val job = imported.copy(options = TranscriptionOptions(SpeechLanguage.MANDARIN))
            store.save(job)
            File(store.directory, "audio.part").writeText("unfinished extraction")
            val reopened = TranscriptionJobStore(root).load()!!
            assertFalse(reopened.hasAudio)
            assertTrue(store.source.exists())
            assertFalse(File(store.directory, "audio.part").exists())
            val ready = store.extract(reopened, ProcessingCancellation()) {}
            assertTrue(ready.audioOffsetMs!! in 1900..2100)
            val planned = ready.copy(sections = listOf(AudioSection(0, (ready.audioBytes!! / 4).toInt())))
            store.save(planned)
            val segments = listOf(TranscriptSegment(ready.audioOffsetMs, ready.audioOffsetMs + 1000, "已完成"))
            val cancelled = ProcessingCancellation().apply { cancel() }
            try { store.checkpoint(planned, 0, segments, cancelled); fail("Cancelled section committed") }
            catch (_: CancellationException) { }
            assertEquals(0, TranscriptionJobStore(root).load()!!.completedSections)
            val atomic = AtomicFile(File(store.directory, "section-0.json"))
            atomic.startWrite().use { it.write("partial JSON".toByteArray()) } // Simulated process death before finishWrite.
            assertEquals(0, TranscriptionJobStore(root).load()!!.completedSections)
            val blocked = File(store.directory, "section-0.json.new")
            blocked.delete()
            assertTrue(blocked.mkdirs())
            File(blocked, "blocker").writeText("Cannot replace this staging path")
            try { store.checkpoint(planned, 0, segments, ProcessingCancellation()); fail("Write failure treated as completion") }
            catch (_: java.io.IOException) { }
            assertEquals(0, TranscriptionJobStore(root).load()!!.completedSections)
            assertTrue(blocked.deleteRecursively())
            store.checkpoint(planned, 0, segments, ProcessingCancellation())
            assertEquals(1, TranscriptionJobStore(root).load()!!.completedSections)
            assertEquals(segments, store.readSection(planned, 0))
            store.audio.appendText("corruption")
            try { TranscriptionJobStore(root).load(); fail("Invalid PCM accepted") } catch (_: IllegalArgumentException) { }
            store.discard()
            assertFalse(store.directory.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun immutableSettingsDiscardAndSuccessfulCleanupPreservePreviousResult(): Unit = runBlocking {
        val repository = app.repository
        repository.ready.await()
        withContext(Dispatchers.IO) { repository.discardJob() }
        val previous = Transcript("previous", listOf(TranscriptSegment(0, 1000, "Keep this result.")))
        withContext(Dispatchers.IO) { repository.complete(previous, ProcessingCancellation()) }
        val source = File(app.cacheDir, "exports/repository-${UUID.randomUUID()}/speech.mp4").apply { parentFile!!.mkdirs() }
        try {
            instrumentation.context.assets.open("speech.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.exports", source)
            withContext(Dispatchers.IO) { repository.importMedia(uri, ProcessingCancellation()) }
            val imported = repository.state.value.pendingJob!!
            assertFalse(repository.state.value.isActive)
            assertNull(imported.options)
            val started = withContext(Dispatchers.IO) { repository.startJob(imported.id, TranscriptionOptions(SpeechLanguage.ENGLISH)) }
            withContext(Dispatchers.IO) { repository.finish(ProcessingStage.CANCELLED, "Cancelled") }
            try { withContext(Dispatchers.IO) { repository.startJob(started.id, TranscriptionOptions(SpeechLanguage.MANDARIN)) }; fail("Mixed job settings") }
            catch (_: IllegalArgumentException) { }
            assertEquals(previous, repository.state.value.transcript)
            withContext(Dispatchers.IO) { repository.discardJob() }
            assertEquals(previous, TranscriptStore(app.noBackupFilesDir).load())
            withContext(Dispatchers.IO) { repository.importMedia(uri, ProcessingCancellation()) }
            val next = repository.state.value.pendingJob!!
            val result = Transcript(next.sourceName, listOf(TranscriptSegment(0, 1000, "New result")), next.id)
            withContext(Dispatchers.IO) { repository.complete(result, ProcessingCancellation()) }
            assertNull(repository.state.value.pendingJob)
            assertFalse(repository.jobs.directory.exists())
            assertEquals(result, TranscriptStore(app.noBackupFilesDir).load())
        } finally {
            withContext(Dispatchers.IO) { repository.discardJob() }
            source.parentFile!!.deleteRecursively()
        }
    }
}
