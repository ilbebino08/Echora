package com.echora.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echora.app.audio.PcmRingBuffer
import com.echora.app.capture.CaptureService
import com.echora.app.capture.CaptureState
import com.echora.app.capture.CaptureStates
import com.echora.app.capture.Settings
import com.echora.app.save.Saver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
fun EchoraScreen(
    shellStatus: com.echora.app.privileged.ShellConnection.ShellStatus,
    settings: Settings,
    onOpenSettings: () -> Unit,
    onSaveComplete: (SaveRequest) -> Unit
) {
    val captureState = CaptureStates.current.collectAsStateWithLifecycle().value
    val ringBufferBytes = rememberSaveable { mutableStateOf(0L) }
    val saveRequest = rememberSaveable { mutableStateOf<SaveRequest?>(null) }
    val showDurationMenu = rememberSaveable { mutableStateOf(false) }
    val selectedDuration = rememberSaveable { mutableStateOf(5) } // default 5 min

    val availableDurations = listOf(1, 2, 5, 10, 15, 30)
        .filter { it <= settings.bufferMinutes }
    if (selectedDuration.value !in availableDurations && availableDurations.isNotEmpty()) {
        selectedDuration.value = availableDurations.maxOrNull() ?: 1
    }

    val (statusText, statusColor) = when (captureState) {
        is CaptureState.Recording -> "In registrazione..." to Color.Green
        is CaptureState.CallRecording -> "In registrazione (chiamata)..." to Color.Cyan
        is CaptureState.Connecting -> "Connessione..." to Color.Yellow
        is CaptureState.MicDisabled -> "Microfono disattivato" to Color.Red
        is CaptureState.Idle -> "In pausa" to Color.Gray
        is CaptureState.Error -> captureState.reason to Color.Red
    }

    val isRecording = captureState is CaptureState.Recording || captureState is CaptureState.CallRecording
    val bufferMinutes = if (ringBufferBytes.value > 0) {
        (ringBufferBytes.value / 32000 / 60).coerceAtMost(settings.bufferMinutes.toLong()).toInt()
    } else 0

    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = statusColor.copy(alpha = 0.1f),
                contentColor = statusColor
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = statusText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isRecording && bufferMinutes > 0) {
                    Text(
                        text = "Buffer: $bufferMinutes min / ${settings.bufferMinutes} min",
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Duration selector
        if (isRecording && availableDurations.isNotEmpty()) {
            val buttonText = "Salva ultimi ${selectedDuration.value} min ▼"
            androidx.compose.material3.OutlinedButton(
                onClick = { showDurationMenu.value = !showDurationMenu.value },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonText, fontSize = 16.sp)
            }
            if (showDurationMenu.value) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { showDurationMenu.value = false }
                ) {
                    availableDurations.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("Ultimi $minutes minuti") },
                            onClick = {
                                selectedDuration.value = minutes
                                showDurationMenu.value = false
                            }
                        )
                    }
                }
            }
        }

        // Save button
        if (isRecording && availableDurations.isNotEmpty()) {
            Button(
                onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val bufferFile = File(context.filesDir, "buffer.pcm")
                            val bytesNeeded = selectedDuration.value * 60L * 32000
                            val pcm = PcmRingBuffer.getLastBytes(bufferFile, bytesNeeded)
                            val uri = Saver.save(
                                context = context,
                                pcm = pcm,
                                minutes = selectedDuration.value
                            )
                            saveRequest.value = SaveRequest(selectedDuration.value, uri)
                        } catch (e: Exception) {
                            saveRequest.value = SaveRequest(selectedDuration.value, error = e.message)
                        }
                        onSaveComplete(saveRequest.value!!)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Salva gli ultimi ${selectedDuration.value} minuti", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary)
            }
        }

        // Pause/Resume button
        Button(
            onClick = {
                if (isRecording) CaptureService.stop(context)
                else CaptureService.start(context)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = if (isRecording) androidx.compose.material3.MaterialTheme.colorScheme.errorContainer else androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                if (isRecording) "Pausa" else "Riprendi",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer else androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // Shell status info
        if (shellStatus != com.echora.app.privileged.ShellConnection.ShellStatus.READY) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Yellow.copy(alpha = 0.1f),
                    contentColor = Color.Yellow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Servizio: ${shellStatus.name}",
                        fontSize = 14.sp
                    )
                    if (shellStatus == com.echora.app.privileged.ShellConnection.ShellStatus.NO_PERMISSION) {
                        Button(onClick = { /* handled by MainActivity */ }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("Concedi permesso Shizuku")
                        }
                    }
                }
            }
        }

        // Settings button
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            Text("Impostazioni", fontSize = 16.sp)
        }
    }
}