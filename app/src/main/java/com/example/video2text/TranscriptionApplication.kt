package com.example.video2text

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class ProcessingStage { IDLE, PREPARING, EXTRACTING, TRANSCRIBING, COMPLETED, CANCELLED, FAILED }

data class TranscriptionState(
    val loaded: Boolean = false,
    val transcript: Transcript? = null,
    val stage: ProcessingStage = ProcessingStage.IDLE,
    val percent: Int? = null,
    val cancelling: Boolean = false,
    val message: String? = null,
    internal val pendingJob: TranscriptionJob? = null,
    val retainedBytes: Long = 0,
    val activeOptions: TranscriptionOptions? = null,
) {
    val isActive: Boolean get() = stage in listOf(ProcessingStage.PREPARING, ProcessingStage.EXTRACTING, ProcessingStage.TRANSCRIBING)
}

class TranscriptionApplication : Application() {
    internal lateinit var repository: TranscriptionRepository
        private set
    internal lateinit var models: ModelRepository
        private set
    override fun onCreate() {
        super.onCreate()
        models = ModelRepository(this)
        repository = TranscriptionRepository(this)
    }
}

internal class TranscriptionRepository(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(TranscriptionState())
    val state = mutable.asStateFlow()
    val ready = CompletableDeferred<Unit>()
    private val store = TranscriptStore(app.noBackupFilesDir)
    private val storage = Mutex()
    val workDirectory = File(app.cacheDir, "transcription") // Legacy cache cleanup only.
    val jobs = TranscriptionJobStore(app.noBackupFilesDir)

    init {
        scope.launch {
            var message: String? = null
            val previous = try { store.load() } catch (_: Exception) {
                message = "The saved transcript could not be opened. You can import the video again."
                null
            }
            var pending: TranscriptionJob? = null
            try {
                pending = jobs.load(previous?.jobId)
                if (pending != null) {
                    message = if (pending.options == null) "Your imported video is ready. Continue setup or discard it."
                        else "An unfinished transcription is saved. Resume or discard it. Your last successful result is unchanged."
                } else if (store.wasInterrupted() && previous?.jobId == null) message = "The import was interrupted. Select the video again. Your last saved result is unchanged."
            } catch (_: Exception) {
                message = "The unfinished job could not be opened. Discard retained media before importing again. Your last saved result is unchanged."
            }
            workDirectory.deleteRecursively()
            File(app.cacheDir, "exports").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }
                ?.forEach { it.deleteRecursively() }
            app.contentResolver.persistedUriPermissions.forEach {
                try { app.contentResolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { /* A provider may have already revoked its grant. */ }
            }
            store.clearActive()
            mutable.value = TranscriptionState(loaded = true, transcript = previous, pendingJob = pending, retainedBytes = jobs.storageBytes(), message = message)
            ready.complete(Unit)
        }
    }

    suspend fun importVideo(uri: android.net.Uri, token: ProcessingCancellation) = storage.withLock {
        check(!state.value.isActive && state.value.pendingJob == null && state.value.retainedBytes == 0L) { "Discard the unfinished job before importing another video." }
        begin()
        store.markActive()
        val pending = jobs.import(app, uri, token) { progress(ProcessingStage.PREPARING, it) }
        store.clearActive()
        mutable.update { it.copy(stage = ProcessingStage.IDLE, pendingJob = pending, retainedBytes = jobs.storageBytes(), activeOptions = null, percent = null) }
    }

    suspend fun startJob(id: String, options: TranscriptionOptions?): TranscriptionJob = storage.withLock {
        check(!state.value.isActive) { "A transcription is already running." }
        val pending = jobs.load() ?: throw ProcessingException("Import a video first.")
        require(pending.id == id) { "The selected job has changed. Reopen it and try again." }
        val fixed = pending.options ?: requireNotNull(options).also { it.validate() }
        require(options == null || pending.options == null || options == pending.options) { "Resume uses the original settings. Discard this job to change them." }
        val job = pending.copy(options = fixed)
        jobs.save(job)
        store.markActive()
        begin(fixed)
        jobUpdated(job)
        job
    }

    fun jobUpdated(job: TranscriptionJob) {
        mutable.update { it.copy(pendingJob = job, retainedBytes = jobs.storageBytes()) }
    }

    suspend fun discardJob() = storage.withLock {
        check(!state.value.isActive) { "Cancel processing before discarding this job." }
        jobs.discard()
        store.clearActive()
        mutable.update { it.copy(pendingJob = null, retainedBytes = 0, stage = ProcessingStage.IDLE, message = null, activeOptions = null) }
    }

    fun begin(options: TranscriptionOptions? = null) { mutable.update { it.copy(activeOptions = options, stage = ProcessingStage.PREPARING, percent = null, cancelling = false, message = null) } }
    fun progress(stage: ProcessingStage, percent: Int?) {
        mutable.update { it.copy(stage = stage, percent = percent?.coerceIn(0, 100)) }
    }
    fun cancelling() { mutable.update { it.copy(cancelling = true) } }
    suspend fun complete(transcript: Transcript, cancellation: ProcessingCancellation) = storage.withLock {
        store.save(transcript, cancellation)
        store.clearActive()
        if (transcript.jobId != null && transcript.jobId == state.value.pendingJob?.id) {
            // Cleanup failure cannot turn a successfully committed transcript into a failed job.
            runCatching { jobs.discard() }
        }
        mutable.update { it.copy(pendingJob = null, retainedBytes = jobs.storageBytes(), transcript = transcript, stage = ProcessingStage.COMPLETED, percent = 100, cancelling = false, message = null) }
    }
    suspend fun finish(stage: ProcessingStage, message: String?) = storage.withLock {
        store.clearActive()
        val pending = runCatching { jobs.load() }.getOrNull()
        mutable.update { it.copy(pendingJob = pending, retainedBytes = jobs.storageBytes(), stage = stage, percent = null, cancelling = false, message = message) }
    }
    fun report(message: String) {
        scope.launch {
            ready.await()
            mutable.update { it.copy(message = message) }
        }
    }
    suspend fun delete() = storage.withLock {
        if (state.value.isActive) return@withLock
        store.delete()
        mutable.update { it.copy(transcript = null, stage = ProcessingStage.IDLE, percent = null, message = null) }
    }
}
