package com.echora.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echora.app.MainActivity
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
        private const val FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 16 // android.app.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        // Audio source constants (from spike: HOTWORD=1999, VOICE_CALL=4, REMOTE_SUBMIX=8)
        const val SOURCE_HOTWORD = 1999
        const val SOURCE_VOICE_CALL = android.media.MediaRecorder.AudioSource.VOICE_CALL // 4
        const val SOURCE_REMOTE_SUBMIX = 8

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
    private var ringBuffer: PcmRingBuffer? = null
    private var scope = CoroutineScope(Dispatchers.IO)
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var isRunning = false
    private var consecutiveZeroFrames = 0
    private var currentSource = SOURCE_HOTWORD
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
        if (!settings?.listeningEnabled!!) return

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
                updateState(CaptureState.Error("Timeout connessione Shizuku"))
                stopCapture()
                return@launch
            }

            val capture = shellConnection?.capture() ?: return@launch
            captureRef = capture

            // Initialize ring buffer
            val capacity = settings!!.bufferMinutes * 60 * 32000
            val bufferFile = File(filesDir, "buffer.pcm")
            ringBuffer = PcmRingBuffer(bufferFile, capacity)
            ringBuffer?.reset()

            // Start call monitoring
            callMonitor?.start()

            // Open initial HOTWORD source
            openSource(SOURCE_HOTWORD, capture)
            currentSource = SOURCE_HOTWORD

            // Set mic listener
            capture.setMicListener(object : com.echora.app.IMicStateCallback.Stub() {
                override fun onMicDisabledChanged(disabled: Boolean) {
                    if (disabled) updateState(CaptureState.MicDisabled)
                    else if (isRunning) updateStateForCurrentSource()
                }
            })

            updateState(CaptureState.Recording)
            @Suppress("WrongConstant")
            startForeground(NOTIFICATION_ID, buildNotification("In registrazione..."), FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

            // Watch call state changes
            val callStateJob = scope.launch {
                callMonitor?.isCallActive?.collect { isCall: Boolean ->
                    if (!isRunning) return@collect
                    handleCallStateChange(isCall, capture)
                }
            }

            // Capture loop
            val chunk = ByteArray(CHUNK_SIZE)
            while (isRunning) {
                val n = capture.read(chunk, chunk.size)
                when {
                    n > 0 -> {
                        val rb = ringBuffer
                        if (rb != null) {
                            rb.append(chunk, 0, n)
                        }
                        consecutiveZeroFrames = 0
                        updateStateForCurrentSource()
                    }
                    n == 0 -> {
                        // No data available, continue
                    }
                    n < 0 -> {
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

                // Update notification every ~5 seconds
                if (System.currentTimeMillis() % 5000 < 100) {
                    updateNotification("In registrazione... ${rb?.filledBytes()?.div(32000) ?: 0}s")
                }

                delay(10)
            }

            // Cleanup on loop exit
            callStateJob.cancel()
            capture.release()
            ringBuffer?.close()
            ringBuffer = null
            captureRef = null
            stopForeground(true)
            stopSelf()
            isRunning = false
            releaseWakeLock()
            updateState(CaptureState.Idle)
        }
    }

    private fun handleCallStateChange(isCall: Boolean, capture: com.echora.app.IEchoraCapture) {
        if (isCall) {
            // Call started - switch to call source
            if (currentSource != SOURCE_HOTWORD) return // Already on call source

            Log.d("EchoraCapture", "Call started, switching to call source")
            updateState(CaptureState.CallRecording)

            // Try VOICE_CALL first, then REMOTE_SUBMIX
            var success = false
            for (source in listOf(SOURCE_VOICE_CALL, SOURCE_REMOTE_SUBMIX)) {
                try {
                    capture.release()
                    capture.open(source, 16000, 1, 128000)
                    currentSource = source
                    success = true
                    Log.d("EchoraCapture", "Switched to call source: $source")
                    break
                } catch (e: Exception) {
                    Log.w("EchoraCapture", "Failed to open source $source", e)
                }
            }

            if (!success) {
                // Fallback to HOTWORD
                Log.w("EchoraCapture", "Call sources failed, staying on HOTWORD")
                try {
                    capture.release()
                    capture.open(SOURCE_HOTWORD, 16000, 1, 128000)
                    currentSource = SOURCE_HOTWORD
                } catch (e: Exception) {
                    Log.e("EchoraCapture", "Failed to reopen HOTWORD", e)
                }
            }
        } else {
            // Call ended - switch back to HOTWORD
            if (currentSource == SOURCE_HOTWORD) return // Already on HOTWORD

            Log.d("EchoraCapture", "Call ended, switching back to HOTWORD")
            try {
                capture.release()
                capture.open(SOURCE_HOTWORD, 16000, 1, 128000)
                currentSource = SOURCE_HOTWORD
                updateState(CaptureState.Recording)
            } catch (e: Exception) {
                Log.e("EchoraCapture", "Failed to reopen HOTWORD after call", e)
                updateState(CaptureState.Error("Errore riapertura microfono: ${e.message}"))
            }
        }
    }

    private fun openSource(source: Int, capture: com.echora.app.IEchoraCapture) {
        try {
            capture.open(source, 16000, 1, 128000)
        } catch (e: Exception) {
            Log.e("EchoraCapture", "Failed to open source $source", e)
            throw e
        }
    }

    private fun updateStateForCurrentSource() {
        val newState = when (currentSource) {
            SOURCE_HOTWORD -> CaptureState.Recording
            SOURCE_VOICE_CALL, SOURCE_REMOTE_SUBMIX -> CaptureState.CallRecording
            else -> CaptureState.Recording
        }
        if (CaptureStates.current.value != newState) {
            updateState(newState)
        }
    }

    private fun stopCapture() {
        if (!isRunning) return
        isRunning = false
        scope.cancel()
        captureRef?.release()
        ringBuffer?.close()
        ringBuffer = null
        captureRef = null
        callMonitor?.stop()
        stopForeground(true)
        stopSelf()
        releaseWakeLock()
        updateState(CaptureState.Idle)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Echora::CaptureWakeLock")
        wakeLock?.acquire()
    }

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

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Echora")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateState(state: CaptureState) {
        CaptureStates.current.value = state
    }
}