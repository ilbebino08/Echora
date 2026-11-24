package com.echora.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

class WavWriterTest {
    @Test fun `header is 44 bytes of RIFF WAVE`() {
        val h = WavWriter.header(pcmBytes = 1000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4, Charset.forName("US-ASCII")))
        assertEquals("WAVE", String(h, 8, 4, Charset.forName("US-ASCII")))
        assertEquals("fmt ", String(h, 12, 4, Charset.forName("US-ASCII")))
        assertEquals("data", String(h, 36, 4, Charset.forName("US-ASCII")))
    }

    @Test fun `header carries rate channels and sizes`() {
        val h = WavWriter.header(pcmBytes = 1000)
        val le = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, le.getShort(20).toInt())        // PCM
        assertEquals(1, le.getShort(22).toInt())        // canali
        assertEquals(16_000, le.getInt(24))             // sample rate
        assertEquals(32_000, le.getInt(28))             // byte/s
        assertEquals(2, le.getShort(32).toInt())        // block align
        assertEquals(16, le.getShort(34).toInt())       // bit
        assertEquals(1000 + 36, le.getInt(4))           // RIFF size
        assertEquals(1000, le.getInt(40))               // data size
    }
}