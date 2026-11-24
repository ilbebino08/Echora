package com.echora.app.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Buffer circolare su file per PCM grezzo.
 *
 * La capacità è in byte. `append` scrive sempre tutti i byte in ingresso,
 * sovrascrivendo i più vecchi quando il buffer è pieno; `readLast` restituisce
 * gli ultimi `byteCount` byte dal più vecchio al più nuovo.
 */
class PcmRingBuffer(
    private val file: File,
    capacityBytes: Int
) {
    private val capacity = capacityBytes
    private var writePos: Int = 0
    private var filled: Int = 0

    init {
        // Capacità massima: 60 min di PCM 16 kHz mono (32000 B/s). Oltre, è un errore.
        if (capacityBytes > MAX_CAPACITY) {
            throw IOException("Capacità $capacityBytes byte oltre il massimo di $MAX_CAPACITY (~60 min)")
        }
        // Prealloca il file alla capacità esatta.
        val raf = RandomAccessFile(file, "rw")
        try {
            raf.setLength(capacity.toLong())
        } finally {
            raf.close()
        }
    }

    /** Scrive tutti i byte di `src` nel buffer, avvolgendo e sovrascrivendo i più vecchi. */
    fun append(src: ByteArray, offset: Int = 0, count: Int = src.size) {
        if (count <= 0) return
        val raf = RandomAccessFile(file, "rw")
        try {
            var written = 0
            var pos = writePos
            while (written < count) {
                val chunk = minOf(count - written, capacity - pos)
                raf.seek(pos.toLong())
                raf.write(src, offset + written, chunk)
                pos = (pos + chunk) % capacity
                written += chunk
            }
            writePos = pos
            filled = minOf(filled + count, capacity)
        } finally {
            raf.close()
        }
    }

    /** Numero di byte validi attualmente nel buffer (saturato a `capacity`). */
    fun filledBytes(): Long = filled.toLong()

    /** Legge al massimo `byteCount` byte, dal più vecchio al più nuovo. */
    fun readLast(byteCount: Long): ByteArray {
        val available = minOf(filled.toLong(), byteCount).toInt()
        if (available <= 0) return ByteArray(0)

        val start = (writePos - filled + capacity) % capacity
        val result = ByteArray(available)
        val raf = RandomAccessFile(file, "rw")
        try {
            if (start + available <= capacity) {
                raf.seek(start.toLong())
                raf.readFully(result)
            } else {
                val first = capacity - start
                raf.seek(start.toLong())
                raf.readFully(result, 0, first)
                raf.seek(0L)
                raf.readFully(result, first, available - first)
            }
        } finally {
            raf.close()
        }
        return result
    }

    /** Svuota il buffer. */
    fun reset() {
        filled = 0
        writePos = 0
    }

    /** Rilascia risorse (il file è gestito esternamente). */
    fun close() {
        // Nessuno stream persistente da chiudere.
    }

    companion object {
        /** 60 min * 60 s * 32000 B/s. Oltre, la richiesta è un errore. */
        const val MAX_CAPACITY = 60 * 60 * 32000
    }
}