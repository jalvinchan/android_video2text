package com.example.video2text

import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** The only durable result is the latest successful transcript, outside Android backup. */
internal class TranscriptStore(directory: File) {
    private val latest = AtomicFile(File(directory, "latest-transcript.json"))
    private val active = AtomicFile(File(directory, "transcription-active"))

    fun load(): Transcript? {
        if (!latest.baseFile.exists() && !File(latest.baseFile.path + ".bak").exists()) return null
        val json = latest.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        require(json.getInt("version") == 1) { "Unknown saved transcript version." }
        val list = json.getJSONArray("segments")
        val segments = List(list.length()) { index ->
            val item = list.getJSONObject(index)
            TranscriptSegment(item.getLong("startMs"), item.getLong("endMs"), item.getString("text"))
        }
        require(segments.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        return Transcript(json.getString("sourceName"), segments, if (json.has("jobId")) json.getString("jobId") else null)
    }

    fun save(transcript: Transcript, cancellation: ProcessingCancellation) {
        val segments = JSONArray()
        transcript.segments.forEach {
            cancellation.check()
            segments.put(JSONObject().put("startMs", it.startMs).put("endMs", it.endMs).put("text", it.text))
        }
        val bytes = JSONObject().put("version", 1).put("sourceName", transcript.sourceName)
            .put("jobId", transcript.jobId).put("segments", segments).toString().toByteArray(Charsets.UTF_8)
        val output = latest.startWrite()
        try {
            output.write(bytes)
            // Cancellation and the successful commit have one unambiguous ordering.
            cancellation.commit { latest.finishWrite(output) }
        } catch (error: Throwable) {
            latest.failWrite(output)
            throw error
        }
    }

    fun delete() = latest.delete()
    fun wasInterrupted(): Boolean = active.baseFile.exists() || File(active.baseFile.path + ".bak").exists()
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
