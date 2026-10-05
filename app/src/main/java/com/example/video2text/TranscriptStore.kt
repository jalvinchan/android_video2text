package com.example.video2text

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Local completed results, outside Android backup and separate from unfinished work. */
internal class TranscriptStore(directory: File) {
    private val history = AtomicFile(File(directory, "transcript-history.json"))
    private val legacy = AtomicFile(File(directory, "latest-transcript.json"))
    private val active = AtomicFile(File(directory, "transcription-active"))

    fun load(): Transcript? = loadHistory().firstOrNull()?.transcript

    fun loadHistory(): List<SavedTranscript> {
        if (exists(history)) {
            val json = read(history)
            require(json.getInt("version") == 1) { "Unknown transcript history version." }
            val list = json.getJSONArray("transcripts")
            val entries = List(list.length()) { index ->
                val item = list.getJSONObject(index)
                SavedTranscript(item.getString("id"),
                    if (item.has("completedAtMs")) item.getLong("completedAtMs") else null,
                    decode(item.getJSONObject("transcript")))
            }
            require(entries.map { it.id }.distinct().size == entries.size)
            val jobIds = entries.mapNotNull { it.transcript.jobId }
            require(jobIds.distinct().size == jobIds.size)
            removeLegacy()
            return entries
        }
        if (!exists(legacy)) return emptyList()
        // Old records have no completion date. Retain their saved-file date when available.
        val transcript = decode(read(legacy))
        val savedAt = legacy.baseFile.lastModified().takeIf { it > 0 }
        val entries = listOf(SavedTranscript(UUID.randomUUID().toString(), savedAt, transcript))
        write(entries)
        // Never remove the old record until the entire migrated history is committed.
        removeLegacy()
        return entries
    }

    fun save(transcript: Transcript, cancellation: ProcessingCancellation): List<SavedTranscript> {
        cancellation.check()
        val previous = loadHistory()
        // A repeated successful-job commit must not create a second history entry.
        val existing = previous.firstOrNull { transcript.jobId != null && it.transcript.jobId == transcript.jobId }
        if (existing != null) {
            require(existing.transcript == transcript) { "A completed job already has a different transcript." }
            cancellation.commit { }
            return previous
        }
        val entries = listOf(SavedTranscript(UUID.randomUUID().toString(), System.currentTimeMillis(), transcript)) + previous
        write(entries, cancellation)
        return entries
    }

    fun delete(id: String): List<SavedTranscript> {
        val entries = loadHistory()
        require(entries.any { it.id == id }) { "This saved transcript is no longer available." }
        val remaining = entries.filterNot { it.id == id }
        // Keep an empty committed history so an old record can never be resurrected.
        write(remaining)
        return remaining
    }

    private fun decode(json: JSONObject): Transcript {
        require(json.getInt("version") == 1) { "Unknown saved transcript version." }
        val list = json.getJSONArray("segments")
        val segments = List(list.length()) { index ->
            val item = list.getJSONObject(index)
            TranscriptSegment(item.getLong("startMs"), item.getLong("endMs"), item.getString("text"))
        }
        require(segments.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        return Transcript(json.getString("sourceName"), segments, if (json.has("jobId")) json.getString("jobId") else null)
    }

    private fun encode(transcript: Transcript, cancellation: ProcessingCancellation?): JSONObject {
        require(transcript.segments.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        val segments = JSONArray()
        transcript.segments.forEach {
            cancellation?.check()
            segments.put(JSONObject().put("startMs", it.startMs).put("endMs", it.endMs).put("text", it.text))
        }
        return JSONObject().put("version", 1).put("sourceName", transcript.sourceName)
            .put("jobId", transcript.jobId).put("segments", segments)
    }

    private fun write(entries: List<SavedTranscript>, cancellation: ProcessingCancellation? = null) {
        val list = JSONArray()
        entries.forEach {
            cancellation?.check()
            list.put(JSONObject().put("id", it.id).put("completedAtMs", it.completedAtMs)
                .put("transcript", encode(it.transcript, cancellation)))
        }
        val bytes = JSONObject().put("version", 1).put("transcripts", list).toString().toByteArray(Charsets.UTF_8)
        val output = history.startWrite()
        try {
            output.write(bytes)
            output.fd.sync() // AtomicFile logs sync/rename failures; they must reach the caller.
            val finish = {
                history.finishWrite(output)
                if (!history.openRead().use { it.readBytes().contentEquals(bytes) }) {
                    throw IOException("Transcript history could not be saved. Check free storage and retry.")
                }
            }
            if (cancellation == null) finish() else cancellation.commit(finish)
        } catch (error: Throwable) {
            history.failWrite(output)
            throw error
        }
    }

    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun read(file: AtomicFile) = file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun removeLegacy() {
        legacy.delete()
        check(!exists(legacy)) { "The old saved transcript could not be removed. Check storage and retry." }
    }

    fun wasInterrupted(): Boolean = exists(active)
    fun markActive() {
        val output = active.startWrite()
        try {
            output.write(1)
            active.finishWrite(output)
        } catch (error: Throwable) {
            active.failWrite(output)
            throw error
        }
    }
    fun clearActive() = active.delete()
}
