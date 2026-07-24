package com.renardoberou.spectralcamera.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renardoberou.spectralcamera.core.CameraCapabilities
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.CaptureActionResult
import com.renardoberou.spectralcamera.core.CaptureResult
import com.renardoberou.spectralcamera.core.ChannelSwapMode
import com.renardoberou.spectralcamera.core.DoubleExposureMode
import com.renardoberou.spectralcamera.core.FocusMode
import com.renardoberou.spectralcamera.core.FocusTapResult
import com.renardoberou.spectralcamera.core.LookFamily
import com.renardoberou.spectralcamera.core.ManualAdjustments
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.WhiteBalancePreset
import com.renardoberou.spectralcamera.core.camera.CameraController
import com.renardoberou.spectralcamera.core.focus.FocusMath
import com.renardoberou.spectralcamera.core.state.SpectralViewModel
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun LiveCameraScreen(
    viewModel: SpectralViewModel,
    cameraController: CameraController,
    settings: CameraSettings,
    capabilities: CameraCapabilities?,
    galleryCount: Int,
    onCapture: suspend () -> CaptureActionResult,
    onImport: suspend (android.net.Uri) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenHardware: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val doubleExposureState by viewModel.doubleExposureState.collectAsStateWithLifecycle()
    var showPresets by remember { mutableStateOf(false) }
    var showExposure by remember { mutableStateOf(false) }
    var showFocus by remember { mutableStateOf(false) }
    var showWhiteBalance by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var showSaveNote by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var captureLabel by remember { mutableStateOf("Ready for capture") }
    var focusMessage by remember { mutableStateOf("Continuous autofocus active") }
    var capturing by remember { mutableStateOf(false) }
    val manualFocusLabel = FocusMath.positionLabel(
        settings.manualFocusPosition,
        capabilities?.minimumFocusDistanceDiopters ?: 0f,
        capabilities?.focusDistanceCalibration
            ?: com.renardoberou.spectralcamera.core.FocusDistanceCalibration.UNCALIBRATED,
    )

    LaunchedEffect(settings.focusMode, settings.manualFocusPosition, capabilities) {
        focusMessage = when (settings.focusMode) {
            FocusMode.CONTINUOUS -> "Continuous autofocus active"
            FocusMode.TAP_LOCK -> "Tap a subject to focus and lock"
            FocusMode.MACRO -> "Tap a close subject to focus and lock"
            FocusMode.MANUAL -> "Manual focus: $manualFocusLabel"
            FocusMode.INFINITY -> "Focus held at infinity"
            FocusMode.FIXED -> "This lens reports fixed focus"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                captureLabel = "Opening import preview…"
                runCatching { onImport(uri) }
                    .onSuccess { captureLabel = "Ready for capture" }
                    .onFailure { captureLabel = "Import failed: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(settings.focusMode, settings.manualMode) {
                detectTapGestures { offset ->
                    focusMessage = when (settings.focusMode) {
                        FocusMode.CONTINUOUS -> "Focusing…"
                        FocusMode.TAP_LOCK -> "Locking focus…"
                        FocusMode.MACRO -> "Macro focusing…"
                        FocusMode.FIXED -> "This lens has fixed focus"
                        FocusMode.MANUAL,
                        FocusMode.INFINITY,
                        -> if (settings.manualMode) {
                            "Manual exposure and focus active"
                        } else {
                            "Metering exposure…"
                        }
                    }
                    cameraController.focusAt(
                        x = offset.x,
                        y = offset.y,
                        viewWidth = size.width.toFloat(),
                        viewHeight = size.height.toFloat(),
                        manualExposure = settings.manualMode,
                    ) { result ->
                        focusMessage = when (result) {
                            FocusTapResult.FOCUSED -> "Focused • continuous AF resumes automatically"
                            FocusTapResult.LOCKED -> "Focus locked • tap another subject or unlock"
                            FocusTapResult.FAILED -> "Focus failed • try a higher-contrast edge"
                            FocusTapResult.METERED -> "Exposure metered • focus position unchanged"
                            FocusTapResult.IGNORED -> "Manual exposure and focus active"
                            FocusTapResult.UNSUPPORTED -> "Focus action unavailable on this lens"
                        }
                    }
                }
            },
    ) {
        doubleExposureState.overlayBitmap?.let { overlay ->
            Image(
                bitmap = overlay.asImageBitmap(),
                contentDescription = "First double-exposure frame guide",
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.34f),
                contentScale = ContentScale.Crop,
            )
        }

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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        val wideHeader = maxWidth >= 520.dp
                        if (wideHeader) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(0.9f),
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    Text(
                                        text = "Spectral Camera",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "${settings.sensorMode.label} • simulated IR",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1.35f),
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    Text(
                                        text = "${settings.preset.label} • ${settings.focusMode.label}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        textAlign = TextAlign.End,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${settings.requestedCaptureLabel} • ${settings.outputMode.label}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.End,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (doubleExposureState.waitingForSecond) {
                                        Text(
                                            text = "Double Exposure • frame 1 stored",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            textAlign = TextAlign.End,
                                        )
                                    }
                                    if (settings.manualMode) {
                                        Text(
                                            text = "Manual exposure • ISO ${settings.manualIso}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            textAlign = TextAlign.End,
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Spectral Camera",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = settings.focusMode.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    text = "${settings.sensorMode.label} • ${settings.preset.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${settings.requestedCaptureLabel} • ${settings.outputMode.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (doubleExposureState.waitingForSecond) {
                                    Text(
                                        text = "Double Exposure • frame 1 stored",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                if (settings.manualMode) {
                                    Text(
                                        text = "Manual exposure • ISO ${settings.manualIso}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (capabilities?.exposureSupported == true) {
                        FilterChip(
                            selected = showExposure,
                            onClick = {
                                showExposure = !showExposure
                                if (showExposure) {
                                    showFocus = false
                                    showWhiteBalance = false
                                }
                            },
                            label = { Text(if (showExposure) "Exposure ▴" else "Exposure ▾") },
                        )
                    }
                    if (capabilities != null) {
                        FilterChip(
                            selected = showFocus,
                            onClick = {
                                showFocus = !showFocus
                                if (showFocus) {
                                    showExposure = false
                                    showWhiteBalance = false
                                }
                            },
                            label = { Text(if (showFocus) "Focus ▴" else "Focus ▾") },
                        )
                    }
                    if (capabilities != null) {
                        FilterChip(
                            selected = showWhiteBalance,
                            onClick = {
                                showWhiteBalance = !showWhiteBalance
                                if (showWhiteBalance) {
                                    showExposure = false
                                    showFocus = false
                                }
                            },
                            label = { Text("WB ${settings.whiteBalancePreset.label}") },
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = { showPresets = true },
                        label = { Text("Presets") },
                    )
                }
                if (capabilities?.exposureSupported == true && showExposure) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SteppedControl(
                                label = "Look intensity",
                                options = listOf("25%" to 0.25f, "50%" to 0.5f, "75%" to 0.75f, "100%" to 1.0f),
                                value = settings.intensity,
                            ) { value -> viewModel.setIntensity(value) }
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
                                        range == null || iso in range
                                    }
                                    .map { "ISO $it" to it.toFloat() }
                                SteppedControl(
                                    label = "ISO (AE off)",
                                    options = isoOptions,
                                    value = settings.manualIso.toFloat(),
                                ) { iso -> viewModel.setManualIso(iso.toInt()) }
                                val shutterOptions = listOf(
                                    "1/4000" to 250_000L,
                                    "1/2000" to 500_000L,
                                    "1/1000" to 1_000_000L,
                                    "1/500" to 2_000_000L,
                                    "1/250" to 4_000_000L,
                                    "1/125" to 8_000_000L,
                                    "1/60" to 16_666_667L,
                                    "1/30" to 33_333_333L,
                                    "1/15" to 66_666_667L,
                                    "1/8" to 125_000_000L,
                                ).filter { pair ->
                                    val range = capabilities.exposureTimeRange
                                    range == null || pair.second in range
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
                                        append("AE off · manual sensor")
                                    } else {
                                        append("ISO auto")
                                    }
                                    capabilities.aperture?.let {
                                        append("  ·  f/" + String.format("%.1f", it))
                                    }
                                    append("  ·  fixed aperture")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }

                if (capabilities != null && showFocus) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(
                            Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = focusMessage,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FocusMode.values().forEach { mode ->
                                    FilterChip(
                                        selected = settings.focusMode == mode,
                                        enabled = capabilities.supportsFocusMode(mode),
                                        onClick = {
                                            viewModel.setFocusMode(mode)
                                            focusMessage = mode.description
                                        },
                                        label = { Text(mode.label) },
                                    )
                                }
                            }
                            Text(
                                text = if (capabilities.canFocus) {
                                    settings.focusMode.description
                                } else {
                                    "This lens reports fixed focus; focus controls have no physical effect."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )

                            if (settings.focusMode == FocusMode.MANUAL && capabilities.manualFocusSupported) {
                                Text(
                                    text = "Lens position: $manualFocusLabel",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = settings.manualFocusPosition,
                                    onValueChange = viewModel::setManualFocusPosition,
                                    valueRange = 0f..1f,
                                    steps = 19,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("∞ / far", style = MaterialTheme.typography.labelSmall)
                                    Text("nearest", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            if (
                                settings.focusMode == FocusMode.TAP_LOCK ||
                                settings.focusMode == FocusMode.MACRO
                            ) {
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        cameraController.unlockFocus()
                                        focusMessage = "Focus unlocked • tap a subject to lock again"
                                    },
                                    label = { Text("Unlock focus") },
                                )
                            }
                        }
                    }
                }

                if (capabilities != null && showWhiteBalance) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(
                            Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "White balance • ${settings.whiteBalancePreset.temperatureLabel}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            WhiteBalanceControl(
                                current = settings.whiteBalancePreset,
                                capabilities = capabilities,
                                onSelect = viewModel::setWhiteBalancePreset,
                            )
                            Text(
                                text = when {
                                    settings.whiteBalancePreset == WhiteBalancePreset.AUTO ->
                                        settings.whiteBalancePreset.description
                                    capabilities.usesDirectKelvinWhiteBalance(settings.whiteBalancePreset) ->
                                        "Direct ${settings.whiteBalancePreset.temperatureLabel} CCT request on this lens."
                                    else ->
                                        "This lens uses its closest advertised fixed white-balance mode."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            captureLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { viewModel.setFrontFacing(!settings.frontFacing) }) {
                                Icon(Icons.Outlined.Cameraswitch, contentDescription = "Switch camera")
                            }

                            FilledTonalButton(
                                enabled = !capturing,
                                onClick = {
                                    scope.launch {
                                        capturing = true
                                        captureLabel = when {
                                            settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED &&
                                                doubleExposureState.waitingForSecond -> "Capturing double-exposure frame 2…"
                                            settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED ->
                                                "Capturing double-exposure frame 1…"
                                            else -> "Capturing ${settings.requestedCaptureLabel}…"
                                        }
                                        runCatching { onCapture() }
                                            .onSuccess { action ->
                                                when (action) {
                                                    is CaptureActionResult.AwaitingSecondExposure ->
                                                        captureLabel = action.message
                                                    is CaptureActionResult.Saved -> {
                                                        captureLabel = "Saved ${action.result.summary}"
                                                        viewModel.refreshGallery()
                                                    }
                                                }
                                            }
                                            .onFailure {
                                                captureLabel = "Capture failed: ${it.message ?: it.javaClass.simpleName}"
                                            }
                                        capturing = false
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 26.dp, vertical = 18.dp),
                            ) {
                                Text(
                                    when {
                                        settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED &&
                                            doubleExposureState.waitingForSecond -> "Capture 2 + Save"
                                        settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED -> "Capture 1"
                                        else -> "Capture"
                                    },
                                )
                            }

                            IconButton(onClick = {
                                torchEnabled = !torchEnabled
                                cameraController.setTorch(torchEnabled)
                            }) {
                                Icon(Icons.Outlined.FlashOn, contentDescription = "Torch")
                            }
                        }

                        if (doubleExposureState.waitingForSecond) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    viewModel.cancelDoubleExposure()
                                    captureLabel = "Double exposure cancelled"
                                },
                                label = { Text("Cancel stored frame 1") },
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = settings.saveOriginal,
                                enabled = settings.hdrCaptureMode != com.renardoberou.spectralcamera.core.HdrCaptureMode.RAW_THREE_FRAME,
                                onClick = {
                                    viewModel.setSaveOriginal(!settings.saveOriginal)
                                    showSaveNote = true
                                },
                                label = {
                                    Text(
                                        if (settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED) {
                                            "Save sources"
                                        } else {
                                            "Save original"
                                        },
                                    )
                                },
                            )
                            FilterChip(
                                selected = false,
                                onClick = { showAdjustments = true },
                                label = { Text("Manual panel") },
                            )
                            FilterChip(
                                selected = false,
                                onClick = {
                                    importLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                label = { Text("Import photo") },
                            )
                            FilterChip(
                                selected = settings.zebraEnabled,
                                onClick = { viewModel.setZebra(!settings.zebraEnabled) },
                                label = { Text("Zebra") },
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
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 104.dp),
            ) {
                Text(
                    text = when {
                        !settings.saveOriginal -> "Only processed captures will be saved."
                        settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED ->
                            "Both double-exposure source frames will be saved."
                        else -> "Originals will be saved alongside processed photos."
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.Black,
                )
            }
        }
    }
}

@Composable
internal fun PresetSheet(
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
        Text(
            "These are simulated spectral looks inspired by infrared film behavior, not claims of true IR capture.",
            style = MaterialTheme.typography.bodySmall,
        )
        val grouped = SpectralPreset.values().groupBy { it.family }
        listOf(LookFamily.MONOCHROME_IR, LookFamily.AEROCHROME, LookFamily.STANDARD_FILM).forEach { family ->
            val presets = grouped[family].orEmpty()
            if (presets.isEmpty()) return@forEach
            Text(
                text = family.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            presets.forEach { preset ->
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
        SteppedControl("Hue rotation", listOf("-90°" to -90f, "-45°" to -45f, "-15°" to -15f, "0°" to 0f, "+15°" to 15f, "+45°" to 45f, "+90°" to 90f), current.hueRotation) { value -> onAdjustmentsChange(current.copy(hueRotation = value)) }
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
            Text(
                "Camera exposure range: ${capabilities.exposureRange.first} to ${capabilities.exposureRange.last}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun SteppedControl(
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
private fun WhiteBalanceControl(
    current: WhiteBalancePreset,
    capabilities: CameraCapabilities,
    onSelect: (WhiteBalancePreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WhiteBalancePreset.values().toList().chunked(3).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowPresets.forEach { preset ->
                    val enabled = capabilities.supportsWhiteBalancePreset(preset)
                    val selected = current == preset
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .alpha(if (enabled) 1f else 0.42f)
                            .then(if (enabled) Modifier.clickable { onSelect(preset) } else Modifier),
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                preset.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                preset.temperatureLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
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
            horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                            .padding(vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
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
