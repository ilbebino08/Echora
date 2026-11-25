package com.echora.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.echora.app.capture.CaptureService
import com.echora.app.capture.Settings
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bufferMinutes by rememberSaveable { mutableStateOf(settings.bufferMinutes) }
    var listeningEnabled by rememberSaveable { mutableStateOf(settings.listeningEnabled) }
    var startOnBoot by rememberSaveable { mutableStateOf(settings.startOnBoot) }
    var recordCalls by rememberSaveable { mutableStateOf(settings.recordCalls) }
    val changed = bufferMinutes != settings.bufferMinutes ||
        listeningEnabled != settings.listeningEnabled || startOnBoot != settings.startOnBoot ||
        recordCalls != settings.recordCalls
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
            start = 22.dp, end = 22.dp, top = 12.dp, bottom = 24.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("‹  Indietro")
                }
                Text("Impostazioni", style = type.headlineLarge)
            }

            Surface(
                color = colors.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Durata del buffer", style = type.titleMedium)
                    Text(
                        "$bufferMinutes min",
                        modifier = Modifier.padding(top = 4.dp),
                        style = type.headlineSmall,
                        color = colors.primary
                    )
                    Slider(
                        value = bufferMinutes.toFloat(),
                        onValueChange = { bufferMinutes = it.roundToInt() },
                        valueRange = 1f..60f,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Durata del buffer in minuti" }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1 min", style = type.labelSmall, color = colors.onSurfaceVariant)
                        Text("60 min", style = type.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }

            Surface(
                color = colors.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingSwitch(
                        label = "Ascolto attivo",
                        description = "Avvia l’ascolto quando apri Echora.",
                        checked = listeningEnabled,
                        onCheckedChange = { listeningEnabled = it }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    SettingSwitch(
                        label = "Avvio all’accensione",
                        description = "Riprendi l’ascolto dopo un riavvio, se Shizuku è attivo.",
                        checked = startOnBoot,
                        onCheckedChange = { startOnBoot = it }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    SettingSwitch(
                        label = "Registrazione chiamate",
                        description = "Registra telefonate e chiamate VoIP. Se disattivata, l’ascolto si sospende durante la chiamata.",
                        checked = recordCalls,
                        onCheckedChange = { recordCalls = it }
                    )
                }
            }

            Button(
                onClick = {
                    val resize = bufferMinutes != settings.bufferMinutes
                    if (resize) settings.bufferMinutes = bufferMinutes
                    if (listeningEnabled != settings.listeningEnabled) settings.listeningEnabled = listeningEnabled
                    if (startOnBoot != settings.startOnBoot) settings.startOnBoot = startOnBoot
                    if (recordCalls != settings.recordCalls) settings.recordCalls = recordCalls
                    if (resize) {
                        CaptureService.stop(context)
                        if (settings.listeningEnabled) CaptureService.start(context)
                    }
                    onBack()
                },
                enabled = changed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = MaterialTheme.shapes.medium
            ) { Text("Salva modifiche") }
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
