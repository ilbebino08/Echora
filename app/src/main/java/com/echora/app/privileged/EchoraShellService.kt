package com.echora.app.privileged

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioRecord
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.echora.app.IEchoraCapture
import com.echora.app.IMicStateCallback

class EchoraShellService : Service() {
    private var record: AudioRecord? = null
    private val mutex = Any()
    private var micListener: IMicStateCallback? = null

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private val binder = object : IEchoraCapture.Stub() {
        override fun open(source: Int, sampleRate: Int, channels: Int, bufferBytes: Int) {
            synchronized(mutex) {
                releaseLocked()
                try {
                    val rec = AudioRecordFactory.create(
                        this@EchoraShellService,
                        source,
                        sampleRate,
                        channels,
                        bufferBytes
                    )
                    rec.startRecording()
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record = rec
                        Log.d("EchoraShell", "open OK: source=$source rate=$sampleRate ch=$channels")
                    } else {
                        rec.release()
                        Log.e("EchoraShell", "open FAILED: startRecording state=${rec.recordingState}")
                    }
                } catch (e: Exception) {
                    Log.e("EchoraShell", "open exception", e)
                }
            }
        }

        override fun start() {
            synchronized(mutex) {
                record?.startRecording()
            }
        }

        override fun read(buffer: ByteArray, sizeBytes: Int): Int {
            val rec = record ?: return -1
            return try {
                rec.read(buffer, 0, sizeBytes)
            } catch (e: Exception) {
                Log.e("EchoraShell", "read exception", e)
                -1
            }
        }

        override fun release() {
            synchronized(mutex) {
                releaseLocked()
            }
        }

        override fun isMicrophoneDisabled(): Boolean {
            return try {
                val sensorPrivacy = getSystemService("sensor_privacy")
                if (sensorPrivacy != null) {
                    val method = sensorPrivacy.javaClass.getMethod("isSensorPrivacyEnabled", Int::class.java)
                    method.isAccessible = true
                    method.invoke(sensorPrivacy, 1) as Boolean // 1 = microphone
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w("EchoraShell", "isMicrophoneDisabled check failed", e)
                false
            }
        }

        override fun setMicListener(cb: IMicStateCallback?) {
            synchronized(mutex) {
                micListener = cb
            }
        }

        override fun actualSampleRate(): Int {
            return record?.sampleRate ?: 0
        }

        override fun actualChannels(): Int {
            return record?.channelConfiguration ?: 0
        }
    }

    private fun releaseLocked() {
        val rec = record
        if (rec != null) {
            try {
                rec.stop()
            } catch (e: Exception) {
                Log.w("EchoraShell", "stop failed", e)
            }
            try {
                rec.release()
            } catch (e: Exception) {
                Log.w("EchoraShell", "release failed", e)
            }
            record = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        synchronized(mutex) {
            releaseLocked()
        }
    }

    companion object {
        /**
         * Verifica se il chiamante ha i privilegi per usare questo servizio.
         * - uid 0 (root) sempre OK
         * - uid con permesso CAPTURE_AUDIO_HOTWORD (Shizuku shell)
         */
        fun isCompatible(context: Context): Boolean {
            val token = Binder.clearCallingIdentity()
            try {
                val callingUid = Binder.getCallingUid()
                if (callingUid == 0) return true
                val pm = context.packageManager
                try {
                    val granted = pm.checkPermission(
                        "android.permission.CAPTURE_AUDIO_HOTWORD",
                        "com.android.shell"
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    return granted
                } catch (e: Exception) {
                    return false
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }
}