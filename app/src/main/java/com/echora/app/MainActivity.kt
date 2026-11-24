package com.echora.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echora.app.capture.CaptureService
import com.echora.app.capture.CaptureState
import com.echora.app.capture.CaptureStates
import com.echora.app.capture.Settings
import com.echora.app.privileged.ShellConnection
import com.echora.app.privileged.ShellConnection.ShellStatus
import com.echora.app.ui.EchoraScreen
import com.echora.app.ui.SaveRequest
import com.echora.app.ui.SettingsScreen
import com.echora.app.save.Saver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val shellConnection = ShellConnection(this)
    private val settings = Settings.create(this)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            shellConnection.start()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("EchoraMain", "Notification permission granted: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        shellConnection = shellConnection,
                        settings = settings,
                        onRequestPermission = { requestPermission() },
                        onShareFile = ::shareFile
                    )
                }
            }
        }
        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        shellConnection.stop()
    }

    private fun checkAndRequestPermissions() {
        // Request Shizuku permission
        if (Shizuku.checkSelfPermission() == 0) {
            shellConnection.start()
        } else if (Shizuku.isPreV11()) {
            Log.w("EchoraMain", "Shizuku pre-v11, not supported")
        } else {
            requestPermission()
        }

        // Request notification permission (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start capture service if enabled
        if (settings.listeningEnabled) {
            CaptureService.start(this)
        }
    }

    private fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }

    private fun shareFile(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(Intent.createChooser(intent, "Condividi registrazione"))
    }

    companion object {
        const val REQUEST_CODE = 42
    }
}

@Composable
fun MainScreen(
    shellConnection: ShellConnection,
    settings: Settings,
    onRequestPermission: () -> Unit,
    onShareFile: (Uri) -> Unit
) {
    val shellStatus = shellConnection.status.collectAsStateWithLifecycle().value
    val captureState = CaptureStates.current.collectAsStateWithLifecycle().value

    val snackbarHostState = rememberSaveable { SnackbarHostState() }
    val showSettings = rememberSaveable { mutableStateOf(false) }
    val pendingSaveResult = rememberSaveable { mutableStateOf<SaveRequest?>(null) }

    // Handle save result with LaunchedEffect (showSnackbar is suspend)
    val saveResult = pendingSaveResult.value
    if (saveResult != null) {
        LaunchedEffect(saveResult) {
            if (saveResult.error != null) {
                snackbarHostState.showSnackbar(
                    message = "Errore salvataggio: ${saveResult.error}",
                    actionLabel = "OK"
                )
            } else if (saveResult.uri != null) {
                val result = snackbarHostState.showSnackbar(
                    message = "Salvato: ${Saver.fileName(saveResult.minutes)}",
                    actionLabel = "Condividi"
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    onShareFile(saveResult.uri!!)
                }
            }
            pendingSaveResult.value = null
        }
    }

    if (showSettings.value) {
        SettingsScreen(
            settings = settings,
            onBack = { showSettings.value = false }
        )
    } else {
        EchoraScreen(
            shellStatus = shellStatus,
            settings = settings,
            onOpenSettings = { showSettings.value = true },
            onSaveComplete = { req ->
                pendingSaveResult.value = req
            }
        )
    }

    SnackbarHost(snackbarHostState)
}