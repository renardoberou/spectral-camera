package com.renardoberou.spectralcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import com.renardoberou.spectralcamera.core.CameraCapabilities
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.CaptureResult
import com.renardoberou.spectralcamera.core.ChannelSwapMode
import com.renardoberou.spectralcamera.core.ManualAdjustments
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.camera.CameraController
import com.renardoberou.spectralcamera.core.state.SpectralViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCameraScreen(
    viewModel: SpectralViewModel,
    cameraController: CameraController,
    settings: CameraSettings,
    capabilities: CameraCapabilities?,
    galleryCount: Int,
    onCapture: suspend () -> CaptureResult,
    onOpenGallery: () -> Unit,
    onOpenHardware: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showPresets by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var showSaveNote by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var captureLabel by remember { mutableStateOf("Ready for capture") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    cameraController.focusAt(
                        offset.x,
                        offset.y,
                        size.width.toFloat(),
                        size.height.toFloat(),
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x59000000), Color.Transparent, Color(0x8C030208)),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "Spectral Camera",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${settings.sensorMode.label} • simulated IR by default",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Preset: ${settings.preset.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onOpenGallery, label = { Text("Gallery ($galleryCount)") }, leadingIcon = { Icon(Icons.Outlined.Collections, null) })
                    AssistChip(onClick = onOpenHardware, label = { Text("Hardware test") }, leadingIcon = { Icon(Icons.Outlined.Thermostat, null) })
                    AssistChip(onClick = { showPresets = true }, label = { Text("Presets") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) })
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (capabilities?.exposureSupported == true) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (capabilities.manualExposureSupported) {
                                SteppedControl(
                                    label = "Exposure mode",
                                    options = listOf("Auto" to 0f, "Manual" to 1f),
                                    value = if (settings.manualMode) 1f else 0f,
                                ) { mode -> viewModel.setManualMode(mode > 0.5f) }
                            }
                            if (settings.manualMode && capabilities.manualExposureSupported) {
                                val isoOptions = listOf(100, 200, 400, 800, 1600, 3200)
                                    .filter { iso ->
                                        val range = capabilities.isoRange
                                        range == null || (iso >= range.first && iso <= range.last)
                                    }
                                    .map { "ISO $it" to it.toFloat() }
                                SteppedControl(
                                    label = "ISO (AE off)",
                                    options = isoOptions,
                                    value = settings.manualIso.toFloat(),
                                ) { iso -> viewModel.setManualIso(iso.toInt()) }
                                val shutterOptions = listOf(
                                    "1/4000" to 250_000L, "1/2000" to 500_000L, "1/1000" to 1_000_000L,
                                    "1/500" to 2_000_000L, "1/250" to 4_000_000L, "1/125" to 8_000_000L,
                                    "1/60" to 16_666_667L, "1/30" to 33_333_333L, "1/15" to 66_666_667L,
                                    "1/8" to 125_000_000L,
                                ).filter { pair ->
                                    val range = capabilities.exposureTimeRange
                                    range == null || (pair.second >= range.first && pair.second <= range.last)
                                }
                                ShutterControl(
                                    options = shutterOptions,
                                    valueNs = settings.manualShutterNs,
                                ) { ns -> viewModel.setManualShutter(ns) }
                            } else {
                                val stopOptions = listOf(-2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f)
                                    .filter { it >= capabilities.minStops - 0.01f && it <= capabilities.maxStops + 0.01f }
                                    .map { formatStops(it) to it }
                                StopsRow(
                                    label = "Exposure compensation (stops)",
                                    options = stopOptions,
                                    value = settings.hardwareEv,
                                ) { stops ->
                                    viewModel.setHardwareEv(stops)
                                    cameraController.setExposureCompensation(capabilities.stopsToIndex(stops))
                                }
                            }
                            Text(
                                buildString {
                                    if (settings.manualMode && capabilities.manualExposureSupported) {
                                        append("AE off \u00b7 manual sensor")
                                    } else {
                                        append("ISO auto")
                                    }
                                    capabilities.aperture?.let { append("  \u00b7  f/" + String.format("%.1f", it)) }
                                    append("  \u00b7  fixed lens")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(captureLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { viewModel.setFrontFacing(!settings.frontFacing) }) {
                                Icon(Icons.Outlined.Cameraswitch, contentDescription = "Switch camera")
                            }

                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        captureLabel = "Capturing…"
                                        runCatching { onCapture() }
                                            .onSuccess {
                                                captureLabel = "Saved ${it.displayName}"
                                                viewModel.refreshGallery()
                                            }
                                            .onFailure { captureLabel = "Capture failed: ${it.message ?: it.javaClass.simpleName}" }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 26.dp, vertical = 18.dp),
                            ) {
                                Text("Capture")
                            }

                            IconButton(onClick = {
                                torchEnabled = !torchEnabled
                                cameraController.setTorch(torchEnabled)
                            }) {
                                Icon(Icons.Outlined.FlashOn, contentDescription = "Torch")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = settings.saveOriginal,
                                onClick = {
                                    viewModel.setSaveOriginal(!settings.saveOriginal)
                                    showSaveNote = true
                                },
                                label = { Text("Save original") },
                            )
                            FilterChip(
                                selected = false,
                                onClick = { showAdjustments = true },
                                label = { Text("Manual panel") },
                            )
                        }
                    }
                }

                Text(
                    text = "Simulated IR only unless external IR/thermal hardware is connected.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        if (showPresets) {
            ModalBottomSheet(
                onDismissRequest = { showPresets = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            ) {
                PresetSheet(
                    current = settings.preset,
                    onPick = {
                        viewModel.setPreset(it)
                        showPresets = false
                    },
                )
            }
        }

        if (showAdjustments) {
            ModalBottomSheet(
                onDismissRequest = { showAdjustments = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                AdjustmentsSheet(
                    settings = settings,
                    capabilities = capabilities,
                    onSettingsChange = { updated ->
                        viewModel.setSaveOriginal(updated.saveOriginal)
                        viewModel.setFrontFacing(updated.frontFacing)
                    },
                    onAdjustmentsChange = { updated -> viewModel.updateAdjustments { updated } },
                    onDismiss = { showAdjustments = false },
                )
            }
        }

        if (showSaveNote) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1200)
                showSaveNote = false
            }
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 104.dp),
            ) {
                Text(
                    text = if (settings.saveOriginal) "Originals will be saved alongside processed photos." else "Only processed captures will be saved.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.Black,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(
    current: SpectralPreset,
    onPick: (SpectralPreset) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Filter preset drawer", style = MaterialTheme.typography.headlineSmall)
        Text("These are simulated spectral looks inspired by infrared film behavior, not claims of true IR capture.", style = MaterialTheme.typography.bodySmall)
        SpectralPreset.values().forEach { preset ->
            FilterChip(
                selected = preset == current,
                onClick = { onPick(preset) },
                label = {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(preset.label, fontWeight = FontWeight.SemiBold)
                        Text(preset.description, style = MaterialTheme.typography.bodySmall)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AdjustmentsSheet(
    settings: CameraSettings,
    capabilities: CameraCapabilities?,
    onSettingsChange: (CameraSettings) -> Unit,
    onAdjustmentsChange: (ManualAdjustments) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = settings.adjustments
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Manual adjustment panel", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Tune the simulated IR character, not the claim. The app labels the output as simulated unless external hardware is detected.",
            style = MaterialTheme.typography.bodySmall,
        )

        FilledTonalButton(onClick = { onAdjustmentsChange(ManualAdjustments()) }) {
            Text("Reset to film defaults")
        }
        SteppedControl("Contrast", listOf("Low" to 0.7f, "Normal" to 1.0f, "Medium" to 1.25f, "High" to 1.6f, "Max" to 2.0f), current.contrast) { value -> onAdjustmentsChange(current.copy(contrast = value)) }
        StopsRow("Digital exposure (stops)", listOf(-2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f).map { formatStops(it) to it }, current.exposureCompensation) { value -> onAdjustmentsChange(current.copy(exposureCompensation = value)) }
        SteppedControl("Blacks", listOf("-1" to -1f, "-0.5" to -0.5f, "0" to 0f, "+0.5" to 0.5f, "+1" to 1f), current.blacks) { value -> onAdjustmentsChange(current.copy(blacks = value)) }
        SteppedControl("Whites", listOf("-1" to -1f, "-0.5" to -0.5f, "0" to 0f, "+0.5" to 0.5f, "+1" to 1f), current.whites) { value -> onAdjustmentsChange(current.copy(whites = value)) }
        SteppedControl("Bloom", listOf("Off" to 0f, "Low" to 0.3f, "Medium" to 0.6f, "High" to 1.0f), current.bloom) { value -> onAdjustmentsChange(current.copy(bloom = value)) }
        SteppedControl("Film grain", listOf("Off" to 0f, "Fine" to 0.25f, "Medium" to 0.5f, "Coarse" to 0.85f), current.grain) { value -> onAdjustmentsChange(current.copy(grain = value)) }
        SteppedControl("Sharpness", listOf("Off" to 0f, "Low" to 0.4f, "Medium" to 0.8f, "High" to 1.2f), current.sharpness) { value -> onAdjustmentsChange(current.copy(sharpness = value)) }
        SteppedControl("Red channel weight", listOf("Low" to 0.7f, "Normal" to 1.0f, "High" to 1.5f, "Max" to 2.0f), current.redChannelWeight) { value -> onAdjustmentsChange(current.copy(redChannelWeight = value)) }
        SteppedControl("Green foliage lift", listOf("Off" to 0f, "Low" to 0.33f, "Medium" to 0.66f, "High" to 1.0f), current.greenFoliageLift) { value -> onAdjustmentsChange(current.copy(greenFoliageLift = value)) }
        SteppedControl("Blue sky suppression", listOf("Off" to 0f, "Low" to 0.33f, "Medium" to 0.66f, "High" to 1.0f), current.blueSkySuppression) { value -> onAdjustmentsChange(current.copy(blueSkySuppression = value)) }
        SteppedControl("Hue rotation", listOf("-90\u00b0" to -90f, "-45\u00b0" to -45f, "-15\u00b0" to -15f, "0\u00b0" to 0f, "+15\u00b0" to 15f, "+45\u00b0" to 45f, "+90\u00b0" to 90f), current.hueRotation) { value -> onAdjustmentsChange(current.copy(hueRotation = value)) }
        SteppedControl("Saturation", listOf("B&W" to 0f, "Muted" to 0.6f, "Normal" to 1.0f, "Rich" to 1.4f, "Max" to 2.0f), current.saturation) { value -> onAdjustmentsChange(current.copy(saturation = value)) }

        Text("RGB channel swap", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ChannelSwapMode.values().forEach { mode ->
                FilterChip(
                    selected = current.channelSwapMode == mode,
                    onClick = { onAdjustmentsChange(current.copy(channelSwapMode = mode)) },
                    label = { Text(mode.label) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = settings.saveOriginal,
                onClick = { onSettingsChange(settings.copy(saveOriginal = !settings.saveOriginal)) },
                label = { Text("Save original") },
            )
            FilterChip(
                selected = settings.frontFacing,
                onClick = { onSettingsChange(settings.copy(frontFacing = !settings.frontFacing)) },
                label = { Text("Front camera") },
            )
        }

        if (capabilities?.exposureSupported == true) {
            Text("Camera exposure range: ${capabilities.exposureRange.first} to ${capabilities.exposureRange.last}", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SteppedControl(
    label: String,
    options: List<Pair<String, Float>>,
    value: Float,
    onSelect: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val nearest = options.minByOrNull { abs(it.second - value) }?.second
            options.forEach { option ->
                FilterChip(
                    selected = option.second == nearest,
                    onClick = { onSelect(option.second) },
                    label = { Text(option.first) },
                )
            }
        }
    }
}

@Composable
private fun ShutterControl(
    options: List<Pair<String, Long>>,
    valueNs: Long,
    onSelect: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Shutter (AE off)", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val nearest = options.minByOrNull { abs(it.second - valueNs) }?.second
            options.forEach { option ->
                FilterChip(
                    selected = option.second == nearest,
                    onClick = { onSelect(option.second) },
                    label = { Text(option.first) },
                )
            }
        }
    }
}

@Composable
private fun StopsRow(
    label: String,
    options: List<Pair<String, Float>>,
    value: Float,
    onSelect: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val nearest = options.minByOrNull { abs(it.second - value) }?.second
            options.forEach { option ->
                val selected = option.second == nearest
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(option.second) },
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        option.first,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun formatStops(stops: Float): String {
    if (stops == 0f) return "0"
    val sign = if (stops > 0f) "+" else ""
    return if (stops == stops.toInt().toFloat()) {
        sign + stops.toInt()
    } else {
        sign + String.format("%.1f", stops)
    }
}
