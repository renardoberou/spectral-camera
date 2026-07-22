package com.renardoberou.spectralcamera.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renardoberou.spectralcamera.core.GalleryItem
import com.renardoberou.spectralcamera.core.media.GalleryAccessLevel
import com.renardoberou.spectralcamera.core.media.GalleryPermissionPolicy
import com.renardoberou.spectralcamera.core.state.SpectralViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: SpectralViewModel,
    onReprocess: suspend (Uri) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val galleryState by viewModel.galleryState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<GalleryItem?>(null) }
    var accessLevel by remember { mutableStateOf(resolveGalleryAccess(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        accessLevel = resolveGalleryAccess(context)
        viewModel.refreshGallery()
    }

    LaunchedEffect(Unit) {
        accessLevel = resolveGalleryAccess(context)
        viewModel.refreshGallery()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessLevel = resolveGalleryAccess(context)
                viewModel.refreshGallery()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Gallery / Export") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Text(
            text = "Each image shows whether it was Standard, Computational HDR, True RAW HDR, or Double Exposure. Ultra HDR files remain SDR-compatible JPEGs in non-HDR viewers; the detail viewer enables HDR only when the decoded bitmap actually carries a gain map.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (accessLevel != GalleryAccessLevel.FULL) {
            GalleryAccessNotice(
                accessLevel = accessLevel,
                onRequestAccess = { permissionLauncher.launch(requiredGalleryPermissions()) },
            )
        }

        galleryState.errorMessage?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    Button(onClick = viewModel::refreshGallery) { Text("Try again") }
                }
            }
        }

        when {
            galleryState.isLoading && galleryState.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            galleryState.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (accessLevel) {
                            GalleryAccessLevel.PARTIAL -> "No Spectral Camera captures are included in the current photo selection."
                            GalleryAccessLevel.DENIED -> "Photo access is unavailable. Grant access to display saved captures."
                            else -> "No Spectral Camera captures are visible yet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(galleryState.items, key = { it.uri.toString() }) { item ->
                        GalleryCard(item = item, onClick = { selected = item })
                    }
                }
            }
        }
    }

    selected?.let { item ->
        GalleryDetailDialog(
            onReprocess = onReprocess,
            item = item,
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun GalleryAccessNotice(
    accessLevel: GalleryAccessLevel,
    onRequestAccess: () -> Unit,
) {
    val message = when (accessLevel) {
        GalleryAccessLevel.PARTIAL ->
            "Only selected photos are visible. Change the selection to include other Spectral Camera captures."
        GalleryAccessLevel.APP_OWNED_ONLY ->
            "Captures from this installation are visible. Allow photo access to recover images made by previous installations."
        GalleryAccessLevel.DENIED ->
            "Allow photo access to display Spectral Camera captures saved on this device."
        GalleryAccessLevel.FULL -> return
    }
    val action = if (accessLevel == GalleryAccessLevel.PARTIAL) "Manage photo access" else "Show previous captures"

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Button(onClick = onRequestAccess) { Text(action) }
        }
    }
}

@Composable
private fun GalleryCard(item: GalleryItem, onClick: () -> Unit) {
    val thumbnail by rememberGalleryThumbnail(item.uri)
    val bitmap = thumbnail.bitmap
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = item.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (thumbnail.complete) "Preview unavailable" else "Loading")
                        }
                    }
                }
            }
            Column(Modifier.padding(12.dp)) {
                Text(item.presetLabel, style = MaterialTheme.typography.labelLarge)
                Text(
                    item.captureModeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(item.sensorModeLabel, style = MaterialTheme.typography.bodySmall)
                Text(
                    when {
                        item.isOriginal -> "Reference / Original"
                        item.isUltraHdr -> "Processed • Ultra HDR"
                        else -> "Processed • SDR"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GalleryDetailDialog(
    item: GalleryItem,
    onDismiss: () -> Unit,
    onReprocess: suspend (Uri) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val thumbnail by rememberGalleryThumbnail(item.uri)
    val bitmap = thumbnail.bitmap
    UltraHdrWindowEffect(bitmap)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                Text(item.presetLabel, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Capture: ${item.captureModeLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(item.sensorModeLabel, style = MaterialTheme.typography.bodySmall)
                if (item.isUltraHdr) {
                    Text("Ultra HDR gain-map JPEG", style = MaterialTheme.typography.labelMedium)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = item.displayName,
                        modifier = Modifier.fillMaxWidth().height(360.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else if (thumbnail.complete) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Preview unavailable")
                    }
                }
                Text("URI: ${item.uri}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    scope.launch {
                        onReprocess(item.uri)
                        onDismiss()
                    }
                }) {
                    Text("Process with current preset")
                }
                RowActions(uri = item.uri, onDismiss = onDismiss)
            }
        }
    }
}

/** Dynamically enables HDR only while an actual decoded gain-map image is visible. */
@Composable
private fun UltraHdrWindowEffect(bitmap: Bitmap?) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val containsGainmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        bitmap?.hasGainmap() == true

    DisposableEffect(activity, containsGainmap) {
        val window = activity?.window
        val previous = window?.colorMode ?: ActivityInfo.COLOR_MODE_DEFAULT
        if (window != null) {
            window.colorMode = if (containsGainmap) {
                ActivityInfo.COLOR_MODE_HDR
            } else {
                ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
        onDispose {
            if (window != null) window.colorMode = previous
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun RowActions(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "Share spectral image")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Text("Share")
        }
        Button(onClick = onDismiss) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            Text("Close")
        }
    }
}

private data class GalleryThumbnailState(
    val bitmap: Bitmap? = null,
    val complete: Boolean = false,
)

@Composable
private fun rememberGalleryThumbnail(uri: Uri): androidx.compose.runtime.State<GalleryThumbnailState> {
    val context = LocalContext.current
    return androidx.compose.runtime.produceState(initialValue = GalleryThumbnailState(), key1 = uri) {
        val bitmap = withContext(Dispatchers.IO) { loadSampledBitmap(context, uri, 960, 960) }
        value = GalleryThumbnailState(bitmap = bitmap, complete = true)
    }
}

private fun loadSampledBitmap(
    context: Context,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int,
): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.Options().also { options ->
            options.inJustDecodeBounds = true
            BitmapFactory.decodeStream(stream, null, options)
        }
    } ?: return@runCatching null

    val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
    resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, decodeOptions)
    }
}.getOrNull()

private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    var sample = 1
    val halfHeight = height / 2
    val halfWidth = width / 2
    while (halfHeight / sample >= reqHeight && halfWidth / sample >= reqWidth) {
        sample *= 2
    }
    return sample.coerceAtLeast(1)
}

private fun requiredGalleryPermissions(): Array<String> =
    GalleryPermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT)

private fun resolveGalleryAccess(context: Context): GalleryAccessLevel {
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    return GalleryPermissionPolicy.resolve(
        sdkInt = Build.VERSION.SDK_INT,
        hasReadMediaImages = granted(Manifest.permission.READ_MEDIA_IMAGES),
        hasSelectedPhotoAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            false
        },
        hasLegacyStorageAccess = granted(Manifest.permission.READ_EXTERNAL_STORAGE),
    )
}
