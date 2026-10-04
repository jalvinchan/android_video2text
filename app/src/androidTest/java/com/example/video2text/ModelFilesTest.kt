package com.example.video2text

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelFilesTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val bytes = "A small verified model fixture: 普通话，廣東話".toByteArray(Charsets.UTF_8)
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun installationIsAtomicAndModelPreparationDoesNotCommitTheTranscription() {
        val directory = File(app.cacheDir, "model-install-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val atomic = AtomicFile(File(directory, "model.bin"))
            val token = ProcessingCancellation()
            ModelFiles.install(atomic, ByteArrayInputStream(bytes), bytes.size.toLong(), hash, token) {}
            assertArrayEquals(bytes, atomic.openRead().use { it.readBytes() })
            assertFalse(token.isCommitted())
            assertTrue(token.cancel())
            assertTrue(ModelFiles.verify(ByteArrayInputStream(bytes), bytes.size.toLong(), hash, ProcessingCancellation()))
            for ((payload, expectedHash) in listOf(bytes.copyOf(bytes.size - 1) to hash, bytes to "0".repeat(64), (bytes + 1.toByte()) to hash)) {
                try { ModelFiles.install(atomic, ByteArrayInputStream(payload), bytes.size.toLong(), expectedHash, ProcessingCancellation()) {}; fail("Unverified model installed") }
                catch (_: IOException) { }
                assertArrayEquals(bytes, atomic.openRead().use { it.readBytes() })
                assertFalse(File(directory, "model.bin.new").exists())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationDuringCopyKeepsInstalledBytesAndLowStorageFailsBeforeWriting() {
        val directory = File(app.cacheDir, "model-cancel-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val atomic = AtomicFile(File(directory, "model.bin"))
            atomic.baseFile.writeText("previous verified model")
            val token = ProcessingCancellation()
            try { ModelFiles.install(atomic, ByteArrayInputStream(bytes), bytes.size.toLong(), hash, token) { token.cancel() }; fail("Cancellation ignored") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals("previous verified model", atomic.baseFile.readText())
            assertFalse(File(directory, "model.bin.new").exists())
            try { ModelFiles.install(atomic, ByteArrayInputStream(bytes), Long.MAX_VALUE, hash, ProcessingCancellation()) {}; fail("Impossible storage accepted") }
            catch (_: IOException) { }
            assertEquals("previous verified model", atomic.baseFile.readText())
        } finally { directory.deleteRecursively() }
    }
}
