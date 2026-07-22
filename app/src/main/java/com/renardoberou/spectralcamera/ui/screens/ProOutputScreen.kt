package com.renardoberou.spectralcamera.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.renardoberou.spectralcamera.core.CameraCapabilities
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.state.SpectralViewModel

/** Photographer-facing output controls, kept separate from the live HUD. */
@Composable
fun ProOutputScreen(
    viewModel: SpectralViewModel,
    settings: CameraSettings,
    capabilities: CameraCapabilities?,
) {
    val rawSupported = capabilities?.rawJpegCaptureSupported == true

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Pro output",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Choose where capture time is spent. Every mode uses the same film model; only source resolution, render order, and final file size change.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("Processed output", style = MaterialTheme.typography.titleMedium)
            OutputMode.values().forEach { mode ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = if (settings.outputMode == mode) 4.dp else 1.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = settings.outputMode == mode,
                            onClick = { viewModel.setOutputMode(mode) },
                            label = { Text(mode.label, fontWeight = FontWeight.SemiBold) },
                        )
                        Text(mode.description, style = MaterialTheme.typography.bodySmall)
                        val detail = when (mode) {
                            OutputMode.FULL_RESOLUTION ->
                                "Maximum-quality JPEG source (quality 100). Output size is limited only by the camera stream and GPU texture size."
                            OutputMode.HQ_1080 ->
                                "The high-resolution source is cropped to 16:9, rendered through the film engine, then progressively reduced to exact Full HD."
                            OutputMode.FAST_1080 ->
                                "Requests a lower-latency 16:9 JPEG source near 1920×1080, then normalizes mod-16 sizes such as 1920×1088 to exact Full HD."
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            Text("Source files", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.saveOriginal,
                    onClick = { viewModel.setSaveOriginal(!settings.saveOriginal) },
                    label = { Text("Original JPEG") },
                )
                FilterChip(
                    selected = settings.saveRawSidecar && rawSupported,
                    enabled = rawSupported,
                    onClick = { viewModel.setSaveRawSidecar(!settings.saveRawSidecar) },
                    label = { Text(if (rawSupported) "RAW DNG sidecar" else "RAW unavailable") },
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when {
                            capabilities == null -> "RAW capability is checked when the camera session is ready."
                            rawSupported -> "This camera reports simultaneous RAW DNG + JPEG support."
                            else -> "This camera/session does not support simultaneous RAW DNG + JPEG capture."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "RAW is an untouched DNG sidecar for external development. The current processed Aerochrome/IR export still uses the companion JPEG bitmap so preview and saved look remain aligned. A future cycle can develop DNG data inside Spectral Camera.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (settings.saveRawSidecar && settings.outputMode == OutputMode.FAST_1080) {
                        Text(
                            text = "RAW sidecar capture forces the high-quality sensor source; the processed result is still exported at exact Full HD.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Text(
                text = "Selected: ${settings.outputMode.label} • ${if (settings.saveOriginal) "JPEG original on" else "processed only"} • ${if (settings.saveRawSidecar && rawSupported) "DNG on" else "DNG off"}",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
