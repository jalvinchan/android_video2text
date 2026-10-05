package com.example.video2text

import android.content.Context
import android.net.Uri
import java.io.File

internal class OfflineTranscriber(private val context: Context) {
    /** Fixture entry point; production keeps the job store across terminal paths. */
    suspend fun run(uri: Uri, directory: File, cancellation: ProcessingCancellation, options: TranscriptionOptions = TranscriptionOptions(), progress: (ProcessingStage, Int?) -> Unit): Transcript {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "Select audio or video using the file picker or Android Share menu." }
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
        // Retained full-PCM jobs keep their original plan/revision. New jobs use windows.
        if (job.progressive || !job.hasAudio) return resumeProgressive(job, store, cancellation, progress, checkpointed)
        var ready = job
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
        require(allSegments.isNotEmpty()) { "No speech was detected. Try a file with clearer speech." }
        return Transcript(ready.sourceName, allSegments.toList(), ready.id)
    }

    private suspend fun resumeProgressive(job: TranscriptionJob, store: TranscriptionJobStore, cancellation: ProcessingCancellation, progress: (ProcessingStage, Int?) -> Unit, checkpointed: (TranscriptionJob) -> Unit): Transcript {
        val options = requireNotNull(job.options)
        var ready = if (job.progressive) job else job.copy(progressive = true, sections = emptyList()).also {
            store.save(it)
            checkpointed(it)
        }
        val allSegments = mutableListOf<TranscriptSegment>()
        for (index in 0 until ready.completedSections) allSegments += store.readSection(ready, index)
        if (ready.needsModel) {
            val models = (context.applicationContext as TranscriptionApplication).models
            val prepared = models.acquire(options, cancellation) { progress(ProcessingStage.PREPARING, it) }
            var session: WhisperBridge.Session? = null
            try {
                fun processWindow() {
                    cancellation.check()
                    val window = requireNotNull(ready.window)
                    val sections = requireNotNull(ready.sections)
                    val base = sections.indexOfFirst { it.startSample >= window.coreStartSample }.let { if (it < 0) sections.size else it }
                    if (!window.planned || ready.completedSections < sections.size) {
                        val current = mutableListOf<TranscriptSegment>()
                        fun report(percent: Int) {
                            val timeMs = ready.audioOffsetMs!! + (window.startSample.toLong() + window.samples.toLong() * percent / 100) * 1000 / PCM_RATE
                            progress(ProcessingStage.TRANSCRIBING, ready.sourceDurationMs?.takeIf { it > 0 }?.let { (timeMs * 100 / it).toInt().coerceIn(0, 99) })
                        }
                        report(0)
                        cancellation.check()
                        val engine = session ?: WhisperBridge.Session(prepared.model.absolutePath).also { session = it }
                        engine.transcribe(prepared.vad.absolutePath, store.windowAudio.absolutePath,
                            options.language.code, options.model.name, ready.audioOffsetMs!! + window.startSample.toLong() * 1000 / PCM_RATE,
                            maxOf(1, Runtime.getRuntime().availableProcessors() - 1),
                            window.coreStartSample - window.startSample, window.coreEndSample - window.startSample,
                            if (window.planned) sections.drop(base).flatMap { listOf(it.startSample - window.startSample, it.endSample - window.startSample) }.toIntArray() else null,
                            ready.completedSections - base,
                            object : WhisperCallbacks {
                                override fun isCancelled() = cancellation.isCancelled()
                                override fun onProgress(percent: Int) = report(percent)
                                override fun onPlan(boundaries: IntArray) {
                                    cancellation.check()
                                    require(boundaries.size % 2 == 0)
                                    val added = boundaries.toList().chunked(2).map { AudioSection(it[0] + window.startSample, it[1] + window.startSample) }
                                    require(added.withIndex().all { (i, section) -> section.startSample >= (added.getOrNull(i - 1)?.endSample ?: window.coreStartSample) && section.endSample > section.startSample && section.endSample <= window.coreEndSample })
                                    val planned = ready.copy(sections = sections + added, window = window.copy(planned = true))
                                    store.save(planned) // The window's plan is durable before its first inference.
                                    ready = planned
                                    checkpointed(ready)
                                }
                                override fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray) {
                                    val text = utf8.toString(Charsets.UTF_8).trim()
                                    if (text.isNotEmpty()) current += TranscriptSegment(startMs, endMs, text)
                                }
                                override fun onSectionComplete(index: Int) {
                                    val globalIndex = base + index
                                    store.checkpoint(ready, globalIndex, current, cancellation)
                                    allSegments += current
                                    current.clear()
                                    ready = ready.copy(completedSections = globalIndex + 1)
                                    checkpointed(ready)
                                }
                            })
                    }
                    cancellation.check()
                    ready = store.finishWindow(ready)
                    checkpointed(ready)
                }
                // Reuse committed PCM before restarting the decoder. An unfinished section repeats.
                if (ready.window != null) processWindow()
                if (!ready.extractionComplete) {
                    progress(ProcessingStage.EXTRACTING, null)
                    var offsetMs = 0L
                    val windows = PcmWindows { start, coreStart, coreEnd, samples ->
                        cancellation.check()
                        if (coreEnd > ready.processedSamples) {
                            require(coreStart == ready.processedSamples) { "The source audio changed. Discard this job and import again." }
                            ready = store.retainWindow(ready, start, coreStart, coreEnd, samples, offsetMs, cancellation)
                            checkpointed(ready)
                            processWindow()
                            progress(ProcessingStage.EXTRACTING, null)
                        }
                    }
                    val timeline = MediaAudioDecoder().decodeSamples(store.source, cancellation::check,
                        { progress(ProcessingStage.EXTRACTING, it?.coerceAtMost(99)) }) { sample, offset ->
                        offsetMs = offset
                        windows.push(sample)
                    }
                    windows.finish()
                    cancellation.check()
                    ready = store.finishDecoding(ready, timeline)
                    checkpointed(ready)
                }
            } finally {
                try { session?.close() }
                finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { models.release() } }
            }
            progress(ProcessingStage.TRANSCRIBING, 100)
        }
        cancellation.check()
        require(allSegments.isNotEmpty()) { "No speech was detected. Try a file with clearer speech." }
        return Transcript(ready.sourceName, allSegments.toList(), ready.id)
    }
}
