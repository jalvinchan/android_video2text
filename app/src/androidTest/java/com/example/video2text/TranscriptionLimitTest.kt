package com.example.video2text

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionLimitTest {
    @Test fun metadataAndDecodedAudioLimitsKeepSourceAndProCanResume(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as TranscriptionApplication
        app.models.ready.await()
        val root = File(app.cacheDir, "exports/limit-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "speech.mp4")
            instrumentation.context.assets.open("speech.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val store = TranscriptionJobStore(File(root, "job"))
            val imported = store.import(app, FileProvider.getUriForFile(app, "${app.packageName}.exports", source), ProcessingCancellation()) {}
                .copy(options = TranscriptionOptions(SpeechLanguage.ENGLISH))
            store.save(imported)
            val limited = OfflineTranscriber(app, TranscriptionAccess(1000)) // Short fixture exercises the actual decoder boundary.
            try { limited.resume(imported, store, ProcessingCancellation(), { _, _ -> }) {}; fail("Metadata limit ignored") }
            catch (_: ProcessingException) { }
            assertEquals(imported, store.load())
            val unknownDuration = imported.copy(sourceDurationMs = null)
            store.save(unknownDuration)
            try { limited.resume(unknownDuration, store, ProcessingCancellation(), { _, _ -> }) {}; fail("Unknown duration bypassed decoded limit") }
            catch (error: ProcessingException) { assertTrue(error.message!!.contains("Upgrade to Pro")) }
            val retained = store.load()!!
            assertTrue(store.source.isFile)
            assertFalse(retained.extractionComplete)
            assertEquals(0, retained.completedSections)
            val result = OfflineTranscriber(app, TranscriptionAccess.forPlan(true, true))
                .resume(retained, store, ProcessingCancellation(), { _, _ -> }) {}
            assertTrue(result.segments.isNotEmpty())
            assertTrue(store.load()!!.extractionComplete)
        } finally { root.deleteRecursively() }
    }
}
