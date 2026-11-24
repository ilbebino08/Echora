package com.echora.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTransformTest {
    @Test fun `downmix takes every third frame and averages channels`() {
        val src = shortArrayOf(100, 300, 0, 0, 0, 0, -100, -300) // 4 frame 48k stereo
        val out = AudioTransform.downmixStereo48kToMono16k(src, frames = 4)
        assertEquals(1, out.size)
        assertEquals(200.toShort(), out[0])
    }

    @Test fun `mix saturates instead of wrapping`() {
        val out = AudioTransform.mix(shortArrayOf(30000, Short.MIN_VALUE), shortArrayOf(30000, -1))
        assertEquals(Short.MAX_VALUE, out[0])
        assertEquals(Short.MIN_VALUE, out[1])
    }
}