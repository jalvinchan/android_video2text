package com.example.video2text

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val VAD_FILE = "ggml-silero-v5.1.2.bin"
internal const val VAD_BYTES = 885098L
internal const val VAD_SHA256 = "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"
internal const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/$VAD_FILE"

private data class ModelAsset(val fileName: String, val bytes: Long, val sha256: String, val url: String)
private val detectorAsset = ModelAsset(VAD_FILE, VAD_BYTES, VAD_SHA256, VAD_URL)
private val SpeechModel.asset get() = ModelAsset(fileName, bytes, sha256, url)

internal data class ModelDownload(
    val model: SpeechModel,
    val mobileData: Boolean,
    val detector: Boolean = false,
    val percent: Int? = null,
    val status: String = "Waiting for download",
    val error: String? = null,
)

private val ModelDownload.asset get() = if (detector) detectorAsset else model.asset

internal data class ModelState(
    val loaded: Boolean = false,
    val options: TranscriptionOptions = TranscriptionOptions(),
    val installed: Set<SpeechModel> = emptySet(),
    val inUse: SpeechModel? = null,
    val download: ModelDownload? = null,
    val message: String? = null,
)

/** Owns the fixed catalog, verified private files, saved choices and durable download IDs. */
internal class ModelRepository(private val context: Context) : AutoCloseable {
    override fun close() { verification?.cancel(); commands.close(); scope.cancel() }
    private val preferences = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val directory = File(context.noBackupFilesDir, "models")
    private val manager get() = context.getSystemService(DownloadManager::class.java)
    private val mutable = MutableStateFlow(ModelState(options = readOptions(), download = readDownload()))
    val state = mutable.asStateFlow()
    val ready = CompletableDeferred<Unit>()
    @Volatile private var verification: ProcessingCancellation? = null
    @Volatile private var downloadCancellationRequested = false

