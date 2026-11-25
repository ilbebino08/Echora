package com.echora.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.echora.app.audio.PcmRingBuffer
import com.echora.app.privileged.ShellConnection
import com.echora.app.privileged.ShellConnection.ShellStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.echora.app.ACTION_START"
        const val ACTION_STOP = "com.echora.app.ACTION_STOP"
        const val ACTION_TOGGLE = "com.echora.app.ACTION_TOGGLE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "echora_capture"
        private const val CHUNK_SIZE = 3200 // 100ms at 16kHz mono 16-bit

        // Audio sources used by the capture service.
        const val SOURCE_HOTWORD = 1999
        const val SOURCE_VOICE_CALL = android.media.MediaRecorder.AudioSource.VOICE_CALL // 4

        @Volatile
        private var ringBuffer: PcmRingBuffer? = null

        internal fun readRecentAudio(byteCount: Long): ByteArray {
            val pcm = ringBuffer?.readLast(byteCount) ?: ByteArray(0)
            check(pcm.isNotEmpty()) { "Il buffer non contiene ancora audio" }
            return pcm
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CaptureService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }

        fun toggle(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_TOGGLE))
        }
    }

    private var shellConnection: ShellConnection? = null
    private var settings: Settings? = null
    private var scope = CoroutineScope(Dispatchers.IO)
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var isRunning = false
    private var consecutiveZeroFrames = 0
    private var recordingState: CaptureState = CaptureState.Recording
    private var callMonitor: CallMonitor? = null
    private var captureRef: com.echora.app.IEchoraCapture? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings.create(this)
        shellConnection = ShellConnection(this)
        callMonitor = CallMonitor(getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
            ACTION_TOGGLE -> if (isRunning) stopCapture() else startCapture()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        scope.cancel()
        shellConnection?.stop()
        callMonitor?.stop()
        releaseWakeLock()
    }

    private fun startCapture() {
        if (isRunning) return
        val captureSettings = settings
        if (captureSettings?.listeningEnabled != true) {
            stopSelf()
            return
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildForegroundNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        isRunning = true
        acquireWakeLock()
        updateState(CaptureState.Connecting)

        scope.launch {
            shellConnection?.start()

            // Wait for READY state with timeout
            var ready = false
            for (i in 0..100) { // 10 seconds max
                if (shellConnection?.status?.value == ShellStatus.READY) {
                    ready = true
                    break
                }
                if (!isRunning) return@launch
                delay(100)
            }

            if (!ready) {
                stopCapture(CaptureState.Error("Connessione Shizuku scaduta"))
                return@launch
            }

            val capture = shellConnection?.capture() ?: return@launch
            captureRef = capture

            // Initialize ring buffer
            val capacity = captureSettings.bufferMinutes * 60 * 32000
            val bufferFile = File(filesDir, "buffer.pcm")
            ringBuffer = PcmRingBuffer(bufferFile, capacity)

            // Start call monitoring
            callMonitor?.start()

            // Set mic listener
            capture.setMicListener(object : com.echora.app.IMicStateCallback.Stub() {
                override fun onMicDisabledChanged(disabled: Boolean) {
                    if (!isRunning || recordingState == CaptureState.PausedForCall) return
                    if (disabled) updateState(CaptureState.MicDisabled)
                    else updateState(recordingState)
                }
            })

            var currentCall: CallKind? = null
            val chunk = ByteArray(CHUNK_SIZE)
            while (isRunning) {
                val kind = callMonitor?.call?.value ?: CallKind.NONE
                if (captureSettings.shouldPauseForCall(kind)) {
                    if (recordingState != CaptureState.PausedForCall) {
                        try {
                            capture.release()
                        } catch (e: RemoteException) {
                            Log.w("EchoraCapture", "Collegamento alla cattura interrotto", e)
                            stopCapture(CaptureState.Error("Collegamento a Shizuku interrotto"))
                            return@launch
                        }
                        currentCall = null
                        recordingState = CaptureState.PausedForCall
                        updateState(recordingState)
                        Log.d("EchoraCapture", "Cattura sospesa per chiamata: $kind")
                    }
                    delay(100)
                    continue
                }
                if (currentCall != kind) {
                    try {
                        recordingState = openFor(currentCall, kind, capture)
                    } catch (e: Exception) {
                        Log.w("EchoraCapture", "Impossibile cambiare sorgente audio", e)
                        stopCapture(CaptureState.Error("Impossibile aprire il microfono"))
                        return@launch
                    }
                    currentCall = kind
                    updateState(recordingState)
                }
                val n = try {
                    val count = capture.read(chunk, chunk.size)
                    val state = recordingState as? CaptureState.CallRecording
                    if (state?.kind == CallKind.VOIP && state.hasMicrophone && capture.actualSampleRate() == 0) {
                        recordingState = state.copy(hasMicrophone = false)
                        updateState(recordingState)
                    }
                    count
                } catch (e: RemoteException) {
                    Log.w("EchoraCapture", "Collegamento alla cattura interrotto", e)
                    stopCapture(CaptureState.Error("Collegamento a Shizuku interrotto"))
                    return@launch
                }
                if (captureSettings.shouldPauseForCall(callMonitor?.call?.value ?: CallKind.NONE)) continue
                when {
                    n > 0 -> {
                        val rb = ringBuffer
                        if (rb != null) {
                            val firstBlock = rb.filledBytes() == 0L
                            rb.append(chunk, 0, n)
                            if (firstBlock) Log.d("EchoraCapture", "Primo blocco PCM ricevuto: $n byte")
                        }
                        consecutiveZeroFrames = 0
                    }
                    n == 0 -> {
                        // No data available, continue
                    }
                    n < 0 -> {
                        if (callMonitor?.call?.value != currentCall) {
                            currentCall = null
                            continue
                        }
                        updateState(CaptureState.Error("Errore lettura: $n"))
                        break
                    }
                }

                // Check for all-zero audio (microphone disabled/silent)
                val rb = ringBuffer
                if (rb != null && rb.filledBytes() > 0) {
                    val recent = rb.readLast(minOf(3200, rb.filledBytes()))
                    val allZero = recent.all { it == 0.toByte() }
                    if (allZero) {
                        consecutiveZeroFrames++
                        if (consecutiveZeroFrames >= 100) { // 10 seconds of zeros
                            updateState(CaptureState.Error("Nessun audio dal microfono"))
                            break
                        }
                    } else {
                        consecutiveZeroFrames = 0
                    }
                }

                delay(10)
            }

            stopCapture(CaptureStates.current.value as? CaptureState.Error ?: CaptureState.Idle)
        }
    }

    private fun openFor(
        previous: CallKind?,
        kind: CallKind,
        capture: com.echora.app.IEchoraCapture
    ): CaptureState {
        if (kind == CallKind.TELEPHONE) {
            try {
                capture.open(SOURCE_VOICE_CALL, 16000, 1, 128000)
                Log.d("EchoraCapture", "Sorgente telefonica VOICE_CALL attiva")
                return CaptureState.CallRecording(kind, hasCallAudio = true)
            } catch (e: Exception) {
                Log.w("EchoraCapture", "VOICE_CALL non disponibile", e)
            }
        }

        if (kind == CallKind.VOIP) {
            val hasMicrophone = try {
                capture.open(MediaRecorder.AudioSource.MIC, 16000, 1, 128000)
                true
            } catch (e: RuntimeException) {
                Log.w("EchoraCapture", "Microfono non disponibile durante la chiamata", e)
                false
            }

            try {
                capture.enableVoipPlayback(16000)
                return CaptureState.CallRecording(kind, hasCallAudio = true, hasMicrophone = hasMicrophone)
            } catch (e: Exception) {
                Log.w("EchoraCapture", "Loopback VoIP non disponibile", e)
                if (!hasMicrophone) throw e
            }
            return CaptureState.CallRecording(kind, hasCallAudio = false)
        }

        capture.open(SOURCE_HOTWORD, 16000, 1, 128000)
        if (previous == CallKind.VOIP) Log.d("EchoraCapture", "Cattura ambientale ripristinata")
        return if (kind == CallKind.NONE) CaptureState.Recording
            else CaptureState.CallRecording(kind, hasCallAudio = false)
    }

    private fun stopCapture(finalState: CaptureState = CaptureState.Idle) {
        if (!isRunning) return
        isRunning = false
        scope.cancel()
        try {
            captureRef?.release()
        } catch (e: RemoteException) {
            Log.w("EchoraCapture", "Collegamento già chiuso", e)
        }
        ringBuffer?.close()
        ringBuffer = null
        captureRef = null
        callMonitor?.stop()
        stopForeground(true)
        releaseWakeLock()
        updateState(finalState)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Echora::CaptureWakeLock")
        wakeLock?.acquire()
    }

    @Synchronized
    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cattura Audio Echora",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifica del servizio di cattura audio continua"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Echora")
            .setContentText("Servizio di cattura audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateState(state: CaptureState) {
        CaptureStates.current.value = state
    }
}
