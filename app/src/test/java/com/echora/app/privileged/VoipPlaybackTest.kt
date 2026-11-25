package com.echora.app.privileged

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VoipPlaybackTest {
    @Test fun `mixes signed PCM with saturation and preserves unread microphone samples`() {
        val microphone = byteArrayOf(0x30, 0x75, -0x30, -0x76, 0, 0, 7, 0)
        val playback = byteArrayOf(0x10, 0x27, -0x10, -0x28, -1, -1)
        mixVoicePcm(microphone, playback, 6)
        assertArrayEquals(byteArrayOf(-1, 0x7f, 0, -0x80, -1, -1, 7, 0), microphone)
    }
}
