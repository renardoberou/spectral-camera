package com.renardoberou.spectralcamera.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.state.SpectralViewModel
import kotlinx.coroutines.launch

/**
 * Pick once, then dial in a look for THIS specific photo before it is saved.
 * Save commits with whatever is chosen here - not whatever the live camera
 * happens to be set to. Used from both "Import photo" (Live) and
 * "Process with current preset" (Gallery), so an existing capture can be
 * reprocessed with a different look the same way.
 */
@Composable
fun ImportPreviewScreen(
    viewModel: SpectralViewModel,
    process: suspend (Bitmap, CameraSettings) -> Bitmap,
    onDone: () -> Unit,
    onCancelled: () -> Unit,
) {
    val state by viewModel.importPreview.collectAsStateWithLifecycle()
    val loading by viewModel.importLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.importError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showPresets by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // While the photo is still decoding, state is legitimately null - this is
    // NOT "cancelled". Cloud-backed sources (shared albums, "Collections",
    // anything not already local to MediaStore) resolve through a slower
    // path than local photos and must be given time to arrive; popping the
    // screen the instant state == null used to close it before those photos
    // ever finished loading. Only an explicit Cancel/Save leaves the screen.
    if (state == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loadError != null) {
                Text(loadError ?: "", color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    viewModel.cancelImportPreview()
                    onCancelled()
                }) { Text("Back") }
            } else {
                CircularProgressIndicator()
                Text("Opening photo\u2026", modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = {
                    viewModel.cancelImportPreview()
                    onCancelled()
                }) { Text("Cancel") }
            }
        }
        return
    }

    val current = state ?: return

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val preview = current.preview
            if (preview != null && !preview.isRecycled) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Import preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            if (current.isRendering) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = showPresets,
                    onClick = { showPresets = !showPresets },
                    label = { Text("Preset: ${current.settings.preset.label}") },
                )
            }

            if (showPresets) {
                Box(modifier = Modifier.height(320.dp)) {
                    PresetSheet(
                        current = current.settings.preset,
                        onPick = { preset ->
                            showPresets = false
                            viewModel.updatePreviewSettings({ it.copy(preset = preset) }, process)
                        },
                    )
                }
            }

            SteppedControl(
                label = "Look intensity",
                options = listOf("25%" to 0.25f, "50%" to 0.5f, "75%" to 0.75f, "100%" to 1.0f),
                value = current.settings.intensity,
            ) { v -> viewModel.updatePreviewSettings({ it.copy(intensity = v) }, process) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.cancelImportPreview()
                        onCancelled()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        scope.launch {
                            errorText = null
                            runCatching { viewModel.confirmImportPreview(process) }
                                .onSuccess { onDone() }
                                .onFailure { errorText = "Save failed: ${it.message ?: it.javaClass.simpleName}" }
                        }
                    },
                    enabled = !current.isSaving && !current.isRendering,
                    modifier = Modifier.weight(1f),
                ) {
                    if (current.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text("Save")
                    }
                }
            }
        }
    }
}
