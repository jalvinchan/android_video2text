package com.example.video2text

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflinePipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TranscriptionApplication

    private fun fixture(name: String): File {
        val directory = File(app.cacheDir, "exports/test-${UUID.randomUUID()}").apply { mkdirs() }
        return File(directory, name).also { target ->
            instrumentation.context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
    private fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.exports", file)

    @Test fun realVideoTranscribesOfflineAndCleansTemporaryMedia(): Unit = runBlocking {
        val video = fixture("speech.mp4")
        val directory = File(app.cacheDir, "pipeline-test-${UUID.randomUUID()}")
        val start = SystemClock.elapsedRealtime()
        val transcript = OfflineTranscriber(app).run(uri(video), directory, ProcessingCancellation()) { _, _ -> }
        val plain = TranscriptFormatter.format(transcript, TranscriptFormat.PLAIN_TEXT)
        assertTrue(plain, plain.contains("country", ignoreCase = true))
        assertTrue(plain, plain.contains("ask", ignoreCase = true))
        assertTrue(transcript.segments.all { it.startMs >= 0 && it.endMs >= it.startMs })
        assertFalse(directory.exists())
        android.util.Log.i("OfflinePipelineTest", "JFK video elapsedMs=${SystemClock.elapsedRealtime() - start}; text=$plain")
        video.parentFile!!.deleteRecursively()
    }

    @Test fun missingAudioSilentAudioAndCorruptMediaAreRecoverable() {
        val directory = File(app.cacheDir, "decoder-test-${UUID.randomUUID()}").apply { mkdirs() }
        for ((name, message) in listOf("no-audio.mp4" to "no audio track", "silent.mp4" to "silent")) {
            val video = fixture(name)
            try { MediaAudioDecoder().decode(video, File(directory, "pcm"), {}, {}); fail(name) }
            catch (error: IllegalArgumentException) { assertTrue(error.message, error.message!!.contains(message)) }
            video.parentFile!!.deleteRecursively()
        }
        val corrupt = File(directory, "corrupt.mp4").apply { writeText("not a video") }
        try { MediaAudioDecoder().decode(corrupt, File(directory, "pcm"), {}, {}); fail("Corrupt video accepted") }
        catch (_: Exception) { }
        directory.deleteRecursively()
    }

    @Test fun decoderKeepsDelayedAudioRelativeToTheVideo() {
        val video = fixture("offset-speech.mp4")
        val directory = File(app.cacheDir, "offset-test-${UUID.randomUUID()}").apply { mkdirs() }
        val audio = MediaAudioDecoder().decode(video, File(directory, "pcm"), {}, {})
        assertTrue("offset=${audio.offsetMs}", audio.offsetMs in 1_900..2_100)
        assertTrue(audio.durationMs in 10_000..12_000)
        assertEquals(0L, audio.file.length() % 4)
        directory.deleteRecursively()
        video.parentFile!!.deleteRecursively()
    }

    @Test fun shareAndSaveUseUtf8AndTheEntireSelectedFormat(): Unit = runBlocking {
        val repository = app.repository
        repository.ready.await()
        val transcript = Transcript("example.mp4", listOf(TranscriptSegment(12_000, 13_000, "Café—你好，廣東話！"), TranscriptSegment(20_000, 21_000, "普通话，第二句。")))
        kotlinx.coroutines.withContext(Dispatchers.IO) { repository.complete(transcript, ProcessingCancellation()) }
        val historicalId = repository.state.value.history.first().id
        withContext(Dispatchers.IO) { repository.complete(Transcript("newer.wav", listOf(TranscriptSegment(0, 100, "Different text"))), ProcessingCancellation()) }
        val viewModel = TranscriptionViewModel(app)
        withContext(Dispatchers.Main) { viewModel.openTranscript(historicalId) }
        for (format in TranscriptFormat.entries) {
            viewModel.selectFormat(format)
            viewModel.share()
            val event = withTimeout(5_000) { viewModel.events.first() } as UiEvent.Share
            @Suppress("DEPRECATION") val shared = event.intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            val expected = TranscriptFormatter.format(transcript, format)
            assertEquals(expected, app.contentResolver.openInputStream(shared)!!.bufferedReader(Charsets.UTF_8).use { it.readText() })
            assertEquals(TranscriptFormatter.fileName(transcript, format), viewModel.prepareSave())
            val destination = File(app.cacheDir, "exports/test-${UUID.randomUUID()}").apply { mkdirs() }
            val file = File(destination, "saved.txt").apply { createNewFile() }
            viewModel.save(uri(file))
            assertEquals(UiEvent.Message("Full transcript saved."), withTimeout(5_000) { viewModel.events.first() })
            assertEquals(expected, file.readText(Charsets.UTF_8))
            destination.deleteRecursively()
        }
    }

    @Test fun atomicStorageSurvivesReopeningAndCancellationPreservesPreviousResult() {
        val directory = File(app.noBackupFilesDir, "store-test-${UUID.randomUUID()}").apply { mkdirs() }
        val original = Transcript("original.mp4", listOf(TranscriptSegment(123, 456, "原文，廣東話。")))
        val store = TranscriptStore(directory)
        store.save(original, ProcessingCancellation())
        assertEquals(original, TranscriptStore(directory).load())
        val cancelled = ProcessingCancellation().apply { cancel() }
        try { store.save(Transcript("new", emptyList()), cancelled); fail("Cancelled result saved") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(original, TranscriptStore(directory).load())
        store.markActive()
        assertTrue(TranscriptStore(directory).wasInterrupted())
        store.clearActive()
        assertFalse(store.wasInterrupted())
        store.delete(store.loadHistory().single().id)
        assertNull(store.load())
        directory.deleteRecursively()
    }

    @Test fun shareImportRejectsConcurrentStartAndCancellationKeepsPreviousResult(): Unit = runBlocking {
        val repository = app.repository
        repository.ready.await()
        val original = Transcript("previous", listOf(TranscriptSegment(0, 1, "Keep me.")))
        kotlinx.coroutines.withContext(Dispatchers.IO) { repository.complete(original, ProcessingCancellation()) }
        val video = fixture("speech.mp4")
        val shared = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri(video)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        withContext(Dispatchers.IO) { repository.discardJob() }
        ActivityScenario.launch<MainActivity>(shared).use { scenario ->
            withTimeout(30_000) { repository.state.first { it.pendingJob != null && !it.isActive } }
            assertNull(repository.state.value.pendingJob!!.options)
            val pending = repository.state.value.pendingJob!!
            app.startService(TranscriptionService.startIntent(app, pending.id, TranscriptionOptions(SpeechLanguage.ENGLISH)))
            withTimeout(30_000) { repository.state.first { it.stage == ProcessingStage.TRANSCRIBING } }
            val jobOptions = repository.state.value.activeOptions
            app.models.selectLanguage(SpeechLanguage.MANDARIN)
            withTimeout(5_000) { app.models.state.first { it.options.language == SpeechLanguage.MANDARIN } }
            assertEquals(jobOptions, repository.state.value.activeOptions)
            scenario.recreate()
            app.startService(TranscriptionService.startIntent(app, pending.id, TranscriptionOptions(SpeechLanguage.ENGLISH)))
            withTimeout(5_000) { repository.state.first { it.message?.contains("already running") == true } }
            app.startService(TranscriptionService.cancelIntent(app))
            withTimeout(30_000) { repository.state.first { it.stage == ProcessingStage.CANCELLED } }
            assertEquals(original, repository.state.value.transcript)
            assertEquals(original, TranscriptStore(app.noBackupFilesDir).load())
            assertTrue(repository.state.value.pendingJob!!.hasAudio)
            assertTrue(repository.jobs.audio.exists())
            withContext(Dispatchers.IO) { repository.discardJob() }
        }
        app.models.selectLanguage(SpeechLanguage.AUTO)
        video.parentFile!!.deleteRecursively()
    }

    @Test fun uriValidationDoesNotReadUnselectedFiles(): Unit = runBlocking {
        val directory = File(app.cacheDir, "invalid-uri-test")
        try { OfflineTranscriber(app).run(Uri.parse("file:///etc/passwd"), directory, ProcessingCancellation()) { _, _ -> }; fail("Invalid URI accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("file picker")) }
        assertFalse(directory.exists())
    }
}
