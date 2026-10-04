package com.example.video2text

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit harness phases with `am force-stop` between invocations. No network or phone benchmark. */
@RunWith(AndroidJUnit4::class)
class TranscriptionProcessRecoveryTest {
    @Test fun recoveryAcrossTargetProcessDeath(): Unit = runBlocking(Dispatchers.IO) {
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        assumeTrue("Requires explicit start/resume/committed/reopen phases", phase in listOf("start", "resume", "committed", "reopen"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as TranscriptionApplication
        val repository = app.repository
        repository.ready.await()
        val previous = Transcript("before-process-death", listOf(TranscriptSegment(0, 1000, "Preserve me.")))
        when (phase) {
            "start", "committed" -> {
                repository.discardJob()
                repository.complete(previous, ProcessingCancellation())
                val source = File(app.cacheDir, "exports/process-recovery/boundary.mp4").apply { parentFile!!.mkdirs() }
                instrumentation.context.assets.open("boundary.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
                repository.importVideo(FileProvider.getUriForFile(app, "${app.packageName}.exports", source), ProcessingCancellation())
                source.parentFile!!.deleteRecursively()
                val job = repository.startJob(repository.state.value.pendingJob!!.id, TranscriptionOptions(SpeechLanguage.MANDARIN))
                if (phase == "committed") {
                    TranscriptStore(app.noBackupFilesDir).save(Transcript(job.sourceName, previous.segments, job.id), ProcessingCancellation())
                    // Deliberately leave durable media and active marker as if killed at commit boundary.
                } else {
                    val token = ProcessingCancellation()
                    try {
                        OfflineTranscriber(app).resume(job, repository.jobs, token, repository::progress) { checkpoint ->
                            repository.jobUpdated(checkpoint)
                            if (checkpoint.completedSections == 1) token.cancel()
                        }
                        fail("Expected first-section stop")
                    } catch (_: CancellationException) { }
                    assertEquals(1, repository.jobs.load()!!.completedSections)
                }
                assertTrue(TranscriptStore(app.noBackupFilesDir).wasInterrupted())
            }
            "resume" -> {
                val saved = repository.state.value.pendingJob!!
                assertFalse(repository.state.value.isActive) // Reopening never restarts inference.
                assertTrue(repository.state.value.message!!.contains("Resume"))
                assertEquals(previous, repository.state.value.transcript)
                assertEquals(1, saved.completedSections)
                val first = File(repository.jobs.directory, "section-0.json").readBytes()
                val started = repository.startJob(saved.id, saved.options)
                val result = OfflineTranscriber(app).resume(started, repository.jobs, ProcessingCancellation(), repository::progress) { checkpoint ->
                    assertTrue(checkpoint.completedSections > 1)
                    repository.jobUpdated(checkpoint)
                }
                assertArrayEquals(first, File(repository.jobs.directory, "section-0.json").readBytes())
                assertTrue(TranscriptFormatter.format(result, TranscriptFormat.PLAIN_TEXT), Regex("[声聲]明").findAll(TranscriptFormatter.format(result, TranscriptFormat.PLAIN_TEXT)).count() in 5..6)
                repository.complete(result, ProcessingCancellation())
                assertFalse(repository.jobs.directory.exists())
            }
            "reopen" -> {
                assertNull(repository.state.value.pendingJob)
                assertNotNull(repository.state.value.transcript!!.jobId)
                assertFalse(repository.jobs.directory.exists())
                assertFalse(repository.state.value.isActive)
            }
        }
    }
}
