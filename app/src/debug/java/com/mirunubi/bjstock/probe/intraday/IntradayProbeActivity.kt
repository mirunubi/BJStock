package com.mirunubi.bjstock.probe.intraday

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Debug-only probe screen. Reachable only by explicit component start (e.g. adb am start); no launcher entry. */
class IntradayProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val evidencePath = getExternalFilesDir(ProbeEvidenceStore.DIRECTORY_NAME)?.absolutePath ?: "unavailable"
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen(
                        evidencePath = evidencePath,
                        onStart = { restMinuteBars ->
                            ContextCompat.startForegroundService(this, IntradayProbeService.startIntent(this, restMinuteBars))
                        },
                        onStop = { startService(IntradayProbeService.stopIntent(this)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbeScreen(evidencePath: String, onStart: (Boolean) -> Unit, onStop: () -> Unit) {
    val state by ProbeStateHolder.state.collectAsStateWithLifecycle()
    var restMinuteBars by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("BJStock 12-B2 Probe", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ProbeScopeBanner.LINES.forEach { Text(it, fontWeight = FontWeight.SemiBold) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onStart(restMinuteBars) }, enabled = state.canStart) { Text("Start") }
            OutlinedButton(onClick = onStop, enabled = state.canStop) { Text("Stop") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = restMinuteBars, onCheckedChange = { restMinuteBars = it }, enabled = state.canStart)
            Text("Also observe REST minute bars (FHKST03010200)")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Request notification permission")
            }
        }

        Text("State: ${state.status.name}${if (state.sessionActive) " (session active)" else ""}")
        Text("Session id: ${state.sessionId ?: "-"}")
        Text("Evidence location: ${state.evidenceDir ?: evidencePath}")
        Text("Last error / message: ${state.lastMessage}")
        Text("Frames: ${state.frames}  Records: ${state.records}  Frame issues: ${state.frameIssues}")
        Text("Reconnects: ${state.reconnects}  Heartbeat gaps: ${state.heartbeatGaps}  Possible gaps: ${state.possibleGaps}")
    }
}

object ProbeScopeBanner {
    val LINES = listOf("VIRTUAL ONLY", "KRX / J ONLY", "005930 + 000660", "NO TRADING")
}
