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
import androidx.compose.ui.unit.dp
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
                        Column(Modifier.padding(14.dp)) {
                            Text("Exposure compensation", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = settings.hardwareEv,
                                onValueChange = { value ->
                                    viewModel.setHardwareEv(value)
                                    cameraController.setExposureCompensation(value.roundToInt())
                                },
                                valueRange = capabilities.exposureRange.first.toFloat()..capabilities.exposureRange.last.toFloat(),
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

        AdjustmentSlider("Contrast", current.contrast, 0.5f..2.0f) { value -> onAdjustmentsChange(current.copy(contrast = value)) }
        AdjustmentSlider("Digital exposure", current.exposureCompensation, -2f..2f) { value -> onAdjustmentsChange(current.copy(exposureCompensation = value)) }
        AdjustmentSlider("Blacks", current.blacks, -1f..1f) { value -> onAdjustmentsChange(current.copy(blacks = value)) }
        AdjustmentSlider("Whites", current.whites, -1f..1f) { value -> onAdjustmentsChange(current.copy(whites = value)) }
        AdjustmentSlider("Bloom", current.bloom, 0f..1.2f) { value -> onAdjustmentsChange(current.copy(bloom = value)) }
        AdjustmentSlider("Grain", current.grain, 0f..1f) { value -> onAdjustmentsChange(current.copy(grain = value)) }
        AdjustmentSlider("Sharpness", current.sharpness, 0f..1.5f) { value -> onAdjustmentsChange(current.copy(sharpness = value)) }
        AdjustmentSlider("Red channel weight", current.redChannelWeight, 0.5f..2.2f) { value -> onAdjustmentsChange(current.copy(redChannelWeight = value)) }
        AdjustmentSlider("Green foliage lift", current.greenFoliageLift, 0f..1f) { value -> onAdjustmentsChange(current.copy(greenFoliageLift = value)) }
        AdjustmentSlider("Blue sky suppression", current.blueSkySuppression, 0f..1f) { value -> onAdjustmentsChange(current.copy(blueSkySuppression = value)) }
        AdjustmentSlider("Hue rotation", current.hueRotation, -180f..180f) { value -> onAdjustmentsChange(current.copy(hueRotation = value)) }
        AdjustmentSlider("Saturation", current.saturation, 0f..2f) { value -> onAdjustmentsChange(current.copy(saturation = value)) }

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
private fun AdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(String.format("%.2f", value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
