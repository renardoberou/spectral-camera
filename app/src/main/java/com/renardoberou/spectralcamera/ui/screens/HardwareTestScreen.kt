package com.renardoberou.spectralcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.renardoberou.spectralcamera.core.HardwareTestState
import com.renardoberou.spectralcamera.core.camera.CameraController
import com.renardoberou.spectralcamera.core.state.SpectralViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareTestScreen(
    viewModel: SpectralViewModel,
    cameraController: CameraController,
) {
    val state by viewModel.hardwareState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Hardware test") },
            actions = {
                IconButton(onClick = { viewModel.resetHardwareTest() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Reset test")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How to test", style = MaterialTheme.typography.titleMedium)
                    Text("Point a TV remote at the camera and press a button. If the camera sees the remote LED flashing, the app will report minor near-IR leakage detected.")
                    Text("True infrared still requires external hardware or camera modification — this app only simulates IR with the normal phone camera unless an external device is connected.")
                    Button(onClick = { viewModel.resetHardwareTest() }) { Text("Start / reset detection") }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text(state.statusMessage, color = if (state.detected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                    LinearProgressIndicator(progress = { state.confidence }, modifier = Modifier.fillMaxWidth())
                    Text("Peak luma: ${String.format("%.2f", state.peakLuma)} • Frames: ${state.framesAnalyzed}")
                    Text(state.instruction, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Capability note", style = MaterialTheme.typography.titleMedium)
                    Text("External IR / thermal support is only a framework here. Without external hardware, the app stays in simulated IR mode.")
                }
            }
        }
    }
}
