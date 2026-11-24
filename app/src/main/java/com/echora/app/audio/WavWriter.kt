package com.echora.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    const val SAMPLE_RATE = 16_000

    fun header(pcmBytes: Int, sampleRate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val dataSize = pcmBytes
        val riffSize = dataSize + 36
        val fmtSize = 16
        val blockAlign = (channels * 2).toShort()
        val bitRate = sampleRate * blockAlign

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())           // 0-3
        buffer.putInt(riffSize)                    // 4-7
        buffer.put("WAVE".toByteArray())           // 8-11
        buffer.put("fmt ".toByteArray())           // 12-15
        buffer.putInt(fmtSize)                     // 16-19 fmt size (16)
        buffer.putShort(1)                         // 20-21 audio format (PCM)
        buffer.putShort(channels.toShort())        // 22-23 num channels
        buffer.putInt(sampleRate)                  // 24-27 sample rate
        buffer.putInt(bitRate)                     // 28-31 byte rate
        buffer.putShort(blockAlign)                // 32-33 block align
        buffer.putShort(16)                        // 34-35 bits per sample
        buffer.put("data".toByteArray())           // 36-39 "data"
        buffer.putInt(dataSize)                    // 40-43 data size
        return buffer.array()
    }
}