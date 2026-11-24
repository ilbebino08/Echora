package com.echora.app.capture

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CallMonitorTest {

    @Test fun `voip recording triggers call detection`() {
        assertTrue(CallDetection.isCallRecording(listOf(RecordingSnapshot(7)))) // VOICE_COMMUNICATION
    }

    @Test fun `normal mic recording does not`() {
        assertFalse(CallDetection.isCallRecording(listOf(RecordingSnapshot(1), RecordingSnapshot(6)))) // MIC, VOICE_RECOGNITION
    }

    @Test fun `empty list does not trigger`() {
        assertFalse(CallDetection.isCallRecording(emptyList()))
    }

    @Test fun `mixed with voip triggers`() {
        assertTrue(CallDetection.isCallRecording(listOf(RecordingSnapshot(1), RecordingSnapshot(7))))
    }
}