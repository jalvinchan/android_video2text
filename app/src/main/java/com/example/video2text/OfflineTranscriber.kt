package com.example.video2text

import android.content.Context
import android.net.Uri
import java.io.File

internal class OfflineTranscriber(private val context: Context) {
    /** Fixture entry point; production keeps the job store across terminal paths. */
    suspend fun run(uri: Uri, directory: File, cancellation: ProcessingCancellation, options: TranscriptionOptions = TranscriptionOptions(), progress: (ProcessingStage, Int?) -> Unit): Transcript {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "Select a video using the file picker or Android Share menu." }
        options.validate()
        val store = TranscriptionJobStore(directory)
        try {
            val imported = store.import(context, uri, cancellation) { progress(ProcessingStage.PREPARING, it) }
            val job = imported.copy(options = options)
            store.save(job)
            return resume(job, store, cancellation, progress) {}
        } finally { directory.deleteRecursively() }
    }

    suspend fun resume(job: TranscriptionJob, store: TranscriptionJobStore, cancellation: ProcessingCancellation, progress: (ProcessingStage, Int?) -> Unit, checkpointed: (TranscriptionJob) -> Unit): Transcript {
        val options = requireNotNull(job.options)
        options.validate()
        cancellation.check()
        var ready = if (job.hasAudio) job else {
            progress(ProcessingStage.EXTRACTING, 0)
            store.extract(job, cancellation) { progress(ProcessingStage.EXTRACTING, it) }.also(checkpointed)
        }
        cancellation.check()
        val allSegments = mutableListOf<TranscriptSegment>()
        for (index in 0 until ready.completedSections) allSegments += store.readSection(ready, index)
        // A crash after the last checkpoint need not reload the model or repeat inference.
        if (ready.needsModel) {
            val models = (context.applicationContext as TranscriptionApplication).models
            val prepared = models.acquire(options, cancellation) { progress(ProcessingStage.PREPARING, it) }
            try {
                val current = mutableListOf<TranscriptSegment>()
                progress(ProcessingStage.TRANSCRIBING, ready.sections?.let { if (it.isEmpty()) 100 else ready.completedSections * 100 / it.size } ?: 0)
                WhisperBridge.transcribeSections(prepared.model.absolutePath, prepared.vad.absolutePath, store.audio.absolutePath,
                    options.language.code, options.model.name, ready.audioOffsetMs!!, maxOf(1, Runtime.getRuntime().availableProcessors() - 1),
                    ready.sections?.flatMap { listOf(it.startSample, it.endSample) }?.toIntArray(), ready.completedSections,
                    object : WhisperCallbacks {
                        override fun isCancelled() = cancellation.isCancelled()
                        override fun onProgress(percent: Int) { progress(ProcessingStage.TRANSCRIBING, percent) }
                        override fun onPlan(boundaries: IntArray) {
                            cancellation.check()
                            require(boundaries.size % 2 == 0)
                            ready = ready.copy(sections = boundaries.toList().chunked(2).map { AudioSection(it[0], it[1]) })
                            store.save(ready)
                            checkpointed(ready)
                        }
                        override fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray) {
                            val text = utf8.toString(Charsets.UTF_8).trim()
                            if (text.isNotEmpty()) current += TranscriptSegment(startMs, endMs, text)
                        }
                        override fun onSectionComplete(index: Int) {
                            store.checkpoint(ready, index, current, cancellation)
                            allSegments += current
                            current.clear()
                            ready = ready.copy(completedSections = index + 1)
                            checkpointed(ready)
                        }
                    })
                cancellation.check()
            } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { models.release() } }
        }
        cancellation.check()
        require(allSegments.isNotEmpty()) { "No speech was detected. Try a video with clearer speech." }
        return Transcript(ready.sourceName, allSegments.toList(), ready.id)
    }
}
