package com.example.video2text

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Share(val intent: Intent) : UiEvent
}

enum class TranscriptionPage { IMPORT, LANGUAGE, MODEL, PROCESSING, RESULT, MODELS }

class TranscriptionViewModel(application: Application, private val savedState: SavedStateHandle = SavedStateHandle()) : AndroidViewModel(application) {
    private val app get() = getApplication<TranscriptionApplication>()
    private val repository get() = app.repository
    val state = repository.state
    internal val models = app.models.state
    val page = savedState.getStateFlow("page", TranscriptionPage.IMPORT)
    val language = savedState.getStateFlow("language", app.models.state.value.options.language)
    fun navigate(page: TranscriptionPage) { savedState["page"] = page }
    fun selectLanguage(language: SpeechLanguage) {
        savedState["language"] = language
        if (app.models.state.value.options.model.supports(language)) app.models.selectLanguage(language)
    }
    fun selectModel(model: SpeechModel) {
        app.models.selectModel(model)
        if (model.supports(language.value)) app.models.selectLanguage(language.value)
    }
    fun downloadModel(model: SpeechModel, mobileData: Boolean) = app.models.download(model, mobileData)
    fun cancelDownload() = app.models.cancelDownload()
    fun deleteModel(model: SpeechModel) = app.models.delete(model)
    fun refreshModels() = app.models.refresh()
    private val selectedFormat = MutableStateFlow(TranscriptFormat.PLAIN_TEXT)
    val format = selectedFormat.asStateFlow()
    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    internal val events = eventChannel.receiveAsFlow()
    private var pendingSave: Pair<String, String>? = null

    fun selectFormat(format: TranscriptFormat) { selectedFormat.value = format }
    fun report(message: String) = repository.report(message)

    private var importCancellation: ProcessingCancellation? = null

    init {
        viewModelScope.launch {
            repository.ready.await()
            app.models.ready.await()
            if (!savedState.contains("language")) savedState["language"] = app.models.state.value.options.language
        }
    }

    fun importVideo(uri: Uri) {
        if (importCancellation != null) { report("A video import is already running."); return }
        val token = ProcessingCancellation()
        importCancellation = token
        viewModelScope.launch {
            try {
                repository.ready.await()
                app.models.ready.await()
                withContext(Dispatchers.IO) { repository.importVideo(uri, token) }
                navigate(TranscriptionPage.LANGUAGE)
            } catch (error: Exception) {
                if (state.value.isActive && state.value.activeOptions != null) report("A transcription is already running. Stop it before importing another video.")
                else if (state.value.isActive) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { repository.finish(if (token.isCancelled()) ProcessingStage.CANCELLED else ProcessingStage.FAILED, if (token.isCancelled()) "Import cancelled. Select the video again." else error.message ?: "The video could not be imported. Check access and free storage.") }
                else report(error.message ?: "The video could not be imported. Check access and free storage.")
            } finally { importCancellation = null }
        }
    }

    fun start() {
        val pending = state.value.pendingJob ?: return
        val options = pending.options ?: TranscriptionOptions(language.value, app.models.state.value.options.model)
        try {
            options.validate()
            require(!pending.needsModel || options.model in app.models.state.value.installed) { "Download and install ${options.model.label} before starting." }
            ContextCompat.startForegroundService(app, TranscriptionService.startIntent(app, pending.id, options))
            navigate(TranscriptionPage.PROCESSING)
        } catch (error: RuntimeException) { report(error.message ?: "Android could not start processing. Keep the app open and try again.") }
    }

    fun discardJob() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.discardJob() }
                navigate(TranscriptionPage.IMPORT)
            } catch (error: Exception) { report(error.message ?: "The retained media could not be discarded. Try again.") }
        }
    }

    fun stop() {
        importCancellation?.let { it.cancel(); repository.cancelling(); return }
        if (state.value.isActive) {
            try { app.startService(TranscriptionService.cancelIntent(app)) }
            catch (_: RuntimeException) { report("Stopping could not be requested. Reopen the app and try again.") }
        }
    }

    override fun onCleared() { importCancellation?.cancel(); super.onCleared() }

    fun delete() {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repository.delete() } }
            catch (_: Exception) { report("The saved transcript could not be deleted. Try again.") }
        }
    }

    fun copy() {
        val transcript = state.value.transcript ?: return
        val format = selectedFormat.value
        viewModelScope.launch {
            val text = withContext(Dispatchers.Default) { TranscriptFormatter.format(transcript, format) }
            try {
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Transcript", text))
                eventChannel.send(UiEvent.Message("Full transcript copied."))
            } catch (_: RuntimeException) {
                eventChannel.send(UiEvent.Message("This transcript could not be copied. Use Share or Save as .txt for the full text."))
            }
        }
    }

    fun share() {
        val transcript = state.value.transcript ?: return
        val format = selectedFormat.value
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val directory = File(app.cacheDir, "exports/${UUID.randomUUID()}")
                    check(directory.mkdirs())
                    val file = File(directory, TranscriptFormatter.fileName(transcript, format))
                    file.writeText(TranscriptFormatter.format(transcript, format), Charsets.UTF_8)
                    FileProvider.getUriForFile(app, "${app.packageName}.exports", file)
                }
                // A file avoids Binder's text size limit and always shares the full transcript.
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .apply { clipData = ClipData.newRawUri("Transcript", uri) }
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                eventChannel.send(UiEvent.Share(intent))
            } catch (_: Exception) { eventChannel.send(UiEvent.Message("The transcript could not be shared. Check free storage and try again.")) }
        }
    }

    fun prepareSave(): String? {
        val transcript = state.value.transcript ?: return null
        val format = selectedFormat.value
        val filename = TranscriptFormatter.fileName(transcript, format)
        pendingSave = filename to TranscriptFormatter.format(transcript, format)
        return filename
    }

    fun save(uri: Uri?) {
        val snapshot = pendingSave
        pendingSave = null
        if (uri == null) return
        if (snapshot == null) { report("The save was interrupted. Tap Save as .txt again."); return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    (app.contentResolver.openOutputStream(uri, "wt") ?: error("Destination is unavailable."))
                        .bufferedWriter(Charsets.UTF_8).use { it.write(snapshot.second) }
                }
                eventChannel.send(UiEvent.Message("Full transcript saved."))
            } catch (_: Exception) { eventChannel.send(UiEvent.Message("The text file could not be saved. Check destination access and free storage, then try again.")) }
        }
    }
}
