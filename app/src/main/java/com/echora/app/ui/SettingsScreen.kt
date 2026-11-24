package com.echora.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echora.app.capture.CaptureService
import com.echora.app.capture.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bufferMinutes = mutableStateOf(settings.bufferMinutes)
    val listeningEnabled = mutableStateOf(settings.listeningEnabled)
    val startOnBoot = mutableStateOf(settings.startOnBoot)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Header
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Impostazioni", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Configura Echora", fontSize = 16.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Buffer minutes slider
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Minuti di buffer", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("${bufferMinutes.value} min (${(bufferMinutes.value * 32000 * 60 / 1024 / 1024).toInt()} MB)", fontSize = 14.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = bufferMinutes.value.toFloat(),
                    onValueChange = { bufferMinutes.value = it.roundToInt().coerceIn(1, 60) },
                    valueRange = 1f..60f,
                    steps = 59,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1 min", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("60 min", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Listening enabled switch
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Ascolto attivo", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Avvia cattura automaticamente all'avvio app", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = listeningEnabled.value,
                    onCheckedChange = { listeningEnabled.value = it },
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        // Start on boot switch
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Avvio al boot", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Avvia automaticamente il servizio all'accensione del telefono", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = startOnBoot.value,
                    onCheckedChange = { startOnBoot.value = it },
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        // Save button
        Button(
            onClick = {
                // Only save if changed
                val needRestart = bufferMinutes.value != settings.bufferMinutes
                if (needRestart) {
                    settings.bufferMinutes = bufferMinutes.value
                }
                if (listeningEnabled.value != settings.listeningEnabled) {
                    settings.listeningEnabled = listeningEnabled.value
                }
                if (startOnBoot.value != settings.startOnBoot) {
                    settings.startOnBoot = startOnBoot.value
                }
                if (needRestart) {
                    // Restart capture service to resize buffer
                    CoroutineScope(Dispatchers.IO).launch {
                        CaptureService.stop(context)
                        if (settings.listeningEnabled) {
                            CaptureService.start(context)
                        }
                    }
                }
                onBack()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Salva", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary)
        }
    }
}