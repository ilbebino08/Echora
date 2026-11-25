package com.echora.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echora.app.capture.CallKind
import com.echora.app.capture.CaptureService
import com.echora.app.capture.CaptureState
import com.echora.app.capture.CaptureStates
import com.echora.app.capture.Settings
import com.echora.app.privileged.ShellConnection.ShellStatus
import com.echora.app.save.Saver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EchoraScreen(
    shellStatus: ShellStatus,
    settings: Settings,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onSaveComplete: (SaveRequest) -> Unit,
    modifier: Modifier = Modifier
) {
    val captureState by CaptureStates.current.collectAsStateWithLifecycle()
    val isRecording = captureState is CaptureState.Recording || captureState is CaptureState.CallRecording
    val pausedForCall = captureState == CaptureState.PausedForCall
    val canSave = isRecording || pausedForCall
    val micDisabled = captureState is CaptureState.MicDisabled
    val error = captureState as? CaptureState.Error
    val connecting = error == null &&
        (captureState is CaptureState.Connecting || shellStatus == ShellStatus.CONNECTING)
    val capacity = settings.bufferMinutes
    val durations = listOf(1, 2, 5, 10, 15, 30).filter { it <= capacity }
    var selectedDuration by rememberSaveable { mutableStateOf(5) }
    val minutes = selectedDuration.takeIf { it in durations } ?: durations.last()
    var showDurationMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val call = captureState as? CaptureState.CallRecording
    val status = when {
        call != null -> {
            val label = if (call.kind == CallKind.TELEPHONE) "Chiamata telefonica" else "Chiamata VoIP"
            when {
                !call.hasCallAudio -> "$label · solo microfono"
                !call.hasMicrophone -> "$label · solo interlocutore"
                else -> label
            }
        }
        isRecording -> "In registrazione"
        pausedForCall -> "Ascolto sospeso durante la chiamata"
        micDisabled -> "Microfono disattivato"
        error != null -> error.reason
        connecting -> "Connessione in corso"
        !settings.listeningEnabled -> "Ascolto disattivato"
        shellStatus != ShellStatus.READY -> "Shizuku non disponibile"
        else -> "In pausa"
    }
    val tone = if (isRecording && call?.hasCallAudio != false) colors.inversePrimary else colors.inverseOnSurface

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Echora", Modifier.weight(1f), style = type.headlineLarge)
                OutlinedButton(
                    onClick = onOpenSettings,
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium
                ) { Text("Impostazioni") }
            }

            BufferDeck(capacity, status, tone)

            Surface(
                shape = MaterialTheme.shapes.large,
                color = colors.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Salva una registrazione", style = type.headlineSmall)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showDurationMenu = true },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(if (minutes == 1) "Ultimo minuto" else "Ultimi $minutes minuti")
                            Text("⌄", Modifier.padding(start = 10.dp))
                        }
                        DropdownMenu(
                            expanded = showDurationMenu,
                            onDismissRequest = { showDurationMenu = false },
                            modifier = Modifier.width(maxWidth),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            durations.forEach { duration ->
                                DropdownMenuItem(
                                    text = { Text(if (duration == 1) "1 minuto" else "$duration minuti") },
                                    modifier = Modifier.semantics { selected = duration == minutes },
                                    trailingIcon = {
                                        if (duration == minutes) Text("✓", color = colors.primary)
                                    },
                                    onClick = {
                                        selectedDuration = duration
                                        showDurationMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val uri = withContext(Dispatchers.IO) {
                                        val pcm = CaptureService.readRecentAudio(minutes * 60L * 32000)
                                        Saver.save(context, pcm, minutes)
                                    }
                                    onSaveComplete(SaveRequest(minutes, uri))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    onSaveComplete(SaveRequest(minutes, error = failure.message))
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = canSave && !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        shape = MaterialTheme.shapes.medium
                    ) { Text(if (saving) "Salvataggio in corso…" else "Salva in WAV") }
                    if (!canSave) {
                        Text(
                            "Il salvataggio è disponibile durante l’ascolto.",
                            style = type.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            when {
                shellStatus == ShellStatus.NO_PERMISSION -> {
                    TextButton(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text("Concedi il permesso Shizuku")
                    }
                }
                shellStatus == ShellStatus.UNSUPPORTED -> {
                    Text("Aggiorna Shizuku per avviare l’ascolto.", style = type.bodyMedium)
                }
                shellStatus == ShellStatus.ERROR || shellStatus == ShellStatus.NOT_READY -> {
                    if (!canSave && !connecting && settings.listeningEnabled) {
                        Text(
                            "Controlla che Shizuku sia in esecuzione.",
                            style = type.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            if (canSave || settings.listeningEnabled && (shellStatus == ShellStatus.READY || error != null)) {
                OutlinedButton(
                    onClick = {
                        if (canSave) CaptureService.stop(context) else CaptureService.start(context)
                    },
                    enabled = !saving && !connecting && !micDisabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(when {
                        isRecording -> "Metti in pausa"
                        pausedForCall -> "Ferma l’ascolto"
                        error != null -> "Riprova l’ascolto"
                        else -> "Riprendi l’ascolto"
                    })
                }
            }
        }
    }
}

@Composable
private fun BufferDeck(capacity: Int, status: String, statusColor: Color) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val deck = colors.inverseSurface
    val deckText = colors.inverseOnSurface
    val accent = colors.inversePrimary
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = deck,
        contentColor = deckText,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.background(Brush.linearGradient(listOf(lerp(deck, accent, 0.10f), deck)))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.widthIn(min = 166.dp).heightIn(min = 166.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(166.dp)) {
                    val radius = size.minDimension / 2
                    drawCircle(deckText.copy(alpha = 0.28f), radius - 20.dp.toPx(), style = Stroke(1.dp.toPx()))
                    repeat(48) { index ->
                        val major = index % 4 == 0
                        rotate(index * 7.5f) {
                            drawLine(
                                color = if (major) accent.copy(alpha = 0.82f)
                                    else deckText.copy(alpha = 0.40f),
                                start = Offset(center.x, 2.dp.toPx()),
                                end = Offset(center.x, (if (major) 13.dp else 8.dp).toPx()),
                                strokeWidth = (if (major) 1.5.dp else 1.dp).toPx()
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("$capacity", style = type.displayMedium)
                    Text("MIN DI BUFFER", style = type.labelSmall, color = deckText)
                }
            }
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                Text(status, style = type.labelLarge, color = statusColor, textAlign = TextAlign.Center)
            }
        }
    }
}
