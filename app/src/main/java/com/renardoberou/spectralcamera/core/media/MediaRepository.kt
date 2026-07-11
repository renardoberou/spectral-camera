package com.renardoberou.spectralcamera.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.CaptureResult
import com.renardoberou.spectralcamera.core.GalleryItem
import com.renardoberou.spectralcamera.core.SensorMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MediaRepository(private val context: Context) {
    private val resolver = context.contentResolver

    // New captures use DCIM so they appear prominently in Google Photos and
    // other gallery apps. Earlier builds used Pictures/SpectralCamera, which is
    // still queried exactly so historical captures remain recoverable.
    private val currentPicturesPath = "${Environment.DIRECTORY_DCIM}/SpectralCamera"
    private val formerPicturesPath = "${Environment.DIRECTORY_PICTURES}/SpectralCamera"

    @Suppress("DEPRECATION")
    private val legacyCurrentDirectory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "SpectralCamera",
    )

    @Suppress("DEPRECATION")
    private val legacyFormerDirectory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "SpectralCamera",
    )

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

    @Suppress("DEPRECATION")
    fun loadGallery(limit: Int = 400): List<GalleryItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Accept exact current and historical paths, with or without the
            // trailing slash MediaStore normally stores.
            val pathColumn = MediaStore.Images.Media.RELATIVE_PATH
            "($pathColumn = ? OR $pathColumn = ? OR $pathColumn = ? OR $pathColumn = ?)" to
                arrayOf(
                    currentPicturesPath,
                    "$currentPicturesPath/",
                    formerPicturesPath,
                    "$formerPicturesPath/",
                )
        } else {
            val dataColumn = MediaStore.Images.Media.DATA
            "($dataColumn LIKE ? OR $dataColumn LIKE ?)" to
                arrayOf(
                    "${legacyCurrentDirectory.absolutePath}/%",
                    "${legacyFormerDirectory.absolutePath}/%",
                )
        }
        // NULL-safe ordering: older files without DATE_TAKEN fall back to
        // DATE_ADDED (seconds -> ms) instead of sorting last and evicting the
        // newest captures once the 120-item history filled the old cap.
        val sortOrder =
            "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, ${MediaStore.Images.Media.DATE_ADDED} * 1000) DESC"
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
                val meta = parseName(name) ?: continue
                val dateTaken = if (cursor.isNull(dateCol)) 0L else cursor.getLong(dateCol)
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(id.toString())
                    .build()
                items += GalleryItem(
                    uri = uri,
                    displayName = name,
                    dateTakenMillis = dateTaken,
                    presetLabel = meta.preset.label,
                    sensorModeLabel = meta.sensorMode.label,
                    isOriginal = meta.isOriginal,
                )
            }
        }
        return items
    }

    @Suppress("DEPRECATION")
    private fun insertBitmap(
        bitmap: Bitmap,
        displayName: String,
        settings: CameraSettings,
        raw: Boolean,
    ): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(
                MediaStore.Images.Media.DESCRIPTION,
                "${settings.sensorMode.label} • ${settings.preset.label} • ${if (raw) "Original" else "Processed"}",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, currentPicturesPath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                if (!legacyCurrentDirectory.exists() && !legacyCurrentDirectory.mkdirs()) {
                    throw IOException("Failed to create legacy SpectralCamera directory")
                }
                put(
                    MediaStore.Images.Media.DATA,
                    File(legacyCurrentDirectory, displayName).absolutePath,
                )
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore item")

        try {
            resolver.openOutputStream(uri, "w")?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)) {
                    throw IOException("Failed to compress bitmap")
                }
            } ?: throw IOException("Failed to open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
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
        val regex = Regex("^spectral_(raw|proc)_(SIMULATED_IR|EXTERNAL_IR|THERMAL)_([A-Z0-9_]+)_([0-9]{8}_[0-9]{6})\\.jpg$", RegexOption.IGNORE_CASE)
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
