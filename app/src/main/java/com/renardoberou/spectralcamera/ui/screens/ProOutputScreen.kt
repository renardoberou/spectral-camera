package com.renardoberou.spectralcamera.ui.screens

import android.os.Build
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
import com.renardoberou.spectralcamera.core.HdrCaptureMode
import com.renardoberou.spectralcamera.core.HdrToneMap
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.state.SpectralViewModel

/** Photographer-facing capture and output controls, kept separate from the live HUD. */
@Composable
fun ProOutputScreen(
    viewModel: SpectralViewModel,
    settings: CameraSettings,
    capabilities: CameraCapabilities?,
) {
    val rawSupported = capabilities?.rawJpegCaptureSupported == true
    val hdrSupported = capabilities?.hdrBracketSupported != false
    val ultraHdrPlatform = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val hdrActive = settings.hdrCaptureMode == HdrCaptureMode.THREE_FRAME

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Pro imaging",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Capture strategy is separated from the film look. Computational HDR merges scene information before synthetic NIR and the selected Aerochrome or monochrome IR stock.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionTitle("Capture dynamic range")
            HdrCaptureMode.values().forEach { mode ->
                val enabled = mode == HdrCaptureMode.OFF || hdrSupported
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = if (settings.hdrCaptureMode == mode) 4.dp else 1.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = settings.hdrCaptureMode == mode,
                            enabled = enabled,
                            onClick = { viewModel.setHdrCaptureMode(mode) },
                            label = { Text(mode.label, fontWeight = FontWeight.SemiBold) },
                        )
                        Text(mode.description, style = MaterialTheme.typography.bodySmall)
                        if (mode == HdrCaptureMode.THREE_FRAME) {
                            Text(
                                text = "Uses approximately −2 / 0 / +2 EV where the camera range permits, aligns translation, deghosts toward the normal exposure, and merges in linear light. Best for static or slow scenes.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }

            if (hdrActive) {
                SectionTitle("HDR tone map before film")
                HdrToneMap.values().forEach { mode ->
                    FilterChip(
                        selected = settings.hdrToneMap == mode,
                        onClick = { viewModel.setHdrToneMap(mode) },
                        label = { Text(mode.label) },
                    )
                    Text(
                        text = mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Text(
                    text = "This is not an HDR effect applied after the film look. It normalizes merged scene radiance into the working range that feeds vegetation/sky classification and the film characteristic curve.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            SectionTitle("Processed output")
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
                            OutputMode.FULL_RESOLUTION -> if (hdrActive) {
                                "HDR uses a practical high-quality 12 MP-class bracket to avoid three 50 MP bitmaps exhausting phone memory. Standard capture can still request the sensor's largest JPEG stream."
                            } else {
                                "Maximum-quality JPEG source (quality 100). Output size is limited by the camera stream and GPU texture size."
                            }
                            OutputMode.HQ_1080 ->
                                "Each source is cropped to 16:9; HDR merges at high resolution; the film render is progressively reduced to exact Full HD."
                            OutputMode.FAST_1080 ->
                                "Requests a low-latency 16:9 source near 1920×1080 and normalizes aligned sizes such as 1920×1088 to exact Full HD."
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            SectionTitle("HDR display/export")
            FilterChip(
                selected = settings.ultraHdrExport && hdrActive && ultraHdrPlatform,
                enabled = hdrActive && ultraHdrPlatform,
                onClick = { viewModel.setUltraHdrExport(!settings.ultraHdrExport) },
                label = {
                    Text(
                        when {
                            !ultraHdrPlatform -> "Ultra HDR requires Android 14+"
                            !hdrActive -> "Enable Computational HDR first"
                            else -> "Ultra HDR JPEG"
                        },
                    )
                },
            )
            Text(
                text = if (ultraHdrPlatform) {
                    "Creates a backward-compatible SDR JPEG base plus a new gain map derived after the Aerochrome/IR transform. The in-app detail viewer switches the display window to HDR only when the decoded image actually contains a gain map."
                } else {
                    "This device can still save the tone-mapped SDR result, but Android versions before 14 cannot attach or display an Ultra HDR gain map."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle("Source files")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.saveOriginal,
                    onClick = { viewModel.setSaveOriginal(!settings.saveOriginal) },
                    label = { Text(if (hdrActive) "Reference JPEG" else "Original JPEG") },
                )
                FilterChip(
                    selected = settings.saveRawSidecar && rawSupported && !hdrActive,
                    enabled = rawSupported && !hdrActive,
                    onClick = { viewModel.setSaveRawSidecar(!settings.saveRawSidecar) },
                    label = {
                        Text(
                            when {
                                hdrActive -> "RAW unavailable during HDR"
                                rawSupported -> "RAW DNG sidecar"
                                else -> "RAW unavailable"
                            },
                        )
                    },
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
                            hdrActive -> "Computational HDR and RAW sidecar are mutually exclusive in this cycle. HDR records three JPEG exposures; a future RAW-burst pipeline would require three DNGs and a sensor-linear demosaic/merge."
                            capabilities == null -> "RAW and HDR bracket capability are checked when the camera session is ready."
                            rawSupported -> "This camera reports simultaneous RAW DNG + JPEG support for standard capture."
                            else -> "This camera/session does not support simultaneous RAW DNG + JPEG capture."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "The live preview remains a responsive single-frame rendering. HDR is a still-capture operation, so moving subjects may show reference-frame fallback in deghosted areas rather than a false high-range composite.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                text = "Selected: ${settings.hdrCaptureMode.label} • ${settings.hdrToneMap.label} • ${settings.outputMode.label} • ${if (settings.ultraHdrExport && hdrActive && ultraHdrPlatform) "Ultra HDR" else "SDR JPEG"}",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}
