package com.renardoberou.spectralcamera.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.core.database.getLongOrNull
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.CaptureResult
import com.renardoberou.spectralcamera.core.GalleryItem
import com.renardoberou.spectralcamera.core.SensorMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MediaRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val picturesPath = "${Environment.DIRECTORY_PICTURES}/SpectralCamera"
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    suspend fun saveCapture(
        processed: Bitmap,
        original: Bitmap?,
        settings: CameraSettings,
    ): CaptureResult {
        val timestamp = Instant.now()
        val processedName = fileName(settings, raw = false, timestamp)
        val processedUri = insertBitmap(processed, processedName, settings, raw = false)
        val originalUri = if (settings.saveOriginal && original != null) {
            val originalName = fileName(settings, raw = true, timestamp)
            insertBitmap(original, originalName, settings, raw = true)
        } else {
            null
        }
        return CaptureResult(
            processedUri = processedUri,
            originalUri = originalUri,
            displayName = processedName,
        )
    }

    fun loadGallery(limit: Int = 120): List<GalleryItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%SpectralCamera%")
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        val items = mutableListOf<GalleryItem>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext() && items.size < limit) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val dateTaken = if (cursor.isNull(dateCol)) 0L else cursor.getLong(dateCol)
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(id.toString())
                    .build()
                val meta = parseName(name)
                items += GalleryItem(
                    uri = uri,
                    displayName = name,
                    dateTakenMillis = dateTaken,
                    presetLabel = meta?.preset?.label ?: "Unknown preset",
                    sensorModeLabel = meta?.sensorMode?.label ?: "Simulated IR",
                    isOriginal = meta?.isOriginal ?: false,
                )
            }
        }
        return items
    }

    private fun insertBitmap(
        bitmap: Bitmap,
        displayName: String,
        settings: CameraSettings,
        raw: Boolean,
    ): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, picturesPath)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DESCRIPTION, "${settings.sensorMode.label} • ${settings.preset.label} • ${if (raw) "Original" else "Processed"}")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore item")
        resolver.openOutputStream(uri)?.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)) {
                throw IOException("Failed to compress bitmap")
            }
        } ?: throw IOException("Failed to open output stream")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun fileName(settings: CameraSettings, raw: Boolean, instant: Instant): String {
        val stamp = timestampFormatter.format(instant)
        val mode = if (raw) "raw" else "proc"
        return "spectral_${mode}_${settings.sensorMode.name}_${settings.preset.name}_$stamp.jpg"
    }

    private data class ParsedName(
        val preset: SpectralPreset,
        val isOriginal: Boolean,
        val sensorMode: SensorMode,
    )

    private fun parseName(displayName: String): ParsedName? {
        val regex = Regex("^spectral_(raw|proc)_([A-Z0-9_]+)_([A-Z0-9_]+)_([0-9]{8}_[0-9]{6})\\.jpg$", RegexOption.IGNORE_CASE)
        val match = regex.find(displayName) ?: return null
        val mode = match.groupValues[1]
        val sensorModeName = match.groupValues[2].uppercase(Locale.US)
        val presetName = match.groupValues[3].uppercase(Locale.US)
        val preset = runCatching { SpectralPreset.valueOf(presetName) }.getOrNull() ?: return null
        val sensorMode = runCatching { SensorMode.valueOf(sensorModeName) }.getOrNull() ?: SensorMode.SIMULATED_IR
        return ParsedName(
            preset = preset,
            isOriginal = mode.equals("raw", ignoreCase = true),
            sensorMode = sensorMode,
        )
    }
}
