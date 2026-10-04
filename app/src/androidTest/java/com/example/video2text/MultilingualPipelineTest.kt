package com.example.video2text

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultilingualPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TranscriptionApplication
    private fun fixture(name: String, directory: File) = File(directory, name).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun text(transcript: Transcript) = TranscriptFormatter.format(transcript, TranscriptFormat.PLAIN_TEXT)
    private fun chinese(value: String) = value.any { it in '\u4e00'..'\u9fff' }

    private suspend fun runBase(name: String, language: SpeechLanguage): Transcript {
        val directory = File(app.cacheDir, "exports/multilingual-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = fixture(name, directory)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.exports", file)
            val progress = mutableListOf<Int>()
            val work = File(app.cacheDir, "multilingual-work-${UUID.randomUUID()}")
            val transcript = OfflineTranscriber(app).run(uri, work, ProcessingCancellation(), TranscriptionOptions(language)) { stage, percent ->
                if (stage == ProcessingStage.TRANSCRIBING && percent != null) progress += percent
            }
            assertFalse(work.exists())
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
            assertTrue(transcript.segments.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })
            android.util.Log.i("MultilingualPipelineTest", "$name $language: ${text(transcript)}; segments=${transcript.segments}")
            return transcript
        } finally { directory.deleteRecursively() }
    }

    @Test fun baseGuidedMandarinRetainsChinese(): Unit = runBlocking {
        val result = runBase("offset-mandarin.mp4", SpeechLanguage.MANDARIN)
        assertTrue(text(result), chinese(text(result)))
        assertTrue(text(result), text(result).contains("土耳其"))
        assertTrue(result.segments.all { it.startMs >= 1_900 && it.endMs <= 18_000 })
    }

    @Test fun autoChangesFromEnglishToMandarinAfterPause(): Unit = runBlocking {
        val result = runBase("mixed.mp4", SpeechLanguage.AUTO)
        assertTrue(text(result), text(result).contains("country", ignoreCase = true))
        assertTrue(text(result), text(result).contains("土耳其"))
        val firstChinese = result.segments.first { chinese(it.text) }
        assertTrue("$firstChinese", firstChinese.startMs >= 11_000)
    }

    @Test fun continuousSpeechAcrossThirtySecondsHasOrderedTimestampsAndNoDuplicatedPhrases(): Unit = runBlocking {
        val result = runBase("boundary.mp4", SpeechLanguage.AUTO)
        val plain = text(result)
        val mentions = Regex("[声聲]明").findAll(plain).count()
        assertTrue(plain, mentions in 5..6)
        assertTrue(result.segments.any { it.endMs > 30_000 })
        assertTrue(result.segments.zipWithNext().all { (a, b) -> b.startMs >= a.endMs })
    }

    /** Optional files are supplied by the verification harness, never bundled in the test APK. */
    private suspend fun runOptional(model: SpeechModel, name: String, language: SpeechLanguage): Transcript {
        val modelFile = File(app.getExternalFilesDir(null), "verification/${model.fileName}")
        assumeTrue("Supply ${model.fileName} in files/verification to exercise this optional model", modelFile.exists())
        assertTrue(modelFile.inputStream().use { ModelFiles.verify(it, model.bytes, model.sha256, ProcessingCancellation()) })
        val directory = File(app.cacheDir, "optional-native-${UUID.randomUUID()}").apply { mkdirs() }
        val prepared = app.models.acquire(TranscriptionOptions(), ProcessingCancellation()) {}
        try {
            val video = fixture(name, directory)
            val audio = MediaAudioDecoder().decode(video, File(directory, "pcm.f32"), {}, {})
            val segments = mutableListOf<TranscriptSegment>()
            WhisperBridge.transcribe(modelFile.absolutePath, prepared.vad.absolutePath, audio.file.absolutePath, language.code, model.name, audio.offsetMs, maxOf(1, Runtime.getRuntime().availableProcessors() - 1),
                object : WhisperCallbacks {
                    override fun isCancelled() = false
                    override fun onProgress(percent: Int) {}
                    override fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray) {
                        segments += TranscriptSegment(startMs, endMs, utf8.toString(Charsets.UTF_8).trim())
                    }
                })
            val result = Transcript(name, segments)
            android.util.Log.i("MultilingualPipelineTest", "$model $name $language: ${text(result)}")
            assertTrue(segments.isNotEmpty())
            assertTrue(segments.all { it.startMs >= 0 && it.endMs >= it.startMs && it.endMs <= audio.durationMs + audio.offsetMs })
            return result
        } finally { app.models.release(); directory.deleteRecursively() }
    }

    @Test fun smallLoadsAndTranscribesMandarinOffline(): Unit = runBlocking {
        val result = runOptional(SpeechModel.SMALL, "mandarin.mp4", SpeechLanguage.MANDARIN)
        assertTrue(text(result), text(result).contains("土耳其"))
    }

    @Test fun turboLoadsAndTranscribesCantoneseOffline(): Unit = runBlocking {
        val result = runOptional(SpeechModel.TURBO, "cantonese.mp4", SpeechLanguage.CANTONESE)
        assertTrue(text(result), chinese(text(result)))
        assertTrue(text(result), Regex("[岛島]").containsMatchIn(text(result)))
    }

    @Test fun turboAutoProcessesThreeLanguagesInOrder(): Unit = runBlocking {
        val result = runOptional(SpeechModel.TURBO, "mixed-all.mp4", SpeechLanguage.AUTO)
        val plain = text(result)
        assertTrue(plain, plain.contains("fellow", ignoreCase = true))
        assertTrue(plain, plain.contains("土耳其"))
        assertTrue(plain, Regex("[岛島]").containsMatchIn(plain))
        assertTrue(result.segments.zipWithNext().all { (a, b) -> b.startMs >= a.endMs })
    }

    @Test fun jniRejectsCantoneseWithBaseBeforeOpeningUntrustedPaths() {
        try {
            WhisperBridge.transcribe("missing-model", "missing-vad", "missing-pcm", "yue", "BASE", 0, 1,
                object : WhisperCallbacks {
                    override fun isCancelled() = false
                    override fun onProgress(percent: Int) {}
                    override fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray) {}
                })
            fail("JNI accepted incompatible options")
        } catch (error: java.io.IOException) { assertTrue(error.message!!.contains("Invalid transcription")) }
    }
    @Test fun jniRejectsTimestampOverflowBeforeOpeningUntrustedPaths() {
        try {
            WhisperBridge.transcribe("missing-model", "missing-vad", "missing-pcm", "en", "BASE", Long.MAX_VALUE, 1,
                object : WhisperCallbacks {
                    override fun isCancelled() = false
                    override fun onProgress(percent: Int) {}
                    override fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray) {}
                })
            fail("JNI accepted an overflowing audio offset")
        } catch (error: java.io.IOException) { assertTrue(error.message!!.contains("Invalid transcription")) }
    }

}
