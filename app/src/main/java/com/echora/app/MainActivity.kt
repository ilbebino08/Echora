package com.echora.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echora.app.capture.CaptureService
import com.echora.app.capture.Settings
import com.echora.app.privileged.ShellConnection
import com.echora.app.ui.EchoraScreen
import com.echora.app.ui.EchoraTheme
import com.echora.app.ui.SaveRequest
import com.echora.app.ui.SettingsScreen
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val shellConnection = ShellConnection(this)
    private val settings by lazy { Settings.create(this) }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        shellConnection.start()
        if (Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == 0) {
            startListeningIfEnabled()
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE && grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            shellConnection.start()
            startListeningIfEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EchoraTheme {
                MainScreen(
                    shellConnection = shellConnection,
                    settings = settings,
                    onRequestPermission = ::requestPermission,
                    onShareFile = ::shareFile
                )
            }
        }
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        shellConnection.stop(removeService = false)
        super.onDestroy()
    }

    private fun startListeningIfEnabled() {
        if (settings.listeningEnabled) {
            CaptureService.start(this)
        }
    }

    private fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE)
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
    val shellStatus by shellConnection.status.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingSaveResult by remember { mutableStateOf<SaveRequest?>(null) }

    BackHandler(enabled = showSettings) { showSettings = false }

    val saveResult = pendingSaveResult
    if (saveResult != null) {
        LaunchedEffect(saveResult) {
            val uri = saveResult.uri
            if (uri == null) {
                snackbarHostState.showSnackbar(
                    message = "Salvataggio non riuscito. Riprova.",
                    withDismissAction = true
                )
            } else {
                val result = snackbarHostState.showSnackbar(
                    message = "Registrazione salvata in Musica/Echora",
                    actionLabel = "Condividi",
                    withDismissAction = true
                )
                if (result == SnackbarResult.ActionPerformed) onShareFile(uri)
            }
            pendingSaveResult = null
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { insets ->
        if (showSettings) {
            SettingsScreen(
                settings = settings,
                onBack = { showSettings = false },
                modifier = Modifier.padding(insets)
            )
        } else {
            EchoraScreen(
                shellStatus = shellStatus,
                settings = settings,
                onOpenSettings = { showSettings = true },
                onRequestPermission = onRequestPermission,
                onSaveComplete = { pendingSaveResult = it },
                modifier = Modifier.padding(insets)
            )
        }
    }
}
