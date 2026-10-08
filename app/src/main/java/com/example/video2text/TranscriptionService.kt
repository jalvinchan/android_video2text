package com.example.video2text

import android.app.Notification
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as TranscriptionApplication).repository
    private var job: Job? = null
    private var cancellation: ProcessingCancellation? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timeoutMessage: String? = null
    private var pauseMessage: String? = null
    private var lastNotification = 0L
    private var lastStage: ProcessingStage? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Offline transcription", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress and pause controls for offline transcription"
                })
        }
    }

    // The foreground service owns this lock and releases it on every terminal path and onDestroy.
    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            requestCancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) {
            repository.report("A transcription is already running. Stop it before importing another file.")
            return START_NOT_STICKY
        }
        val jobId = intent?.getStringExtra("jobId")
        if (intent?.action != START || jobId.isNullOrBlank()) {
            repository.report("Import audio or video and complete setup before starting transcription.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = notification(ProcessingStage.PREPARING, null)
            when {
                Build.VERSION.SDK_INT >= 35 -> startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT >= 34 -> startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= 29 -> startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(NOTIFICATION, notification)
            }
        } catch (_: RuntimeException) {
            repository.report("Android could not start background processing. Keep the app open and select the file again.")
            stopSelf()
            return START_NOT_STICKY
        }
        val options = try {
            TranscriptionOptions(
                SpeechLanguage.valueOf(intent.getStringExtra("language") ?: throw IllegalArgumentException("Missing language.")),
                SpeechModel.valueOf(intent.getStringExtra("model") ?: throw IllegalArgumentException("Missing model.")))
                .also { it.validate() }
        } catch (_: IllegalArgumentException) {
            repository.report("Invalid transcription settings. Select a language and installed model, then retry.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val token = ProcessingCancellation()
        cancellation = token
        timeoutMessage = null
        pauseMessage = null
        val app = application as TranscriptionApplication
        val access = app.monetization.state.value.access
        job = scope.launch {
            val foregroundMonitor = launch {
                access.monitorForeground(app.foreground.state) {
                    requestCancel(TranscriptionAccess.BACKGROUND_PAUSE_MESSAGE)
                }
            }
            try {
                repository.ready.await()
                withContext(Dispatchers.IO) {
                    (application as TranscriptionApplication).models.ready.await()
                    val savedJob = repository.startJob(jobId, options, access)
                    require(!savedJob.needsModel || options.model in (application as TranscriptionApplication).models.state.value.installed) { "Download and install ${options.model.label} before starting." }
                    token.check()
                    wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "video2text:transcription").apply { acquire() }
                    val transcript = OfflineTranscriber(this@TranscriptionService, access).resume(savedJob, repository.jobs, token, { stage, percent ->
                        repository.progress(stage, percent)
                        updateNotification(stage, percent)
                    }, repository::jobUpdated)
                    token.check()
                    repository.complete(transcript, token)
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!token.isCommitted()) {
                        repository.finish(if (timeoutMessage == null) ProcessingStage.CANCELLED else ProcessingStage.FAILED,
                            timeoutMessage ?: pauseMessage ?: STOPPED_MESSAGE)
                    }
                }
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finish(if (token.isCancelled() && timeoutMessage == null) ProcessingStage.CANCELLED else ProcessingStage.FAILED,
                        timeoutMessage ?: if (token.isCancelled()) pauseMessage ?: STOPPED_MESSAGE else failureMessage(error))
                }
            } catch (_: OutOfMemoryError) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finish(ProcessingStage.FAILED, "Not enough memory to transcribe this file. Close other apps or try a shorter file.")
                }
            } catch (_: LinkageError) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finish(ProcessingStage.FAILED, "The offline engine could not load. Reinstall this app for an ARM64 or x86_64 device.")
                }
            } finally {
                foregroundMonitor.cancel()
                withContext(NonCancellable + Dispatchers.IO) {
                    releaseWakeLock()
                }
                cancellation = null
                job = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun requestCancel(message: String? = null) {
        if (cancellation?.cancel() == true) {
            pauseMessage = message
            repository.cancelling()
            updateNotification(repository.state.value.stage, repository.state.value.percent, force = true)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timeoutMessage = "Android's background processing time limit was reached. Resume the retained job when ready. Your last saved result is unchanged."
        requestCancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancellation?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    @Synchronized private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    @Synchronized private fun updateNotification(stage: ProcessingStage, percent: Int?, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastStage == stage && now - lastNotification < 500) return
        lastStage = stage
        lastNotification = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(stage, percent))
        } catch (_: SecurityException) { /* Notification denial must not stop foreground work. */ }
    }

    private fun notification(stage: ProcessingStage, percent: Int?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, TranscriptionService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val state = repository.state.value
        val cancelling = state.cancelling
        val status = if (cancelling) state.stoppingLabel() else stage.label()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_transcription)
            .setContentTitle("Video to text")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (state.isActive) "$status\n${state.workVisibilityExplanation()}\n${state.stopExplanation()}" else status))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null || cancelling)
            .apply { if (state.isActive && !cancelling) addAction(0, state.stopActionLabel(), cancel) }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun failureMessage(error: Exception): String = when (error) {
        is ProcessingException -> error.message!!
        is SecurityException, is FileNotFoundException -> "The selected file is no longer accessible. Select it again and allow access."
        is MediaCodec.CodecException -> "This phone cannot decode this file's audio codec. Try an AAC or MP3 audio file."
        is IllegalArgumentException -> error.message?.takeIf { it.endsWith('.') } ?: "The file is damaged or uses an unsupported media format. Try another file."
        is IOException -> if (error.message?.contains("space", ignoreCase = true) == true) "Not enough free storage. Free some space and select the file again."
            else error.message?.takeIf { it.startsWith("Offline transcription") || it.startsWith("Unable to load the offline") || it.startsWith("Not enough memory") }
                ?: "The file could not be read or saved. Check access and free storage, then try again."
        else -> "The file could not be transcribed. Try another file or restart the app."
    }

    companion object {
        private const val CHANNEL = "transcription"
        private const val NOTIFICATION = 1
        private const val START = "com.example.video2text.TRANSCRIBE"
        private const val CANCEL = "com.example.video2text.CANCEL"
        private const val STOPPED_MESSAGE = "Processing stopped. Resume or discard the retained job. Your last saved result is unchanged."
        fun startIntent(context: Context, jobId: String, options: TranscriptionOptions) = Intent(context, TranscriptionService::class.java)
            .setAction(START).putExtra("jobId", jobId).putExtra("language", options.language.name).putExtra("model", options.model.name)
        fun cancelIntent(context: Context) = Intent(context, TranscriptionService::class.java).setAction(CANCEL)
    }
}
