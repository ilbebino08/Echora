package com.echora.app.capture

import android.media.MediaRecorder
import android.media.AudioManager
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

enum class CallKind { NONE, TELEPHONE, VOIP }

internal fun detectCall(mode: Int, hasVoipRecording: Boolean): CallKind = when (mode) {
    AudioManager.MODE_IN_CALL, AudioManager.MODE_CALL_REDIRECT -> CallKind.TELEPHONE
    AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_COMMUNICATION_REDIRECT -> CallKind.VOIP
    AudioManager.MODE_NORMAL -> if (hasVoipRecording) CallKind.VOIP else CallKind.NONE
    else -> CallKind.NONE
}

class CallMonitor(private val audioManager: AudioManager) {

    private val _call = MutableStateFlow(CallKind.NONE)
    val call: StateFlow<CallKind> = _call

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

        val listener = AudioManager.OnModeChangedListener { checkCallState() }
        modeListener = listener
        audioManager.addOnModeChangedListener(mainExecutor, listener)
    }

    fun stop() {
        pollJob?.cancel()
        modeListener?.let(audioManager::removeOnModeChangedListener)
        modeListener = null
        scope.cancel()
    }

    private fun checkCallState() {
        val mode = audioManager.mode
        val voipRecording = mode == AudioManager.MODE_NORMAL && try {
            audioManager.activeRecordingConfigurations.any {
                !it.isClientSilenced && it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }
        } catch (e: SecurityException) {
            Log.d("EchoraCallMonitor", "Configurazioni audio non accessibili", e)
            false
        }
        val kind = detectCall(mode, voipRecording)
        if (_call.value != kind) {
            _call.value = kind
            Log.d("EchoraCallMonitor", "Tipo chiamata: $kind")
        }
    }
}
