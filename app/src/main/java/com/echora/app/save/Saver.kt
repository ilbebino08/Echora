package com.echora.app.save

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Saver {

    private const val RELATIVE_PATH = "Music/Echora"
    private const val MIME_TYPE = "audio/wav"
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    /**
     * Genera il nome file per il salvataggio.
     * Formato: Echora_20261124_180300_ultimi10m.wav
     */
    fun fileName(minutes: Int, now: Instant = Instant.now()): String {
        val timestamp = DATE_FORMAT.format(now)
        return "Echora_${timestamp}_ultimi${minutes}m.wav"
    }

    /**
     * Salva i dati PCM come WAV in MediaStore.
     *
     * @param context Context per ContentResolver
     * @param pcm Dati PCM grezzi (16kHz mono 16-bit)
     * @param minutes Durata richiesta in minuti (usata per il nome file)
     * @param now Timestamp per il nome file (default: ora corrente)
     * @return Uri del file salvato
     * @throws IOException se c'è un errore di I/O o spazio insufficiente
     */
    fun save(
        context: Context,
        pcm: ByteArray,
        minutes: Int,
        now: Instant = Instant.now()
    ): Uri {
        val resolver: ContentResolver = context.contentResolver
        val fileName = fileName(minutes, now)

        // Crea entry in MediaStore con IS_PENDING=1
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues)
            ?: throw IOException("Failed to create MediaStore entry for $fileName")

        try {
            // Scrivi WAV header + PCM data
            val outputStream: OutputStream = resolver.openOutputStream(uri)
                ?: throw IOException("Failed to open output stream for $uri")

            try {
                val wavHeader = com.echora.app.audio.WavWriter.header(pcm.size)
                outputStream.write(wavHeader)
                outputStream.write(pcm)
                outputStream.flush()
            } finally {
                outputStream.close()
            }

            // Marca come completo (IS_PENDING=0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }

            Log.d("EchoraSaver", "Saved $fileName (${pcm.size} bytes PCM) to $uri")
            return uri

        } catch (e: Exception) {
            // Cleanup on failure
            try {
                resolver.delete(uri, null, null)
            } catch (ex: Exception) {
                Log.w("EchoraSaver", "Failed to cleanup after save error", ex)
            }
            throw IOException("Failed to save WAV: ${e.message}", e)
        }
    }
}