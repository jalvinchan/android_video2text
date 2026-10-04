package com.example.video2text

import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelRepositoryTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    private open class IsolatedContext(base: Context) : ContextWrapper(base) {
        val suffix = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "model-repository-$suffix").apply { mkdirs() }
        override fun getNoBackupFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$name-$suffix", mode)
        override fun getExternalFilesDir(type: String?): File = File(super.getExternalFilesDir(type), "test-$suffix").apply { mkdirs() }
        fun cleanup() { getSharedPreferences("models", MODE_PRIVATE).edit().clear().commit(); getExternalFilesDir(null).deleteRecursively(); getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS).deleteRecursively(); root.deleteRecursively() }
    }


    private fun detectorFixture(): File = File(app.getExternalFilesDir(null), "verification/$VAD_FILE").also {
        assumeTrue("Supply $VAD_FILE in files/verification for actual-model checks", it.exists())
    }

    private fun baseFixture(): File = File(app.getExternalFilesDir(null), "verification/${SpeechModel.BASE.fileName}").also {
        assumeTrue("Supply Base in files/verification for actual-model checks", it.exists())
    }

    @Test fun freshInstallHasNoModelsAndCannotAcquireBase(): Unit = runBlocking {
        assertTrue(app.assets.list("models").isNullOrEmpty())
        val context = IsolatedContext(app)
        try {
            ModelRepository(context).use { models ->
                models.ready.await()
                assertTrue(models.state.value.installed.isEmpty())
                assertNull(models.state.value.download)
                assertEquals(-1, context.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1))
                models.selectModel(SpeechModel.BASE)
                withTimeout(5000) { models.state.first { it.message?.contains("Download and install Base") == true } }
                try { models.acquire(TranscriptionOptions(), ProcessingCancellation()) {}; fail("Uninstalled Base was acquired") }
                catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("Download and install Base")) }
                assertNull(models.state.value.inUse)
                assertTrue(File(context.root, "models").listFiles()!!.isEmpty())
            }
        } finally { context.cleanup() }
    }

    @Test fun existingBaseIsReusedOfflineAndDeletionRequiresDownloadingAgain(): Unit = runBlocking {
        val context = IsolatedContext(app)
        try {
            val directory = File(context.root, "models").apply { mkdirs() }
            baseFixture().copyTo(File(directory, SpeechModel.BASE.fileName))
            detectorFixture().copyTo(File(directory, VAD_FILE))
            ModelRepository(context).use { models ->
                models.ready.await()
                assertEquals(setOf(SpeechModel.BASE), models.state.value.installed)
                val prepared = models.acquire(TranscriptionOptions(), ProcessingCancellation()) {}
                assertEquals(SpeechModel.BASE.bytes, prepared.model.length())
                assertEquals(VAD_BYTES, prepared.vad.length())
                models.delete(SpeechModel.BASE)
                withTimeout(5000) { models.state.first { it.message?.contains("being used") == true } }
                assertTrue(prepared.model.exists())
                models.release()
                models.delete(SpeechModel.BASE)
                withTimeout(5000) { models.state.first { it.installed.isEmpty() } }
                assertFalse(prepared.model.exists())
            }
            ModelRepository(context).use { models -> models.ready.await(); assertTrue(models.state.value.installed.isEmpty()) }
        } finally { context.cleanup() }
    }

    @Suppress("DEPRECATION")
    @Test fun completedDetectorContinuesBaseDownloadAfterReopening(): Unit = runBlocking {
        val context = IsolatedContext(app)
        val manager = app.getSystemService(DownloadManager::class.java)
        val staging = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), VAD_FILE)
        detectorFixture().copyTo(staging)
        val id = manager.addCompletedDownload("detector fixture", "restart test", false, "application/octet-stream", staging.absolutePath, staging.length(), true)
        try {
            val preferences = context.getSharedPreferences("models", Context.MODE_PRIVATE)
            preferences.edit().putLong("downloadId", id).putString("downloadModel", SpeechModel.BASE.name)
                .putBoolean("downloadDetector", true).putBoolean("mobileData", false).commit()
            ModelRepository(context).use { models ->
                models.ready.await()
                withTimeout(15000) { models.state.first { it.download?.detector == false || it.download?.error != null } }
                assertNull(models.state.value.download?.error)
                assertTrue(models.state.value.installed.isEmpty())
                assertEquals(SpeechModel.BASE, models.state.value.download!!.model)
                assertTrue(preferences.getLong("downloadId", -1) >= 0)
                assertNotEquals(id, preferences.getLong("downloadId", -1))
                val detector = File(context.root, "models/$VAD_FILE")
                assertTrue(detector.inputStream().use { ModelFiles.verify(it, VAD_BYTES, VAD_SHA256, ProcessingCancellation()) })
            }
            ModelRepository(context).use { models ->
                models.ready.await()
                assertFalse(models.state.value.download!!.detector)
                assertFalse(models.state.value.download!!.mobileData)
                models.cancelDownload()
                withTimeout(5000) { models.state.first { it.download == null } }
            }
        } finally {
            val remaining = context.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1)
            if (remaining >= 0) manager.remove(remaining)
            manager.remove(id)
            context.cleanup()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun missingDetectorIsDownloadedWithoutRedownloadingExistingBase(): Unit = runBlocking {
        val context = IsolatedContext(app)
        val manager = app.getSystemService(DownloadManager::class.java)
        try {
            val directory = File(context.root, "models").apply { mkdirs() }
            baseFixture().copyTo(File(directory, SpeechModel.BASE.fileName))
            ModelRepository(context).use { models ->
                models.ready.await()
                assertTrue(models.state.value.installed.isEmpty())
                models.download(SpeechModel.BASE, false)
                withTimeout(5000) { models.state.first { it.download != null } }
                assertTrue(models.state.value.download!!.detector)
                models.cancelDownload()
                withTimeout(5000) { models.state.first { it.download == null } }
            }
            val staging = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), VAD_FILE)
            detectorFixture().copyTo(staging)
            val id = manager.addCompletedDownload("detector fixture", "reuse test", false, "application/octet-stream", staging.absolutePath, staging.length(), true)
            context.getSharedPreferences("models", Context.MODE_PRIVATE).edit().putLong("downloadId", id)
                .putString("downloadModel", SpeechModel.BASE.name).putBoolean("downloadDetector", true).commit()
            ModelRepository(context).use { models ->
                models.ready.await()
                withTimeout(15000) { models.state.first { SpeechModel.BASE in it.installed || it.download?.error != null } }
                assertNull(models.state.value.download)
                assertEquals(setOf(SpeechModel.BASE), models.state.value.installed)
                assertEquals(-1, context.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1))
                val prepared = models.acquire(TranscriptionOptions(), ProcessingCancellation()) {}
                prepared.vad.writeText("damaged detector")
                models.release()
                try { models.acquire(TranscriptionOptions(), ProcessingCancellation()) {}; fail("Damaged detector accepted") }
                catch (error: ProcessingException) { assertTrue(error.message!!.contains("Download")) }
                assertTrue(models.state.value.installed.isEmpty())
                assertNull(models.state.value.inUse)
            }
        } finally {
            val remaining = context.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1)
            if (remaining >= 0) manager.remove(remaining)
            context.cleanup()
        }
    }

    @Test fun preferencesSurviveReopeningAndUninstalledModelsCannotBeSelected(): Unit = runBlocking {
        val context = IsolatedContext(app)
        try {
            ModelRepository(context).use { models ->
                models.ready.await()
                models.selectLanguage(SpeechLanguage.ENGLISH)
                models.selectLanguage(SpeechLanguage.MANDARIN)
                withTimeout(5000) { models.state.first { it.options.language == SpeechLanguage.MANDARIN } }
                models.selectModel(SpeechModel.SMALL)
                withTimeout(5000) { models.state.first { it.message?.contains("Download and install") == true } }
                assertEquals(SpeechModel.BASE, models.state.value.options.model)
            }
            ModelRepository(context).use { models ->
                models.ready.await()
                assertEquals(TranscriptionOptions(SpeechLanguage.MANDARIN), models.state.value.options)
                models.selectLanguage(SpeechLanguage.CANTONESE)
                withTimeout(5000) { models.state.first { it.message?.contains("Cantonese requires") == true } }
                assertEquals(SpeechLanguage.MANDARIN, models.state.value.options.language)
            }
        } finally { context.cleanup() }
    }

    @Test fun wifiPolicyMobileChoiceOneDownloadAndCancellationAreDurable(): Unit = runBlocking {
        val context = IsolatedContext(app)
        try {
            for (mobile in listOf(false, true)) {
                ModelRepository(context).use { models ->
                    models.ready.await()
                    models.download(SpeechModel.BASE, mobile)
                    withTimeout(5000) { models.state.first { it.download != null } }
                    val id = context.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1)
                    assertTrue(id >= 0)
                    app.contentResolver.query(Uri.parse("content://downloads/my_downloads/$id"), arrayOf("allowed_network_types", "allow_roaming"), null, null, null)!!.use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(if (mobile) 3 else 2, cursor.getInt(0))
                        assertEquals(0, cursor.getInt(1))
                    }
                    models.download(SpeechModel.TURBO, false)
                    withTimeout(5000) { models.state.first { it.message?.contains("current download") == true } }
                    assertEquals(id, context.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1))
                    models.cancelDownload()
                    withTimeout(5000) { models.state.first { it.download == null } }
                    assertEquals(-1, context.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1))
                    assertEquals(TranscriptionOptions(), models.state.value.options)
                }
            }
        } finally { context.cleanup() }
    }

    @Suppress("DEPRECATION")
    @Test fun completedCorruptFixtureIsReconciledOnReopeningAndNeverInstalled(): Unit = runBlocking {
        val context = IsolatedContext(app)
        val manager = app.getSystemService(DownloadManager::class.java)
        // DownloadManager requires staging in real app-specific external storage.
        val staging = File(app.getExternalFilesDir(null), "corrupt-${context.suffix}.bin").apply { writeText("not a speech model") }
        val id = manager.addCompletedDownload("model fixture", "corruption test", false, "application/octet-stream", staging.absolutePath, staging.length(), true)
        try {
            context.getSharedPreferences("models", Context.MODE_PRIVATE).edit().putLong("downloadId", id)
                .putString("downloadModel", SpeechModel.SMALL.name).putBoolean("mobileData", false).commit()
            ModelRepository(context).use { models ->
                models.ready.await()
                withTimeout(15000) { models.state.first { it.download?.error != null } }
                assertTrue(models.state.value.download!!.error!!.contains("checksum"))
                assertTrue(models.state.value.installed.isEmpty())
                assertFalse(File(context.root, "models/${SpeechModel.SMALL.fileName}").exists())
                assertFalse(File(context.root, "models/${SpeechModel.SMALL.fileName}.new").exists())
            }
            ModelRepository(context).use { models ->
                models.ready.await()
                assertNotNull(models.state.value.download?.error)
                models.download(SpeechModel.SMALL, false)
                withTimeout(5000) { models.state.first { it.download != null && it.download?.error == null } }
                models.cancelDownload()
                withTimeout(5000) { models.state.first { it.download == null } }
            }
        } finally { manager.remove(id); staging.delete(); context.cleanup() }
    }

    @Test fun removedDownloadIsRecoverableAfterReopening(): Unit = runBlocking {
        val context = IsolatedContext(app)
        try {
            context.getSharedPreferences("models", Context.MODE_PRIVATE).edit().putLong("downloadId", Long.MAX_VALUE)
                .putString("downloadModel", SpeechModel.TURBO.name).commit()
            ModelRepository(context).use { models ->
                models.ready.await()
                withTimeout(5000) { models.state.first { it.download?.error != null } }
                assertTrue(models.state.value.download!!.error!!.contains("interrupted or removed"))
                assertEquals(TranscriptionOptions(), models.state.value.options)
            }
        } finally { context.cleanup() }
    }
    @Test fun deletionRejectsAnInUseModelAndResetsSelectedCantoneseAfterRelease(): Unit = runBlocking {
        val source = File(app.getExternalFilesDir(null), "verification/${SpeechModel.TURBO.fileName}")
        assumeTrue("Supply Turbo in files/verification for the actual-model lease test", source.exists())
        val context = IsolatedContext(app)
        try {
            File(context.root, "models").mkdirs()
            detectorFixture().copyTo(File(context.root, "models/$VAD_FILE"))
            val installed = File(context.root, "models/${SpeechModel.TURBO.fileName}")
            source.inputStream().use { input -> ModelFiles.install(android.util.AtomicFile(installed), input, SpeechModel.TURBO.bytes,
                SpeechModel.TURBO.sha256, ProcessingCancellation()) {} }
            ModelRepository(context).use { models ->
                models.ready.await()
                assertTrue(SpeechModel.TURBO in models.state.value.installed)
                models.selectModel(SpeechModel.TURBO)
                withTimeout(5000) { models.state.first { it.options.model == SpeechModel.TURBO } }
                models.selectLanguage(SpeechLanguage.CANTONESE)
                withTimeout(5000) { models.state.first { it.options.language == SpeechLanguage.CANTONESE } }
                val snapshot = models.state.value.options
                models.acquire(snapshot, ProcessingCancellation()) {}
                models.delete(SpeechModel.TURBO)
                withTimeout(5000) { models.state.first { it.message?.contains("being used") == true } }
                assertTrue(installed.exists())
                models.release()
                models.delete(SpeechModel.TURBO)
                withTimeout(5000) { models.state.first { SpeechModel.TURBO !in it.installed } }
                assertEquals(TranscriptionOptions(), models.state.value.options)
                assertEquals(TranscriptionOptions(SpeechLanguage.CANTONESE, SpeechModel.TURBO), snapshot)
                assertFalse(installed.exists())
            }
            ModelRepository(context).use { models -> models.ready.await(); assertEquals(TranscriptionOptions(), models.state.value.options) }
        } finally { context.cleanup() }
    }

    @Test fun unavailableDownloadManagerAndInaccessibleStagingAreRecoverable(): Unit = runBlocking {
        val unavailableManager = object : IsolatedContext(app) {
            override fun getSystemService(name: String): Any? = if (name == Context.DOWNLOAD_SERVICE) null else super.getSystemService(name)
        }
        val inaccessibleStaging = object : IsolatedContext(app) {
            override fun getExternalFilesDir(type: String?): File = File(root, "staging-file").apply { if (!exists()) writeText("not a directory") }
        }
        for (context in listOf(unavailableManager, inaccessibleStaging)) {
            try {
                ModelRepository(context).use { models ->
                    models.ready.await()
                    models.download(SpeechModel.SMALL, false)
                    withTimeout(5000) { models.state.first { it.message != null } }
                    assertNull(models.state.value.download)
                    assertEquals(TranscriptionOptions(), models.state.value.options)
                    assertEquals(-1, context.getSharedPreferences("models", Context.MODE_PRIVATE).getLong("downloadId", -1))
                }
            } finally { context.cleanup() }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun interruptedVerificationRetriesOnReopeningWithCompletedActualModel(): Unit = runBlocking {
        val source = File(app.getExternalFilesDir(null), "verification/${SpeechModel.SMALL.fileName}")
        assumeTrue("Supply Small in files/verification for interrupted installation", source.exists())
        val context = IsolatedContext(app)
        val manager = app.getSystemService(DownloadManager::class.java)
        val staging = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), SpeechModel.SMALL.fileName)
        source.copyTo(staging)
        val id = manager.addCompletedDownload("verified fixture", "restart test", false, "application/octet-stream", staging.absolutePath, staging.length(), true)
        try {
            val modelDirectory = File(context.root, "models").apply { mkdirs() }
            detectorFixture().copyTo(File(modelDirectory, VAD_FILE))
            // Process death can leave an unfinished AtomicFile write alongside the durable download ID.
            File(modelDirectory, "${SpeechModel.SMALL.fileName}.new").writeText("partial installation")
            context.getSharedPreferences("models", Context.MODE_PRIVATE).edit().putLong("downloadId", id)
                .putString("downloadModel", SpeechModel.SMALL.name).commit()
            ModelRepository(context).use { models ->
                models.ready.await()
                withTimeout(60000) { models.state.first { SpeechModel.SMALL in it.installed || it.download?.error != null } }
                assertNull(models.state.value.download?.error)
                assertTrue(SpeechModel.SMALL in models.state.value.installed)
                val installed = File(modelDirectory, SpeechModel.SMALL.fileName)
                assertTrue(installed.inputStream().use { ModelFiles.verify(it, SpeechModel.SMALL.bytes, SpeechModel.SMALL.sha256, ProcessingCancellation()) })
                assertFalse(File(modelDirectory, "${SpeechModel.SMALL.fileName}.new").exists())
                assertEquals(TranscriptionOptions(), models.state.value.options)
            }
        } finally { manager.remove(id); context.cleanup() }
    }

}