    init {
        scope.launch {
            lock.withLock {
                try {
                    check(directory.mkdirs() || directory.isDirectory) { "Unable to create model storage." }
                    val installed = installedModels()
                    val options = normalize(readOptions(), installed)
                    saveOptions(options)
                    mutable.update { it.copy(loaded = true, installed = installed, options = options) }
                } catch (error: Exception) {
                    mutable.update { it.copy(loaded = true, message = error.message ?: "Model storage could not be opened. Reopen the app to retry.") }
                } finally { ready.complete(Unit) }
            }
            reconcile()
            while (true) {
                state.first { it.download?.error == null && it.download != null }
                delay(1000)
                if (preferences.getLong("downloadId", -1) >= 0) reconcile()
            }
        }
        // Preserve UI action order even when disk work runs on several IO threads.
        scope.launch {
            ready.await()
            for (command in commands) lock.withLock {
                try { command() }
                catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "Model operation failed. Try again.") } }
            }
        }
    }

    private fun readOptions() = TranscriptionOptions(
        SpeechLanguage.entries.find { it.name == preferences.getString("language", null) } ?: SpeechLanguage.AUTO,
        SpeechModel.entries.find { it.name == preferences.getString("model", null) } ?: SpeechModel.BASE)

    private fun normalize(options: TranscriptionOptions, installed: Set<SpeechModel>): TranscriptionOptions {
        val model = options.model.takeIf { it in installed } ?: SpeechModel.BASE
        return TranscriptionOptions(options.language.takeUnless { it == SpeechLanguage.CANTONESE && !model.supportsCantonese } ?: SpeechLanguage.AUTO, model)
    }

    private fun saveOptions(options: TranscriptionOptions) {
        if (!preferences.edit().putString("language", options.language.name).putString("model", options.model.name).commit()) {
            throw IOException("Language and model settings could not be saved. Try again.")
        }
    }

    fun selectLanguage(language: SpeechLanguage) = action {
        val options = state.value.options.copy(language = language)
        options.validate()
        saveOptions(options)
        mutable.update { it.copy(options = options, message = null) }
    }

    fun selectModel(model: SpeechModel) = action {
        require(model in state.value.installed) { "Download and install ${model.label} before selecting it." }
        val options = normalize(state.value.options.copy(model = model), state.value.installed)
        saveOptions(options)
        mutable.update { it.copy(options = options, message = null) }
    }

    fun refresh() { scope.launch { ready.await(); reconcile() } }

    private fun action(block: suspend () -> Unit) { commands.trySend(block) }

    private fun isInstalled(asset: ModelAsset): Boolean = runCatching {
        AtomicFile(File(directory, asset.fileName)).openRead().use { ModelFiles.verify(it, asset.bytes, asset.sha256, ProcessingCancellation()) }
    }.getOrDefault(false)

    private fun installedModels(): Set<SpeechModel> =
        if (isInstalled(detectorAsset)) SpeechModel.entries.filter { isInstalled(it.asset) }.toSet() else emptySet()

    private fun clearDownload() {
        if (!preferences.edit().remove("downloadId").remove("downloadModel").remove("downloadDetector").remove("downloadError").commit()) {
            throw IOException("The download status could not be saved. Reopen the app and retry.")
        }
    }

    private fun completeDownload(model: SpeechModel) {
        val installed = installedModels()
        // Publish success only after the durable transfer state has been cleared.
        clearDownload()
        mutable.update { it.copy(installed = installed, download = null, message = "${model.label} installed. Select it when needed.") }
    }

    // Retry only missing files, including upgrades that already have a verified speech model.
    private fun nextAsset(model: SpeechModel): ModelAsset? = when {
        !isInstalled(detectorAsset) -> detectorAsset
        !isInstalled(model.asset) -> model.asset
        else -> null
    }

    fun download(model: SpeechModel, mobileData: Boolean) = action {
        check(preferences.getLong("downloadId", -1) < 0) { "Cancel the current download before starting another." }
        check(model !in state.value.installed) { "${model.label} is already installed." }
        downloadCancellationRequested = false
        val asset = nextAsset(model)
        if (asset == null) {
            completeDownload(model)
        } else enqueue(model, mobileData, asset)
    }

    private fun enqueue(model: SpeechModel, mobileData: Boolean, asset: ModelAsset) {
        val downloads = manager ?: throw IOException("Android Download Manager is unavailable. Enable it in Android settings and retry.")
        val staging = stagingFile(asset)
        ModelFiles.requireSpace(staging.parentFile!!, asset.bytes)
        ModelFiles.requireSpace(directory, asset.bytes)
        staging.delete()
        val detector = asset == detectorAsset
        val request = DownloadManager.Request(Uri.parse(asset.url))
            .setTitle("Video to text: ${model.label}")
            .setDescription(if (detector) "Shared speech detector (0.84 MiB)" else "Offline speech model (${model.downloadMiB} MiB)")
            .setDestinationUri(Uri.fromFile(staging))
            .setAllowedNetworkTypes(if (mobileData) DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE else DownloadManager.Request.NETWORK_WIFI)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = downloads.enqueue(request)
        if (!preferences.edit().putLong("downloadId", id).putString("downloadModel", model.name)
                .putBoolean("mobileData", mobileData).putBoolean("downloadDetector", detector).remove("downloadError").commit()) {
            downloads.remove(id)
            throw IOException("The download could not be saved. Retry.")
        }
        mutable.update { it.copy(download = ModelDownload(model, mobileData, detector,
            status = if (detector) "Waiting for speech detector download" else "Waiting for download"), message = null) }
    }

    fun cancelDownload() {
        // Cover cancellation just before reconciliation creates its verification token.
        downloadCancellationRequested = true
        verification?.cancel()
        action {
            val id = preferences.getLong("downloadId", -1)
            if (id >= 0) (manager ?: throw IOException("Android Download Manager is unavailable. Enable it and retry cancellation.")).remove(id)
            state.value.download?.asset?.let { runCatching { stagingFile(it).delete() } }
            clearDownload()
            mutable.update { it.copy(download = null, message = null) }
        }
    }

    fun delete(model: SpeechModel) = action {
        check(state.value.inUse != model) { "${model.label} is being used by the running transcription." }
        val file = File(directory, model.fileName)
        if (file.exists() && !file.delete()) throw IOException("The model could not be deleted. Retry.")
        AtomicFile(file).delete()
        val installed = state.value.installed - model
        val options = normalize(state.value.options, installed)
        mutable.update { it.copy(installed = installed, options = options, message = null) }
        saveOptions(options)
    }

    private fun stagingFile(asset: ModelAsset): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Download storage is unavailable. Reopen the app and retry.")
        check(root.mkdirs() || root.isDirectory) { "Download storage is inaccessible. Retry." }
        return File(root, asset.fileName)
    }

    private fun readDownload(): ModelDownload? {
        val model = SpeechModel.entries.find { it.name == preferences.getString("downloadModel", null) } ?: return null
        val error = preferences.getString("downloadError", null)
        return ModelDownload(model, preferences.getBoolean("mobileData", false), preferences.getBoolean("downloadDetector", false),
            error = error, status = if (error == null) "Checking download" else "Download failed")
    }

    private suspend fun reconcile() = lock.withLock {
        val id = preferences.getLong("downloadId", -1)
        if (id < 0) return@withLock
        val download = readDownload() ?: return@withLock
        val token = ProcessingCancellation()
        verification = token
        try {
            if (downloadCancellationRequested) token.cancel()
            token.check()
            val downloads = manager ?: throw IOException("Android Download Manager is unavailable. Enable it and retry.")
            val query = downloads.query(DownloadManager.Query().setFilterById(id))
                ?: throw IOException("Download status is unavailable. Retry.")
            query.use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("The download was interrupted or removed. Retry.")
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val asset = download.asset
                        val installing = if (download.detector) "Verifying and installing speech detector" else "Verifying and installing"
                        mutable.update { it.copy(download = download.copy(status = installing, percent = 0)) }
                        val model = download.model
                        downloads.openDownloadedFile(id)?.use { descriptor ->
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                                ModelFiles.install(AtomicFile(File(directory, asset.fileName)), input, asset.bytes, asset.sha256, token) { percent ->
                                    mutable.update { it.copy(download = download.copy(status = installing, percent = percent)) }
                                }
                            }
                        } ?: throw IOException("Downloaded file is inaccessible. Retry.")
                        val next = nextAsset(model)
                        token.check()
                        if (next != null) {
                            enqueue(model, download.mobileData, next)
                        } else {
                            completeDownload(model)
                        }
                        runCatching { downloads.remove(id) }
                        runCatching { stagingFile(asset).delete() }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        throw IOException(when (reason) {
                            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free storage for this model. Free space and retry."
                            DownloadManager.ERROR_DEVICE_NOT_FOUND, DownloadManager.ERROR_FILE_ERROR -> "Download storage is inaccessible. Retry."
                            else -> "Model download failed (Android code $reason). Check your connection and retry."
                        })
                    }
                    else -> {
                        val waiting = status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
                        mutable.update { it.copy(download = download.copy(percent = (bytes * 100 / download.asset.bytes).toInt().coerceIn(0, 100),
                            status = if (waiting) "Waiting for ${if (download.mobileData) "network" else "Wi-Fi"}${if (download.detector) " (speech detector)" else ""}"
                                else if (download.detector) "Downloading speech detector" else "Downloading")) }
                    }
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Cancel has already queued durable DownloadManager removal after this lock is released.
        } catch (error: Exception) {
            runCatching { manager?.remove(id) }
            runCatching { stagingFile(download.asset).delete() }
            val message = error.message ?: "Model download failed. Retry."
            preferences.edit().remove("downloadId").putString("downloadError", message).commit()
            mutable.update { it.copy(download = download.copy(status = "Download failed", error = message)) }
        } finally { verification = null }
    }

    internal data class PreparedModels(val model: File, val vad: File)

    suspend fun acquire(options: TranscriptionOptions, cancellation: ProcessingCancellation, progress: (Int) -> Unit): PreparedModels {
        ready.await()
        return lock.withLock {
            options.validate()
            require(options.model in state.value.installed) { "Download and install ${options.model.label} before transcribing." }
            check(state.value.inUse == null) { "A transcription is already using an offline model." }
            mutable.update { it.copy(inUse = options.model) }
            try {
                val model = prepare(options.model.asset, cancellation) { progress(it * 99 / 100) }
                val vad = prepare(detectorAsset, cancellation) { progress(99 + it / 100) }
                if (options.model == SpeechModel.BASE) AtomicFile(File(directory, "ggml-base.en.bin")).delete()
                PreparedModels(model, vad)
            } catch (error: Throwable) {
                mutable.update { it.copy(inUse = null) }
                throw error
            }
        }
    }

    suspend fun release() = lock.withLock { mutable.update { it.copy(inUse = null) } }

    private fun prepare(asset: ModelAsset, cancellation: ProcessingCancellation, progress: (Int) -> Unit): File {
        val atomic = AtomicFile(File(directory, asset.fileName))
        val valid = try { atomic.openRead().use { ModelFiles.verify(it, asset.bytes, asset.sha256, cancellation) } }
            catch (_: IOException) { false }
        if (valid) { progress(100); return atomic.baseFile }
        val model = SpeechModel.entries.find { it.fileName == asset.fileName }
        val installed = if (model == null) emptySet() else state.value.installed - model
        val options = normalize(state.value.options, installed)
        mutable.update { it.copy(installed = installed, options = options) }
        saveOptions(options)
        throw ProcessingException("The ${model?.label ?: "speech detector"} model is missing or damaged. Download ${model?.label ?: "a speech model"} again from Models.")
    }
}

/** Shared atomic install boundary: untrusted bytes never replace an installed model. */
internal object ModelFiles {
    fun requireSpace(directory: File, bytes: Long) {
        if (directory.usableSpace - 8L * 1024 * 1024 < bytes) throw IOException("Not enough free storage for this model. Free space and retry.")
    }

    fun verify(input: InputStream, bytes: Long, hash: String, cancellation: ProcessingCancellation): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            cancellation.check()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > bytes) return false
            digest.update(buffer, 0, count)
        }
        return total == bytes && digest.digest().hex() == hash
    }

    fun install(file: AtomicFile, input: InputStream, bytes: Long, hash: String, cancellation: ProcessingCancellation, progress: (Int) -> Unit) {
        requireSpace(file.baseFile.parentFile!!, bytes)
        val output = file.startWrite()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                cancellation.check()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > bytes) throw IOException("Model size or checksum did not match. Retry the download.")
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                progress((total * 100 / bytes).toInt())
            }
            if (total != bytes || digest.digest().hex() != hash) throw IOException("Model size or checksum did not match. Retry the download.")
            synchronized(cancellation) { cancellation.check(); file.finishWrite(output) }
        } catch (error: Throwable) { file.failWrite(output); throw error }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
