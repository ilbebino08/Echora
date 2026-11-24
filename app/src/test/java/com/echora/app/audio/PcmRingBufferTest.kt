package com.echora.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

class PcmRingBufferTest {
    private fun buf(cap: Int) = PcmRingBuffer(File.createTempFile("ring", ".pcm"), cap)

    @Test fun `readLast returns appended bytes in order`() {
        val b = buf(100)
        b.append(ByteArray(10) { it.toByte() })
        assertArrayEquals(ByteArray(10) { it.toByte() }, b.readLast(1000))
    }

    @Test fun `readLast returns only the newest bytes after wraparound`() {
        val b = buf(10)
        b.append(ByteArray(15) { it.toByte() })          // 0..14, capienza 10
        assertArrayEquals(ByteArray(10) { (it + 5).toByte() }, b.readLast(10))
    }

    @Test fun `filledBytes saturates at capacity`() {
        val b = buf(10); b.append(ByteArray(25))
        assertEquals(10L, b.filledBytes())
    }

    @Test fun `reset empties the buffer`() {
        val b = buf(10); b.append(ByteArray(10)); b.reset()
        assertEquals(0L, b.filledBytes()); assertEquals(0, b.readLast(10).size)
    }

    @Test fun `capacity file is preallocated and rejects impossible size`() {
        val f = File.createTempFile("ring", ".pcm")
        PcmRingBuffer(f, 10).close()
        assertEquals(10L, f.length())
    }

    @Test fun `unallocatable capacity throws instead of silently truncating`() {
        val f = File.createTempFile("ring", ".pcm")
        assertThrows(IOException::class.java) { PcmRingBuffer(f, Int.MAX_VALUE) }
    }
}