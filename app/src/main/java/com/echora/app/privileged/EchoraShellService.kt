package com.echora.app.privileged

import android.content.Context
import android.media.AudioRecord
import android.os.Binder
import android.os.Process
import android.util.Log
import com.echora.app.IEchoraCapture
import com.echora.app.IMicStateCallback
import kotlin.system.exitProcess

class EchoraShellService(private val context: Context) : IEchoraCapture.Stub() {
    @Volatile private var record: AudioRecord? = null
    @Volatile private var voipPlayback: VoipPlayback? = null
    private var playbackBytes = ByteArray(0)
    private val mutex = Any()
    private var micListener: IMicStateCallback? = null

    override fun open(source: Int, sampleRate: Int, channels: Int, bufferBytes: Int) {
        synchronized(mutex) {
            releaseLocked()
            val identity = Binder.clearCallingIdentity()
            try {
                val rec = AudioRecordFactory.create(
                    context,
                    source,
                    sampleRate,
                    channels,
                    bufferBytes
                )
                try {
                    check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord non inizializzato ($source)" }
                    rec.startRecording()
                    check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Sorgente $source non attiva" }
                } catch (e: Exception) {
                    rec.release()
                    throw e
                }
                record = rec
                Log.d("EchoraShell", "open OK: source=$source rate=$sampleRate ch=$channels")
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    override fun enableVoipPlayback(sampleRate: Int) {
        synchronized(mutex) {
            val identity = Binder.clearCallingIdentity()
            try {
                if (voipPlayback == null) {
                    voipPlayback = VoipPlayback.open(ShellContext(context, Process.myUid() == 0), sampleRate)
                    Log.d("EchoraShell", "Loopback VoIP attivo")
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    override fun start() {
        synchronized(mutex) {
            record?.startRecording()
        }
    }

    override fun read(buffer: ByteArray, sizeBytes: Int): Int {
        val rec = record
        val playback = voipPlayback
        return try {
            if (rec == null) {
                return playback?.let { readVoipPlayback(it, buffer, sizeBytes) } ?: -1
            }
            val count = rec.read(buffer, 0, sizeBytes)
            if (count < 0 && playback != null) {
                synchronized(mutex) {
                    if (record === rec) {
                        record = null
                        rec.release()
                        Log.w("EchoraShell", "Microfono interrotto ($count), continua il loopback VoIP")
                    }
                }
                return readVoipPlayback(playback, buffer, sizeBytes)
            }
            if (count > 0 && playback != null) {
                if (playbackBytes.size < count) playbackBytes = ByteArray(count)
                val read = readVoipPlayback(playback, playbackBytes, count)
                if (read > 0) mixVoicePcm(buffer, playbackBytes, read)
                if (read < 0) return read
            }
            count
        } catch (e: Exception) {
            Log.e("EchoraShell", "read exception", e)
            -1
        }
    }

    private fun readVoipPlayback(playback: VoipPlayback, buffer: ByteArray, sizeBytes: Int): Int {
        val count = playback.record.read(buffer, 0, sizeBytes, AudioRecord.READ_NON_BLOCKING)
        logVoipSignal(playback, buffer, count)
        return count
    }

    override fun release() {
        synchronized(mutex) {
            releaseLocked()
        }
    }

    override fun isMicrophoneDisabled(): Boolean {
        return try {
            val sensorPrivacy = context.getSystemService("sensor_privacy")
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
    private fun logVoipSignal(playback: VoipPlayback, bytes: ByteArray, count: Int) {
        if (!playback.receivedSignal && (0 until count).any { bytes[it] != 0.toByte() }) {
            playback.receivedSignal = true
            Log.d("EchoraShell", "Segnale VoIP ricevuto dal loopback")
        }
    }

    private fun releaseVoipPlayback() {
        val playback = voipPlayback
        voipPlayback = null
        if (playback != null) {
            val identity = Binder.clearCallingIdentity()
            try {
                playback.close()
            } catch (e: Exception) {
                Log.w("EchoraShell", "Chiusura loopback VoIP", e)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    private fun releaseLocked() {
        releaseVoipPlayback()
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

    override fun destroy() {
        release()
        exitProcess(0)
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
