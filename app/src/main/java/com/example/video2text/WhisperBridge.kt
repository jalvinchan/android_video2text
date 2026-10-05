package com.example.video2text

import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

internal class ProcessingException(message: String) : Exception(message)

internal class ProcessingCancellation {
    private val cancelled = AtomicBoolean(false)
    private val resourceCancellation = AtomicReference<(() -> Unit)?>(null)
    private var committed = false
    fun cancel(): Boolean {
        synchronized(this) {
            if (committed) return false
            cancelled.set(true)
        }
        // Closing the provider stream also interrupts a blocked read during media staging.
        resourceCancellation.getAndSet(null)?.let { runCatching(it) }
        return true
    }
    fun isCancelled(): Boolean = cancelled.get()
    @Synchronized fun isCommitted(): Boolean = committed
    fun check() { if (isCancelled()) throw CancellationException("Transcription cancelled.") }
    @Synchronized fun commit(action: () -> Unit) {
        check()
        action()
        committed = true
    }
    fun <T> withCancellableResource(cancelResource: () -> Unit, action: () -> T): T {
        check(resourceCancellation.compareAndSet(null, cancelResource))
        try {
            if (isCancelled()) resourceCancellation.getAndSet(null)?.let { runCatching(it) }
            check()
            return action()
        } finally { resourceCancellation.compareAndSet(cancelResource, null) }
    }
}

@Keep
internal interface WhisperCallbacks {
    fun isCancelled(): Boolean
    fun onProgress(percent: Int)
    fun onPlan(boundaries: IntArray) {}
    fun onSectionComplete(index: Int) {}
    fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray)
}

@Keep
internal object WhisperBridge {
    init { System.loadLibrary("transcription") }
    /** A run owns one model/detector session. Calls and close stay on its processing thread. */
    class Session(private val modelPath: String) : java.io.Closeable {
        private var handle = openSession(modelPath)
        fun transcribe(vadPath: String, pcmPath: String, language: String, model: String, offsetMs: Long, threads: Int, coreStart: Int, coreEnd: Int, plan: IntArray?, completed: Int, callbacks: WhisperCallbacks) {
            check(handle != 0L)
            transcribeWindow(handle, modelPath, vadPath, pcmPath, language, model, offsetMs, threads, coreStart, coreEnd, plan, completed, callbacks)
        }
        override fun close() {
            if (handle != 0L) closeSession(handle)
            handle = 0
        }
    }
    private external fun openSession(modelPath: String): Long
    private external fun closeSession(handle: Long)
    private external fun transcribeWindow(handle: Long, modelPath: String, vadPath: String, pcmPath: String, language: String, model: String, audioOffsetMs: Long, threads: Int, coreStart: Int, coreEnd: Int, plan: IntArray?, completed: Int, callbacks: WhisperCallbacks)
    external fun transcribeSections(modelPath: String, vadPath: String, pcmPath: String, language: String, model: String, audioOffsetMs: Long, threads: Int, plan: IntArray?, completed: Int, callbacks: WhisperCallbacks)
    external fun transcribe(modelPath: String, vadPath: String, pcmPath: String, language: String, model: String, audioOffsetMs: Long, threads: Int, callbacks: WhisperCallbacks)
}
