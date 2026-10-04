package com.example.video2text

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import androidx.core.util.AtomicFile
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONArray

data class AudioSection(val startSample: Int, val endSample: Int)

data class TranscriptionJob(
    val id: String,
    val sourceName: String,
    val sourceDurationMs: Long?,
    val options: TranscriptionOptions? = null,
    val audioOffsetMs: Long? = null,
    val audioDurationMs: Long? = null,
    val audioBytes: Long? = null,
    val sections: List<AudioSection>? = null,
    val completedSections: Int = 0,
    val sourceHasVideo: Boolean = true, // Records saved before audio import were video-only.
) {
    val hasAudio: Boolean get() = audioBytes != null
    val needsModel: Boolean get() = sections == null || completedSections < sections.size
}

/** One private durable job. Only committed source/audio files are referenced by the record. */
internal class TranscriptionJobStore(root: File) {
    val directory = File(root, "unfinished-transcription")
    private val record = AtomicFile(File(directory, "job.json"))
    // Keep the on-disk filename so existing saved jobs remain resumable.
    val source = File(directory, "input-video")
    val audio = File(directory, "audio.f32")

    fun load(completedJobId: String? = null): TranscriptionJob? {
        if (!record.baseFile.exists() && !File(record.baseFile.path + ".bak").exists()) {
            discard()
            return null
        }
        val json = record.openRead().bufferedReader().use { JSONObject(it.readText()) }
        if (completedJobId != null && json.getString("id") == completedJobId) {
            discard()
            return null
        }
        require(json.getInt("version") == 1) { "Unknown unfinished job version." }
        val options = if (json.has("language")) TranscriptionOptions(
            SpeechLanguage.valueOf(json.getString("language")), SpeechModel.valueOf(json.getString("model"))) else null
        options?.validate()
        // The model hash pins the settings to the exact catalog revision used at Start.
        require(options == null || (json.getString("modelSha256") == options.model.sha256 && json.getString("sectionRevision") == "whisper-1.7.6-sections-v1")) { "The saved model revision has changed. Discard this job and import again." }
        var job = TranscriptionJob(json.getString("id"), json.getString("sourceName"),
            when {
                json.has("sourceDurationMs") -> json.getLong("sourceDurationMs")
                json.has("videoDurationMs") -> json.getLong("videoDurationMs")
                else -> null
            }, options,
            if (json.has("audioBytes")) json.getLong("audioOffsetMs") else null,
            if (json.has("audioBytes")) json.getLong("audioDurationMs") else null,
            if (json.has("audioBytes")) json.getLong("audioBytes") else null,
            if (json.has("sections")) json.getJSONArray("sections").let { list -> List(list.length()) { index ->
                val section = list.getJSONArray(index)
                AudioSection(section.getInt(0), section.getInt(1))
            } } else null, sourceHasVideo = json.optBoolean("sourceHasVideo", true))
        require(job.id.isNotBlank())
        if (job.hasAudio) {
            require(options != null && job.audioOffsetMs!! >= 0 && job.audioDurationMs!! > 0)
            require(audio.isFile && audio.length() == job.audioBytes && audio.length() > 0 && audio.length() % 4 == 0L) { "The retained audio is incomplete. Discard this job and import again." }
            require(audio.length() / 4 * 1000 / 16000 == job.audioDurationMs)
            source.delete() // A crash may have occurred after committing audio but before cleanup.
        } else {
            require(source.isFile && source.length() > 0) { "The retained source file is unavailable. Discard this job and import again." }
            audio.delete() // Uncommitted extraction must never be resumed.
        }
        job.sections?.let { sections ->
            require(job.hasAudio && sections.withIndex().all { (index, section) ->
                section.startSample >= (sections.getOrNull(index - 1)?.endSample ?: 0) &&
                    section.endSample > section.startSample && section.endSample.toLong() <= job.audioBytes!! / 4 &&
                    section.endSample - section.startSample <= 30 * 16000
            }) { "The saved speech section plan is damaged. Discard this job and import again." }
            var completed = 0
            for (index in sections.indices) {
                if (!sectionFile(index).baseFile.exists() && !File(sectionFile(index).baseFile.path + ".bak").exists()) break
                readSection(job, index) // Validate before offering Resume.
                completed++
            }
            job = job.copy(completedSections = completed)
        }
        File(directory, "input-video.part").delete()
        File(directory, "audio.part").delete()
        return job
    }

