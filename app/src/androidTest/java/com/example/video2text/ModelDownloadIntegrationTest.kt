package com.example.video2text

import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit harness phases exercise a real DownloadManager download across target process death. */
@RunWith(AndroidJUnit4::class)
class ModelDownloadIntegrationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TranscriptionApplication
    private val original = Transcript("before-download.mp4", listOf(TranscriptSegment(0, 1000, "你好，廣東話。")))
    private val phase get() = InstrumentationRegistry.getArguments().getString("modelDownloadPhase")

    @Test fun freshBaseAndDetectorDownloadAndAcquire(): Unit = runBlocking {
        assumeTrue("Requires the explicit online base phase", phase == "base")
        val suffix = UUID.randomUUID().toString()
        val root = File(app.cacheDir, "base-download-$suffix").apply { mkdirs() }
        val preferenceName = "base-download-$suffix"
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = app.getSharedPreferences(preferenceName, mode)
            override fun getExternalFilesDir(type: String?): File = File(app.getExternalFilesDir(type), "base-download-$suffix").apply { mkdirs() }
        }
        try {
            ModelRepository(context).use { models ->
                models.ready.await()
                assertTrue(models.state.value.installed.isEmpty())
                models.download(SpeechModel.BASE, false)
                withTimeout(240000) { models.state.first { SpeechModel.BASE in it.installed || it.download?.error != null || it.message != null } }
                assertNull(models.state.value.download?.error)
                assertEquals(setOf(SpeechModel.BASE), models.state.value.installed)
                assertEquals(TranscriptionOptions(), models.state.value.options)
                val prepared = models.acquire(TranscriptionOptions(), ProcessingCancellation()) {}
                assertEquals(SpeechModel.BASE.bytes, prepared.model.length())
                assertEquals(VAD_BYTES, prepared.vad.length())
                models.release()
            }
        } finally {
            val id = context.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1)
            if (id >= 0) app.getSystemService(DownloadManager::class.java).remove(id)
            context.getSharedPreferences("models", Context.MODE_PRIVATE).edit().clear().commit()
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test fun startSmallDownload(): Unit = runBlocking {
        assumeTrue("This online check requires the explicit start phase", phase == "start")
        app.models.ready.await()
        assertFalse(SpeechModel.SMALL in app.models.state.value.installed)
        app.repository.ready.await()
        withContext(Dispatchers.IO) { app.repository.complete(original, ProcessingCancellation()) }
        val before = app.models.state.value.options
        app.models.download(SpeechModel.SMALL, false)
        withTimeout(15000) { app.models.state.first { it.download?.model == SpeechModel.SMALL } }
        assertTrue(app.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1) >= 0)
        assertEquals(before, app.models.state.value.options)
        assertEquals(original, app.repository.state.value.transcript)
    }

    @Test fun reopeningInstallsVerifiedDownloadAndDoesNotSelectIt(): Unit = runBlocking {
        assumeTrue("This recovery check requires the explicit resume phase", phase == "resume")
        app.models.ready.await()
        withTimeout(240000) { app.models.state.first { SpeechModel.SMALL in it.installed || it.download?.error != null } }
        assertNull(app.models.state.value.download?.error)
        assertTrue(SpeechModel.SMALL in app.models.state.value.installed)
        val file = File(app.noBackupFilesDir, "models/${SpeechModel.SMALL.fileName}")
        assertTrue(file.inputStream().use { ModelFiles.verify(it, SpeechModel.SMALL.bytes, SpeechModel.SMALL.sha256, ProcessingCancellation()) })
        assertEquals(SpeechModel.BASE, app.models.state.value.options.model)
        app.repository.ready.await()
        assertEquals(original, app.repository.state.value.transcript)
        assertEquals(original, TranscriptStore.forApp(app).load())
        assertEquals(-1, app.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1))
    }
    @Test fun networkFailureLeavesModelsSettingsAndTranscriptIntact(): Unit = runBlocking {
        assumeTrue("This online check requires the explicit failure phase", phase == "failure")
        app.repository.ready.await()
        if (app.repository.state.value.transcript == null) withContext(Dispatchers.IO) { app.repository.complete(original, ProcessingCancellation()) }
        val previous = app.repository.state.value.transcript
        val suffix = UUID.randomUUID().toString()
        val root = File(app.cacheDir, "network-failure-$suffix").apply { mkdirs() }
        val preferenceName = "network-failure-$suffix"
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = app.getSharedPreferences(preferenceName, mode)
        }
        val staging = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "failure-$suffix.bin")
        val manager = app.getSystemService(DownloadManager::class.java)
        val id = manager.enqueue(DownloadManager.Request(Uri.parse("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/does-not-exist-$suffix.bin"))
            .setDestinationUri(Uri.fromFile(staging)).setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED))
        try {
            context.getSharedPreferences("models", Context.MODE_PRIVATE).edit().putLong("downloadId", id)
                .putString("downloadModel", SpeechModel.SMALL.name).commit()
            ModelRepository(context).use { models ->
                models.ready.await()
                withTimeout(60000) { models.state.first { it.download?.error != null } }
                assertTrue(models.state.value.installed.isEmpty())
                assertEquals(TranscriptionOptions(), models.state.value.options)
                assertEquals(previous, app.repository.state.value.transcript)
                assertFalse(File(root, "models/${SpeechModel.SMALL.fileName}").exists())
            }
        } finally { manager.remove(id); staging.delete(); root.deleteRecursively(); app.deleteSharedPreferences(preferenceName) }
    }

}
