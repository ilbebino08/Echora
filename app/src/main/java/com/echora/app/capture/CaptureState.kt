package com.echora.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface CaptureState {
    object Idle : CaptureState
    object Connecting : CaptureState
    object Recording : CaptureState
    object PausedForCall : CaptureState
    object MicDisabled : CaptureState
    data class CallRecording(
        val kind: CallKind,
        val hasCallAudio: Boolean,
        val hasMicrophone: Boolean = true
    ) : CaptureState
    data class Error(val reason: String) : CaptureState
}

object CaptureStates {
    val current: MutableStateFlow<CaptureState> = MutableStateFlow(CaptureState.Idle)
}
