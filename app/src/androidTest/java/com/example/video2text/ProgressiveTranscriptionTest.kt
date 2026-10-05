package com.example.video2text

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressiveTranscriptionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TranscriptionApplication

    private fun import(root: File, name: String, language: SpeechLanguage): Pair<TranscriptionJobStore, TranscriptionJob> {
        val source = File(app.cacheDir, "exports/progressive-${UUID.randomUUID()}/$name").apply { parentFile!!.mkdirs() }
        try {
            instrumentation.context.assets.open(name).use { input -> source.outputStream().use { input.copyTo(it) } }
            val store = TranscriptionJobStore(root)
            val job = store.import(app, FileProvider.getUriForFile(app, "${app.packageName}.exports", source), ProcessingCancellation()) {}
                .copy(options = TranscriptionOptions(language))
            store.save(job)
            return store to job
        } finally { source.parentFile!!.deleteRecursively() }
    }

    @Test fun guidedAndAutoCheckpointBeforeExtractionEndsAndResumeWithoutRepeatingSavedSections(): Unit = runBlocking(Dispatchers.IO) {
        for (language in listOf(SpeechLanguage.MANDARIN, SpeechLanguage.AUTO)) {
            val root = File(app.noBackupFilesDir, "progressive-test-${UUID.randomUUID()}")
            try {
                val (store, job) = import(root, "boundary.mp4", language)
                val token = ProcessingCancellation()
                try {
                    OfflineTranscriber(app).resume(job, store, token, { _, _ -> }) {
                        if (it.completedSections == 1) token.cancel()
                    }
                    fail("Expected pause after the first section")
                } catch (_: CancellationException) { }
                val reopenedStore = TranscriptionJobStore(root)
                val reopened = reopenedStore.load()!!
                assertTrue(reopened.progressive)
                assertFalse(reopened.extractionComplete)
                assertEquals(1, reopened.completedSections)
                assertTrue(reopenedStore.source.exists())
                assertFalse(reopenedStore.audio.exists())
                assertTrue(reopenedStore.windowAudio.length() in 1..((PCM_WINDOW + 2 * PCM_HALO) * 4L))
                assertTrue(reopened.window!!.coreEndSample.toLong() * 1000 / PCM_RATE < reopened.sourceDurationMs!!)
                val first = File(store.directory, "section-0.json").readBytes()
                val saved = reopenedStore.readSection(reopened, 0)
                val result = OfflineTranscriber(app).resume(reopened, reopenedStore, ProcessingCancellation(), { _, _ -> }) {
                    assertTrue(it.completedSections >= 1)
                    assertTrue(reopenedStore.windowAudio.length() <= (PCM_WINDOW + 2 * PCM_HALO) * 4L)
                }
                assertArrayEquals(first, File(store.directory, "section-0.json").readBytes())
                assertEquals(saved, result.segments.take(saved.size))
                assertTrue(result.segments.zipWithNext().all { (a, b) -> b.startMs >= a.endMs })
                val text = TranscriptFormatter.format(result, TranscriptFormat.PLAIN_TEXT)
                assertTrue(text, Regex("[声聲]明").findAll(text).count() in 5..6)
                val finished = reopenedStore.load()!!
                assertTrue(finished.extractionComplete)
                assertFalse(finished.needsModel)
                assertFalse(reopenedStore.source.exists())
                assertFalse(reopenedStore.windowAudio.exists())
                assertEquals(result, OfflineTranscriber(app).resume(finished, reopenedStore, ProcessingCancellation(), { _, _ -> fail("Repeated completed inference") }) { fail("Repeated commit") })
                assertNull(reopenedStore.load(result.jobId)) // Crash after successful-result commit, before cleanup.
                assertFalse(reopenedStore.directory.exists())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun stoppingAfterPlanningReusesWindowAndPreservesOriginalOffset(): Unit = runBlocking(Dispatchers.IO) {
        val root = File(app.noBackupFilesDir, "progressive-offset-${UUID.randomUUID()}")
        try {
            val (store, job) = import(root, "offset-mandarin.mp4", SpeechLanguage.MANDARIN)
            val token = ProcessingCancellation()
            try {
                OfflineTranscriber(app).resume(job, store, token, { _, _ -> }) {
                    if (it.window?.planned == true) token.cancel()
                }
                fail("Expected pause before inference")
            } catch (_: CancellationException) { }
            val reopened = TranscriptionJobStore(root).load()!!
            assertEquals(0, reopened.completedSections)
            assertTrue(reopened.audioOffsetMs!! in 1900..2100)
            val pcm = store.windowAudio.readBytes()
            val plan = reopened.sections
            val result = OfflineTranscriber(app).resume(reopened, store, ProcessingCancellation(), { _, _ -> }) {
                if (it.window != null) {
                    assertEquals(plan, it.sections)
                    assertArrayEquals(pcm, store.windowAudio.readBytes())
                }
            }
            assertTrue(result.segments.all { it.startMs >= reopened.audioOffsetMs })
        } finally { root.deleteRecursively() }
    }

    @Test fun atomicWindowProgressAndEmptySectionsSurviveInterruptedWrites() {
        val root = File(app.noBackupFilesDir, "progressive-atomic-${UUID.randomUUID()}")
        try {
            val store = TranscriptionJobStore(root)
            store.directory.mkdirs()
            store.source.writeText("Retained source")
            var job = TranscriptionJob("job", "source", null, TranscriptionOptions(SpeechLanguage.ENGLISH), sections = emptyList(), progressive = true)
            store.save(job)
            job = store.retainWindow(job, 0, 0, PCM_WINDOW, FloatArray(PCM_WINDOW + PCM_HALO), 2000, ProcessingCancellation())
            job = job.copy(window = job.window!!.copy(planned = true), sections = listOf(AudioSection(0, PCM_WINDOW)))
            store.save(job)
            val cancelled = ProcessingCancellation().apply { cancel() }
            try { store.checkpoint(job, 0, emptyList(), cancelled); fail("Cancelled section committed") }
            catch (_: CancellationException) { }
            assertEquals(0, TranscriptionJobStore(root).load()!!.completedSections)
            val blocked = File(store.directory, "section-0.json.new").apply { mkdirs() }
            File(blocked, "blocker").writeText("Cannot replace")
            try { store.checkpoint(job, 0, emptyList(), ProcessingCancellation()); fail("Failed checkpoint advanced") }
            catch (_: java.io.IOException) { }
            assertTrue(blocked.deleteRecursively())
            store.checkpoint(job, 0, emptyList(), ProcessingCancellation())
            job = TranscriptionJobStore(root).load()!!
            assertEquals(1, job.completedSections)
            assertTrue(store.readSection(job, 0).isEmpty())
            val blockedRecord = File(store.directory, "job.json.new").apply { mkdirs() }
            File(blockedRecord, "blocker").writeText("Cannot replace")
            try { store.finishWindow(job); fail("Failed metadata removed audio") }
            catch (_: java.io.IOException) { }
            assertTrue(store.windowAudio.exists())
            assertEquals(0, TranscriptionJobStore(root).load()!!.processedSamples)
            blockedRecord.deleteRecursively()
            job = store.finishWindow(job)
            assertFalse(store.windowAudio.exists())
            assertTrue(store.source.exists())
            // A silent detector window still advances durable decoded progress.
            job = store.retainWindow(job, PCM_WINDOW - PCM_HALO, PCM_WINDOW, 2 * PCM_WINDOW, FloatArray(PCM_WINDOW + 2 * PCM_HALO), 2000, ProcessingCancellation())
                .let { it.copy(window = it.window!!.copy(planned = true)) }
            store.save(job)
            job = store.finishWindow(job)
            assertEquals(2 * PCM_WINDOW, TranscriptionJobStore(root).load()!!.processedSamples)
            store.finishDecoding(job, DecodedTimeline(2000, 2 * PCM_WINDOW))
            assertFalse(store.source.exists())
            assertFalse(TranscriptionJobStore(root).load()!!.needsModel)
        } finally { root.deleteRecursively() }
    }

    @Test fun uncommittedWindowIsRemovedAndDamagedCommittedWindowRequiresDiscard() {
        val root = File(app.noBackupFilesDir, "progressive-invalid-${UUID.randomUUID()}")
        try {
            val store = TranscriptionJobStore(root)
            store.directory.mkdirs()
            store.source.writeText("Retained source")
            val job = TranscriptionJob("job", "source", null, TranscriptionOptions(SpeechLanguage.ENGLISH), sections = emptyList(), progressive = true)
            store.save(job)
            store.windowAudio.writeText("Orphaned PCM")
            File(store.directory, "window.part").writeText("Interrupted PCM")
            assertEquals(job, TranscriptionJobStore(root).load())
            assertFalse(store.windowAudio.exists())
            assertFalse(File(store.directory, "window.part").exists())
            store.retainWindow(job, 0, 0, 1, floatArrayOf(0.1f), 0, ProcessingCancellation())
            store.windowAudio.appendText("damaged")
            try { TranscriptionJobStore(root).load(); fail("Damaged window accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(store.source.exists())
            store.discard()
            assertFalse(store.directory.exists())
        } finally { root.deleteRecursively() }
    }
}
