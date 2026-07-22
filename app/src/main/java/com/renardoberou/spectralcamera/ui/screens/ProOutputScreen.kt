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
import com.renardoberou.spectralcamera.core.DoubleExposureMode
import com.renardoberou.spectralcamera.core.HdrCaptureMode
import com.renardoberou.spectralcamera.core.HdrToneMap
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.state.SpectralViewModel

@Composable
fun ProOutputScreen(
    viewModel: SpectralViewModel,
    settings: CameraSettings,
    capabilities: CameraCapabilities?,
) {
    val rawSidecarSupported = capabilities?.rawJpegCaptureSupported == true
    val jpegHdrSupported = capabilities?.hdrBracketSupported ?: true
    val trueRawHdrSupported = capabilities?.trueRawHdrSupported == true
    val ultraHdrPlatform = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val hdrActive = settings.hdrCaptureMode != HdrCaptureMode.OFF
    val rawHdrActive = settings.hdrCaptureMode == HdrCaptureMode.RAW_THREE_FRAME
    val jpegHdrActive = settings.hdrCaptureMode == HdrCaptureMode.THREE_FRAME
    val doubleExposureActive = settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED

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
                text = "The active capture method is always shown on the Live screen, in the saved message, and in the Gallery. HDR remains HDR when movement is detected; uncertain regions use motion-protected fusion instead of silently becoming Standard.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionTitle("Capture dynamic range")
            HdrCaptureMode.values().forEach { mode ->
                val enabled = when (mode) {
                    HdrCaptureMode.OFF -> true
                    HdrCaptureMode.THREE_FRAME -> jpegHdrSupported
                    HdrCaptureMode.RAW_THREE_FRAME -> trueRawHdrSupported
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = if (settings.hdrCaptureMode == mode && !doubleExposureActive) 4.dp else 1.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = settings.hdrCaptureMode == mode && !doubleExposureActive,
                            enabled = enabled,
                            onClick = { viewModel.setHdrCaptureMode(mode) },
                            label = { Text(mode.label, fontWeight = FontWeight.SemiBold) },
                        )
                        Text(mode.description, style = MaterialTheme.typography.bodySmall)
                        when (mode) {
                            HdrCaptureMode.OFF -> Unit
                            HdrCaptureMode.THREE_FRAME -> Text(
                                text = "Uses approximately −2 / 0 / +2 EV where possible. Wind, people, water, or uncertain camera movement no longer cancel the HDR capture: edges and moving detail stay anchored to the normal exposure while broad clipped highlights and deep shadows can still use the bracket.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            HdrCaptureMode.RAW_THREE_FRAME -> Text(
                                text = if (trueRawHdrSupported) {
                                    "Fixes ISO, brackets shutter, merges black-subtracted RAW_SENSOR Bayer radiance before demosaic, and uses the same motion-protected reference anchoring when alignment is uncertain."
                                } else {
                                    "Unavailable on this active lens. It requires RAW_SENSOR, MANUAL_SENSOR, Bayer CFA, black/white-level metadata, and capture colour metadata."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }

            SectionTitle("Creative Standard capture")
            FilterChip(
                selected = doubleExposureActive,
                onClick = {
                    viewModel.setDoubleExposureMode(
                        if (doubleExposureActive) DoubleExposureMode.OFF
                        else DoubleExposureMode.FILM_BALANCED,
                    )
                },
                label = { Text("Double Exposure") },
            )
            Text(
                text = "First press stores frame 1 and places a transparent guide over the live view. Recompose, then press again. The two half-exposures are combined in linear light before the selected Aerochrome or monochrome film look is applied once. Enabling this mode turns HDR and RAW sidecar capture off.",
                style = MaterialTheme.typography.bodySmall,
            )

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
                    text = "The tone map normalizes merged radiance before vegetation, sky, water and skin classification. It is not a local-contrast HDR effect added after the film look.",
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
                            OutputMode.FULL_RESOLUTION -> when {
                                doubleExposureActive ->
                                    "Both source frames are held at a safe high-resolution working size, combined, and then sent through the same adaptive GPU export path."
                                rawHdrActive ->
                                    "Uses the RAW stream's practical size. If the mobile GPU cannot allocate the requested offscreen film-render target, capture retries at descending safe resolutions instead of failing."
                                jpegHdrActive ->
                                    "Uses a high-quality memory-bounded JPEG bracket and adaptive offscreen rendering."
                                else ->
                                    "Maximum-quality source. The largest completed export depends on the camera stream, GPU texture size, and framebuffer memory."
                            }
                            OutputMode.HQ_1080 ->
                                "Merges or combines from a high-resolution source, renders the film look, then progressively reduces to exact Full HD."
                            OutputMode.FAST_1080 ->
                                "Uses a lower-latency 16:9 source near Full HD and normalizes aligned dimensions such as 1920×1088 to exact Full HD."
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
                            !hdrActive -> "Enable an HDR capture mode first"
                            else -> "Ultra HDR JPEG"
                        },
                    )
                },
            )
            Text(
                text = if (ultraHdrPlatform) {
                    "Creates a backward-compatible SDR JPEG plus a gain map regenerated after the Aerochrome/IR transform."
                } else {
                    "This device can save the tone-mapped SDR result but cannot attach an Android Ultra HDR gain map."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle("Source files")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.saveOriginal && !rawHdrActive,
                    enabled = !rawHdrActive,
                    onClick = { viewModel.setSaveOriginal(!settings.saveOriginal) },
                    label = {
                        Text(
                            when {
                                doubleExposureActive -> "Save both source frames"
                                rawHdrActive -> "No JPEG original in RAW mode"
                                jpegHdrActive -> "Reference JPEG"
                                else -> "Original JPEG"
                            },
                        )
                    },
                )
                val rawToggleEnabled = when {
                    doubleExposureActive -> false
                    rawHdrActive -> trueRawHdrSupported
                    jpegHdrActive -> false
                    else -> rawSidecarSupported
                }
                FilterChip(
                    selected = settings.saveRawSidecar && rawToggleEnabled,
                    enabled = rawToggleEnabled,
                    onClick = { viewModel.setSaveRawSidecar(!settings.saveRawSidecar) },
                    label = {
                        Text(
                            when {
                                doubleExposureActive -> "RAW unavailable in Double Exposure"
                                rawHdrActive -> "Save RAW bracket DNGs"
                                jpegHdrActive -> "RAW unavailable in JPEG HDR"
                                rawSidecarSupported -> "RAW DNG sidecar"
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
                            doubleExposureActive -> "Double Exposure is a two-step Standard capture. The first frame remains pending until the second succeeds or you cancel it from the Live screen."
                            rawHdrActive -> "True RAW HDR merges sensor mosaics before demosaic. Saving bracket DNGs is optional."
                            jpegHdrActive -> "JPEG HDR is available on more lenses and is lighter on storage. Motion-protected areas retain the normal exposure rather than cancelling the entire HDR file."
                            capabilities == null -> "RAW and bracket capability are checked when the camera session is ready."
                            rawSidecarSupported -> "This lens supports a Standard RAW DNG + JPEG sidecar workflow."
                            else -> "This active lens does not support simultaneous RAW DNG + JPEG capture."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Text(
                text = "Selected: ${settings.requestedCaptureLabel} • ${settings.hdrToneMap.label} • ${settings.outputMode.label} • ${if (settings.ultraHdrExport && hdrActive && ultraHdrPlatform) "Ultra HDR" else "SDR JPEG"}",
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
