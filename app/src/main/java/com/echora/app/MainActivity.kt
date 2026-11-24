package com.echora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echora.app.privileged.ShellConnection
import com.echora.app.privileged.ShellConnection.ShellStatus
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val shellConnection = ShellConnection(this)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            shellConnection.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        status = shellConnection.status.collectAsStateWithLifecycle().value,
                        onRequestPermission = { requestPermission() },
                        onTestCapture = { testCapture() }
                    )
                }
            }
        }
        checkAndRequestPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        shellConnection.stop()
    }

    private fun checkAndRequestPermission() {
        if (Shizuku.checkSelfPermission() == 0) {
            shellConnection.start()
        } else if (Shizuku.isPreV11()) {
            Log.w("EchoraMain", "Shizuku pre-v11, not supported")
        } else {
            requestPermission()
        }
    }

    private fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }

    private fun testCapture() {
        val capture = shellConnection.capture()
        if (capture == null) {
            Log.w("EchoraMain", "Capture service not ready")
            return
        }
        Thread {
            try {
                capture.open(1999, 16000, 1, 128000)
                val buffer = ByteArray(3200)
                val deadline = System.currentTimeMillis() + 3000
                var sumSquares = 0L
                var samples = 0L
                var totalRead = 0
                while (System.currentTimeMillis() < deadline) {
                    val n = capture.read(buffer, buffer.size)
                    if (n > 0) {
                        totalRead += n
                        for (i in 0 until n step 2) {
                            val sample = (buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)
                            sumSquares += (sample.toLong() * sample)
                            samples++
                        }
                    } else if (n < 0) {
                        break
                    }
                }
                val rms = if (samples > 0) Math.sqrt(sumSquares.toDouble() / samples) else 0.0
                Log.d("ECHORA", "read=$totalRead rms=${rms.toLong()}")
            } catch (e: Exception) {
                Log.e("EchoraMain", "Test capture failed", e)
            }
        }.start()
    }

    companion object {
        const val REQUEST_CODE = 42
    }
}

@Composable
fun MainScreen(
    status: ShellStatus,
    onRequestPermission: () -> Unit,
    onTestCapture: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Echora",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Stato: ${status.name}",
            fontSize = 24.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        when (status) {
            ShellStatus.NO_PERMISSION -> Button(onClick = onRequestPermission) {
                Text("Concedi permesso Shizuku", fontSize = 18.sp)
            }
            ShellStatus.CONNECTING -> Text("Connessione in corso...", fontSize = 18.sp)
            ShellStatus.READY -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Pronto per la cattura", fontSize = 18.sp)
                    Button(onClick = onTestCapture) {
                        Text("Test cattura (3s)", fontSize = 18.sp)
                    }
                }
            }
            ShellStatus.ERROR -> Text("Errore connessione", fontSize = 18.sp, color = androidx.compose.ui.graphics.Color.Red)
            ShellStatus.UNSUPPORTED -> Text("Shizuku non supportato", fontSize = 18.sp, color = androidx.compose.ui.graphics.Color.Red)
            else -> Text("Non pronto", fontSize = 18.sp)
        }
    }
}