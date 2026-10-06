package com.example.video2text

import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TranscriptionApplication
    private fun root() = File(app.cacheDir, "exports/audio-test-${UUID.randomUUID()}").apply { mkdirs() }
    private fun fixture(root: File, asset: String, name: String = asset) = File(root, name).also { file ->
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.exports", file)

    // Reuse the public-domain AAC speech fixture without adding another binary asset.
    private fun audioOnly(source: File, destination: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
            }
            extractor.selectTrack(track)
            val outputTrack = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, 0)
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally { try { muxer.release() } finally { extractor.release() } }
    }

    @Test fun wavAndCompressedAudioUseDurableExtractionAndCheckpoints() {
        val root = root()
        try {
            val wav = fixture(root, "jfk.wav", "misleading-video.mp4")
            val m4a = File(root, "speech.m4a")
            audioOnly(fixture(root, "speech.mp4"), m4a)
            for (source in listOf(wav, m4a)) {
                val jobRoot = File(root, source.name + "-job")
                val store = TranscriptionJobStore(jobRoot)
                val imported = store.import(app, uri(source), ProcessingCancellation()) {}
                assertFalse(imported.sourceHasVideo)
                assertNull(imported.options)
                assertFalse(imported.hasAudio)
                assertTrue(imported.sourceDurationMs!! > 10_000)
                assertEquals("Transcribing speech", ProcessingStage.EXTRACTING.label())
                val fixed = imported.copy(options = TranscriptionOptions(SpeechLanguage.ENGLISH))
                store.save(fixed)
                val cancelled = ProcessingCancellation().apply { cancel() }
                try { store.extract(fixed, cancelled) {}; fail("Cancelled extraction succeeded") }
                catch (_: kotlinx.coroutines.CancellationException) { }
                assertTrue(store.source.exists())
                assertFalse(TranscriptionJobStore(jobRoot).load()!!.hasAudio)
                val ready = store.extract(fixed, ProcessingCancellation()) {}
                assertTrue(ready.audioDurationMs!! > 10_000)
                assertTrue(ready.audioOffsetMs!! in 0..100)
                assertFalse(store.source.exists())
                val planned = ready.copy(sections = listOf(AudioSection(0, (ready.audioBytes!! / 4).toInt())))
                store.save(planned)
                val saved = listOf(TranscriptSegment(ready.audioOffsetMs, ready.audioOffsetMs + 1000, "Saved speech"))
                store.checkpoint(planned, 0, saved, ProcessingCancellation())
                val reopened = TranscriptionJobStore(jobRoot).load()!!
                assertFalse(reopened.sourceHasVideo)
                assertEquals(fixed.options, reopened.options)
                assertEquals(1, reopened.completedSections)
                assertEquals(saved, store.readSection(reopened, 0))
                val modified = store.audio.lastModified()
                assertEquals(reopened, store.extract(reopened, ProcessingCancellation()) {})
                assertEquals(modified, store.audio.lastModified())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidImportsLeaveNoRetainedJobAndLegacyVideoRecordsStillOpen() {
        val root = root()
        try {
            val corrupt = File(root, "pretend.mp3").apply { writeText("Not audio") }
            for (source in listOf(corrupt, fixture(root, "no-audio.mp4"))) {
                val store = TranscriptionJobStore(File(root, "invalid-job"))
                try { store.import(app, uri(source), ProcessingCancellation()) {}; fail("Invalid source accepted") }
                catch (error: Exception) { assertFalse(error is kotlinx.coroutines.CancellationException) }
                assertNull(store.load())
                assertFalse(store.directory.exists())
            }
            val store = TranscriptionJobStore(File(root, "legacy-job"))
            val imported = store.import(app, uri(fixture(root, "speech.mp4")), ProcessingCancellation()) {}
            val fixed = imported.copy(options = TranscriptionOptions(SpeechLanguage.ENGLISH))
            store.save(fixed)
            val record = File(store.directory, "job.json")
            val json = JSONObject(record.readText())
            json.put("videoDurationMs", json.getLong("sourceDurationMs"))
            json.remove("sourceDurationMs")
            json.remove("sourceHasVideo")
            record.writeText(json.toString())
            assertEquals(fixed, TranscriptionJobStore(store.directory.parentFile!!).load())
            assertEquals("Transcribing speech", ProcessingStage.EXTRACTING.label())
        } finally { root.deleteRecursively() }
    }

    @Test fun audioShareUsesTracksDespiteMisleadingMimeAndWaitsForStart(): Unit = runBlocking {
        val root = root()
        val repository = app.repository
        repository.ready.await()
        withContext(Dispatchers.IO) { repository.discardJob() }
        val previous = Transcript("previous", listOf(TranscriptSegment(0, 1000, "Keep me")))
        withContext(Dispatchers.IO) { repository.complete(previous, ProcessingCancellation()) }
        try {
            for (mime in listOf("audio/wav", "video/mp4", "application/octet-stream")) {
                val source = fixture(root, "jfk.wav", "misleading.mp4")
                val shared = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri(source)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ActivityScenario.launch<MainActivity>(shared).use { scenario ->
                    val state = withTimeout(30_000) { repository.state.first { it.pendingJob != null && !it.isActive } }
                    assertFalse(state.pendingJob!!.sourceHasVideo)
                    assertNull(state.pendingJob.options)
                    assertFalse(repository.jobs.audio.exists())
                    assertEquals(previous, state.transcript)
                    scenario.recreate()
                    assertEquals(state.pendingJob.id, repository.state.value.pendingJob!!.id)
                }
                withContext(Dispatchers.IO) { repository.discardJob() }
                assertEquals(previous, repository.state.value.transcript)
            }
        } finally {
            withContext(Dispatchers.IO) { repository.discardJob() }
            root.deleteRecursively()
        }
    }

    @Test fun audioTranscribesThroughTheExistingOfflineEngine(): Unit = runBlocking {
        val root = root()
        try {
            val source = fixture(root, "jfk.wav")
            val directory = File(root, "pipeline")
            val result = OfflineTranscriber(app).run(uri(source), directory, ProcessingCancellation(), TranscriptionOptions(SpeechLanguage.ENGLISH)) { _, _ -> }
            val text = TranscriptFormatter.format(result, TranscriptFormat.PLAIN_TEXT)
            assertTrue(text, text.contains("country", ignoreCase = true))
            assertTrue(result.segments.all { it.startMs >= 0 && it.endMs >= it.startMs })
            assertFalse(directory.exists())
        } finally { root.deleteRecursively() }
    }
}
