package com.echora.app.capture

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class CallMonitorTest {
    @Test fun `classifies telephone and VoIP separately`() {
        assertEquals(CallKind.TELEPHONE, detectCall(AudioManager.MODE_IN_CALL, true))
        assertEquals(CallKind.VOIP, detectCall(AudioManager.MODE_IN_COMMUNICATION, false))
        assertEquals(CallKind.VOIP, detectCall(AudioManager.MODE_NORMAL, true))
    }

    @Test fun `ringtone and call screening are not active calls`() {
        assertEquals(CallKind.NONE, detectCall(AudioManager.MODE_RINGTONE, true))
        assertEquals(CallKind.NONE, detectCall(AudioManager.MODE_CALL_SCREENING, false))
        assertEquals(CallKind.NONE, detectCall(AudioManager.MODE_NORMAL, false))
    }
}
