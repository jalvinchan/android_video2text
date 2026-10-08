package com.example.video2text

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App visibility, not keyboard focus. Process lifecycle ignores rotation's brief stop/start. */
internal class ForegroundVisibility(context: Context) : DefaultLifecycleObserver {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val process = ProcessLifecycleOwner.get().lifecycle
    private var started = process.currentState.isAtLeast(Lifecycle.State.STARTED)
    private val mutable = MutableStateFlow(isVisible())
    val state = mutable.asStateFlow()

    init {
        process.addObserver(this)
        // Application lifetime registration. Screen-off is immediate, independent of lifecycle delay.
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mutable.value = if (intent.action == Intent.ACTION_SCREEN_OFF) false else isVisible()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStart(owner: LifecycleOwner) { started = true; mutable.value = isVisible() }
    override fun onStop(owner: LifecycleOwner) { started = false; mutable.value = false }
    private fun isVisible() = started && power.isInteractive && !keyguard.isKeyguardLocked
}
