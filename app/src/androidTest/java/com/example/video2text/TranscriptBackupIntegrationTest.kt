package com.example.video2text

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit phases surround a real bmgr backup/restore; never run against unpreserved user data. */
@RunWith(AndroidJUnit4::class)
class TranscriptBackupIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = Transcript("backup-verification.wav",
        listOf(TranscriptSegment(123, 456, "Café 你好，廣東話。")), "backup-verification-job")

    @Test fun prepareFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backupPhase") == "prepare")
        runBlocking { (context.applicationContext as TranscriptionApplication).repository.ready.await() }
        val store = TranscriptStore.forApp(context)
        store.save(fixture, ProcessingCancellation())
        File(context.filesDir, "backup-excluded.txt").writeText("Must not be backed up")
        File(context.noBackupFilesDir, "backup-excluded.txt").writeText("Must remain device-local")
        context.getSharedPreferences("backup-verification", 0).edit().putBoolean("excluded", true).commit()
        assertTrue(store.loadHistory().any { it.transcript == fixture })
    }

    @Test fun verifyRestoredFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backupPhase") == "verify")
        val app = context.applicationContext as TranscriptionApplication
        runBlocking { app.repository.ready.await() }
        val store = TranscriptStore.forApp(context)
        val entries = store.loadHistory()
        assertEquals(fixture, entries.single { it.transcript.jobId == fixture.jobId }.transcript)
        assertEquals(entries, app.repository.state.value.history)
        assertFalse(File(context.filesDir, "backup-excluded.txt").exists())
        assertFalse(File(context.noBackupFilesDir, "backup-excluded.txt").exists())
        assertFalse(context.getSharedPreferences("backup-verification", 0).contains("excluded"))
        assertNull(app.repository.state.value.pendingJob)
        assertFalse(store.wasInterrupted())
    }
}
