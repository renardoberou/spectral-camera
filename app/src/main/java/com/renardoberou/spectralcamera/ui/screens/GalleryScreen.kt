package com.renardoberou.spectralcamera.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renardoberou.spectralcamera.core.GalleryItem
import com.renardoberou.spectralcamera.core.state.SpectralViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(viewModel: SpectralViewModel) {
    val context = LocalContext.current
    val items by viewModel.galleryItems.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<GalleryItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Gallery / Export") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Text(
            text = "Captures are saved to your phone\u2019s gallery (DCIM/SpectralCamera) and show up in Google Photos. Everything stays on this device \u2014 nothing is ever uploaded. Labels always show whether an image is simulated or from external hardware.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.uri.toString() }) { item ->
                GalleryCard(item = item, onClick = { selected = item })
            }
        }
    }

    selected?.let { item ->
        GalleryDetailDialog(
            item = item,
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun GalleryCard(item: GalleryItem, onClick: () -> Unit) {
    val thumbnail by rememberGalleryThumbnail(item.uri)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = item.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Loading")
                        }
                    }
                }
            }
            Column(Modifier.padding(12.dp)) {
                Text(item.presetLabel, style = MaterialTheme.typography.labelLarge)
                Text(item.sensorModeLabel, style = MaterialTheme.typography.bodySmall)
                Text(if (item.isOriginal) "Original" else "Processed", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GalleryDetailDialog(item: GalleryItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap by rememberGalleryThumbnail(item.uri)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                Text(item.presetLabel, style = MaterialTheme.typography.bodySmall)
                Text(item.sensorModeLabel, style = MaterialTheme.typography.bodySmall)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = item.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                Text("URI: ${item.uri}", style = MaterialTheme.typography.bodySmall)
                RowActions(uri = item.uri, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun RowActions(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share spectral image").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Text("Share")
        }
        Button(onClick = onDismiss) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = null)
            Text("Close")
        }
    }
}

@Composable
private fun rememberGalleryThumbnail(uri: Uri): androidx.compose.runtime.State<android.graphics.Bitmap?> {
    val context = LocalContext.current
    return androidx.compose.runtime.produceState<android.graphics.Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) { loadSampledBitmap(context, uri, 960, 960) }
    }
}

private fun loadSampledBitmap(context: android.content.Context, uri: Uri, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    val resolver = context.contentResolver
    resolver.openInputStream(uri)?.use { stream ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, options)
        val sample = calculateInSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { stream2 ->
            return BitmapFactory.decodeStream(stream2, null, decodeOptions)
        }
    }
    return null
}

private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    var sample = 1
    var halfHeight = height / 2
    var halfWidth = width / 2
    while (halfHeight / sample >= reqHeight && halfWidth / sample >= reqWidth) {
        sample *= 2
    }
    return sample.coerceAtLeast(1)
}