    fun save(job: TranscriptionJob) {
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create job storage." }
        val json = JSONObject().put("version", 1).put("id", job.id).put("sourceName", job.sourceName)
        json.put("sourceHasVideo", job.sourceHasVideo)
        job.sourceDurationMs?.let { json.put("sourceDurationMs", it) }
        job.options?.let { json.put("language", it.language.name).put("model", it.model.name).put("modelSha256", it.model.sha256).put("sectionRevision", "whisper-1.7.6-sections-v1") }
        if (job.hasAudio) json.put("audioOffsetMs", job.audioOffsetMs).put("audioDurationMs", job.audioDurationMs).put("audioBytes", job.audioBytes)
        job.sections?.let { sections ->
            json.put("sections", JSONArray().also { list -> sections.forEach { list.put(JSONArray().put(it.startSample).put(it.endSample)) } })
        }
        writeAtomic(record, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun import(context: Context, uri: Uri, cancellation: ProcessingCancellation, progress: (Int?) -> Unit): TranscriptionJob {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "Select audio or video using the file picker or Android Share menu." }
        check(load() == null) { "Discard the unfinished job before importing another file." }
        check(directory.mkdirs() || directory.isDirectory)
        var name = "media"
        var size: Long? = null
        val partial = File(directory, "input-video.part")
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex).replace(Regex("[\\r\\n\\t]"), " ").take(200).ifBlank { "media" }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it > 0 }
                }
            }
            size?.let { requireSpace(it) }
            val signal = CancellationSignal()
            val stream = AtomicReference<InputStream?>(null)
            cancellation.withCancellableResource({ try { signal.cancel() } finally { stream.get()?.close() } }) {
                context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
                    descriptor.createInputStream().use { input ->
                        stream.set(input)
                        FileOutputStream(partial).use { file ->
                            val output = file.buffered()
                            val buffer = ByteArray(128 * 1024)
                            var copied = 0L
                            while (true) {
                                cancellation.check()
                                val count = input.read(buffer)
                                if (count < 0) break
                                requireSpace(count.toLong())
                                output.write(buffer, 0, count)
                                copied += count
                                progress(size?.let { (copied.toDouble() / it * 100).toInt().coerceIn(0, 99) })
                            }
                            output.flush()
                            file.fd.sync()
                        }
                    }
                } ?: throw ProcessingException("The selected file is no longer accessible. Select it again.")
            }
            require(partial.length() > 0) { "The selected file is empty." }
            val media = MediaAudioDecoder().inspect(partial, cancellation::check)
            cancellation.check()
            check(partial.renameTo(source)) { "The imported file could not be saved." }
            val job = TranscriptionJob(UUID.randomUUID().toString(), name, media.durationMs, sourceHasVideo = media.hasVideo)
            cancellation.check()
            save(job)
            return job
        } catch (error: Throwable) {
            discard()
            throw error
        } finally {
            try { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Shared files normally have a temporary grant. */ }
        }
    }

    fun extract(job: TranscriptionJob, cancellation: ProcessingCancellation, progress: (Int?) -> Unit): TranscriptionJob {
        if (job.hasAudio) return job
        val partial = File(directory, "audio.part")
        try {
            val decoded = MediaAudioDecoder().decode(source, partial, cancellation::check, progress)
            FileOutputStream(partial, true).use { it.fd.sync() }
            cancellation.check()
            check(partial.renameTo(audio)) { "The extracted audio could not be saved." }
            val ready = job.copy(audioOffsetMs = decoded.offsetMs, audioDurationMs = decoded.durationMs, audioBytes = audio.length())
            save(ready) // Commit metadata only after valid audio is durable. Keep source until this succeeds.
            source.delete()
            return ready
        } finally { partial.delete() }
    }

    private fun sectionFile(index: Int) = AtomicFile(File(directory, "section-$index.json"))

    fun readSection(job: TranscriptionJob, index: Int): List<TranscriptSegment> {
        val json = sectionFile(index).openRead().bufferedReader().use { JSONObject(it.readText()) }
        val section = requireNotNull(job.sections)[index]
        require(json.getString("jobId") == job.id && json.getInt("startSample") == section.startSample && json.getInt("endSample") == section.endSample)
        val items = json.getJSONArray("segments")
        val result = List(items.length()) { i -> items.getJSONObject(i).let { TranscriptSegment(it.getLong("startMs"), it.getLong("endMs"), it.getString("text")) } }
        val start = job.audioOffsetMs!! + section.startSample.toLong() * 1000 / 16000
        val end = job.audioOffsetMs + section.endSample.toLong() * 1000 / 16000
        require(result.all { it.startMs >= start && it.endMs <= end } && result.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        return result
    }

    fun checkpoint(job: TranscriptionJob, index: Int, segments: List<TranscriptSegment>, cancellation: ProcessingCancellation) {
        require(index == job.completedSections)
        val section = requireNotNull(job.sections)[index]
        val json = JSONObject().put("jobId", job.id).put("startSample", section.startSample).put("endSample", section.endSample)
            .put("segments", JSONArray().also { list -> segments.forEach { list.put(JSONObject().put("startMs", it.startMs).put("endMs", it.endMs).put("text", it.text)) } })
        writeAtomic(sectionFile(index), json.toString().toByteArray(Charsets.UTF_8), cancellation)
    }

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray, cancellation: ProcessingCancellation? = null) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync() // Propagate sync failure; AtomicFile itself only logs it.
            cancellation?.check()
            file.finishWrite(output)
            // AtomicFile logs rename failures too; never advance a job after an uncommitted write.
            if (!file.openRead().use { it.readBytes().contentEquals(bytes) }) throw IOException("The job checkpoint could not be saved. Check free storage and retry.")
        } catch (error: Throwable) { file.failWrite(output); throw error }
    }

    fun storageBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0
    fun discard() { check(!directory.exists() || directory.deleteRecursively()) { "The unfinished media could not be removed. Try again." } }
    private fun requireSpace(bytes: Long) {
        if (bytes > directory.usableSpace - 8L * 1024 * 1024) throw ProcessingException("Not enough free storage. Free some space and try again.")
    }
}
