package com.example.video2text

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundVisibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TranscriptionApplication
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun wake() { shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }

    @Test fun rotationKeepsFreeWorkButHomePausesOnlyFreeWork(): Unit = runBlocking {
        wake()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(10_000) { app.foreground.state.first { it } }
            val free = ProcessingCancellation()
            val pro = ProcessingCancellation()
            val freeMonitor = launch(Dispatchers.Main) { TranscriptionAccess.forPlan(true, false).monitorForeground(app.foreground.state) { free.cancel() } }
            val proMonitor = launch(Dispatchers.Main) { TranscriptionAccess.forPlan(true, true).monitorForeground(app.foreground.state) { pro.cancel() } }
            try {
                scenario.recreate()
                instrumentation.waitForIdleSync()
                assertTrue(app.foreground.state.value)
                assertFalse(free.isCancelled())
                shell("input keyevent KEYCODE_HOME")
                withTimeout(10_000) { app.foreground.state.first { !it } }
                // Drain the same Main-thread flow delivery before inspecting cancellation.
                kotlinx.coroutines.withContext(Dispatchers.Main) { }
                assertTrue(free.isCancelled())
                assertFalse(pro.isCancelled())
            } finally { freeMonitor.cancelAndJoin(); proMonitor.cancelAndJoin() }
        }
    }

    @Test fun screenOffPausesFreeWorkAndUnlockDoesNotResumeAutomatically(): Unit = runBlocking {
        wake()
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(10_000) { app.foreground.state.first { it } }
            val free = ProcessingCancellation()
            val monitor = launch(Dispatchers.Main) { TranscriptionAccess.forPlan(true, false).monitorForeground(app.foreground.state) { free.cancel() } }
            try {
                shell("input keyevent KEYCODE_SLEEP")
                withTimeout(10_000) { app.foreground.state.first { !it } }
                kotlinx.coroutines.withContext(Dispatchers.Main) { }
                assertTrue(free.isCancelled())
                wake()
                withTimeout(10_000) { app.foreground.state.first { it } }
                assertTrue(free.isCancelled())
            } finally { wake(); monitor.cancelAndJoin() }
        }
    }
}
