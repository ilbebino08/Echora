package com.echora.app.privileged

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import java.lang.reflect.Constructor

object AudioRecordFactory {
    private const val TAG = "EchoraRecordFactory"

    /**
     * Crea un AudioRecord usando il costruttore nascosto per forma.
     * La sorgente viene passata via AudioAttributes.Builder.setInternalCapturePreset() via reflection.
     *
     * @param context Context di base (verrà avvolto in ShellContext)
     * @param source Sorgente audio (es. 1999 = HOTWORD, 4 = VOICE_CALL, 8 = REMOTE_SUBMIX, 1 = MIC)
     * @param sampleRate Sample rate in Hz (es. 16000)
     * @param channels Numero di canali (es. 1 = mono, 2 = stereo)
     * @param bufferBytes Dimensione del buffer in byte
     * @return AudioRecord configurato e pronto per startRecording()
     */
    @Throws(Exception::class)
    fun create(
        context: Context,
        source: Int,
        sampleRate: Int,
        channels: Int,
        bufferBytes: Int
    ): AudioRecord {
        // 1. AudioAttributes con sorgente interna via reflection
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        setInternalCapturePreset(attributesBuilder, source)
        val attributes = attributesBuilder.build()

        // 2. AudioFormat
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        // 3. Buffer size - minimo richiesto, ma almeno 4 blocchi
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(bufferBytes, minBuffer)

        // 4. Trova il costruttore nascosto per forma
        val shellContext = ShellContext(context, isRootUid())
        val record = createViaHiddenConstructor(attributes, format, bufferSize, shellContext)

        Log.d(TAG, "AudioRecord created: source=$source rate=$sampleRate ch=$channels buffer=$bufferSize")
        return record
    }

    private fun createViaHiddenConstructor(
        attributes: AudioAttributes,
        format: AudioFormat,
        bufferSize: Int,
        shellContext: ShellContext
    ): AudioRecord {
        val constructors = AudioRecord::class.java.declaredConstructors
        var bestCtor: Constructor<*>? = null

        for (c in constructors) {
            val p = c.parameterTypes
            if (p.size < 5 || p[0] != AudioAttributes::class.java || p[1] != AudioFormat::class.java
                || p[2] != Int::class.java || p[3] != Int::class.java || p[4] != Context::class.java
            ) {
                continue
            }
            var tailIsInt = true
            for (i in 5 until p.size) {
                if (p[i] != Int::class.java) {
                    tailIsInt = false
                    break
                }
            }
            if (tailIsInt && (bestCtor == null || p.size > bestCtor.parameterTypes.size)) {
                bestCtor = c
            }
        }

        if (bestCtor == null) {
            throw NoSuchMethodException("No suitable hidden AudioRecord constructor found")
        }

        bestCtor.isAccessible = true

        val types = bestCtor.parameterTypes
        val args = Array(types.size) { i ->
            val type = types[i]
            when {
                type == AudioAttributes::class.java -> attributes
                type == AudioFormat::class.java -> format
                type == Context::class.java -> shellContext
                type == Int::class.java -> if (i == 2) bufferSize else 0
                else -> 0
            }
        }

        return bestCtor.newInstance(*args) as AudioRecord
    }

    private fun setInternalCapturePreset(builder: AudioAttributes.Builder, source: Int) {
        try {
            val method = AudioAttributes.Builder::class.java.getDeclaredMethod(
                "setInternalCapturePreset",
                Int::class.java
            )
            method.isAccessible = true
            method.invoke(builder, source)
        } catch (e: Exception) {
            throw RuntimeException("Failed to setInternalCapturePreset($source)", e)
        }
    }

    private fun isRootUid(): Boolean {
        return try {
            val process = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim() == "0"
        } catch (e: Exception) {
            false
        }
    }
}