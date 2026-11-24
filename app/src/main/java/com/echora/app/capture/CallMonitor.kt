package com.echora.app.capture

import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RecordingSnapshot(val source: Int)

object CallDetection {
    /** True se almeno uno snapshot ha source VOICE_COMMUNICATION (7). */
    fun isCallRecording(active: List<RecordingSnapshot>): Boolean {
        return active.any { it.source == MediaRecorder.AudioSource.VOICE_COMMUNICATION }
    }
}

class CallMonitor(private val audioManager: AudioManager) {

    private val _isCallActive = MutableStateFlow(false)
    val isCallActive: StateFlow<Boolean> = _isCallActive

    private var scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = object : Executor {
        override fun execute(command: Runnable) {
            mainHandler.post(command)
        }
    }

    fun start() {
        // Check initial state
        checkCallState()

        // Poll every 2 seconds
        pollJob = scope.launch {
            while (true) {
                delay(2000)
                if (scope.coroutineContext[Job]?.isCancelled == true) break
                checkCallState()
            }
        }

        // Listen for mode changes (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener = object : AudioManager.OnModeChangedListener {
                override fun onModeChanged(mode: Int) {
                    Log.d("EchoraCallMonitor", "Audio mode changed: $mode")
                    checkCallState()
                }
            }
            audioManager.addOnModeChangedListener(mainExecutor, modeListener!!)
        }
    }

    fun stop() {
        pollJob?.cancel()
        if (modeListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.removeOnModeChangedListener(modeListener!!)
            modeListener = null
        }
        scope.cancel()
    }

    fun isCallActiveNow(): Boolean = _isCallActive.value

    private fun checkCallState() {
        val wasActive = _isCallActive.value
        val nowActive = detectCall()

        if (wasActive != nowActive) {
            _isCallActive.value = nowActive
            Log.d("EchoraCallMonitor", "Call state changed: $nowActive")
        }
    }

    private fun detectCall(): Boolean {
        // 1. Check AudioManager mode
        val mode = audioManager.mode
        if (mode != AudioManager.MODE_NORMAL) {
            Log.d("EchoraCallMonitor", "Call detected via mode: $mode")
            return true
        }

        // 2. Check active recording configurations for VOICE_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val configs = audioManager.getActiveRecordingConfigurations()
                for (config in configs) {
                    if (config.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                        Log.d("EchoraCallMonitor", "Call detected via recording config: ${config.clientAudioSource}")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w("EchoraCallMonitor", "Failed to get recording configs", e)
            }
        }

        return false
    }
}