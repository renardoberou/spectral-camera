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
import com.renardoberou.spectralcamera.core.OutputMode
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
        rawSidecar: File?,
        settings: CameraSettings,
    ): CaptureResult {
        val timestamp = Instant.now()
        val processedName = fileName(settings, CaptureAsset.PROCESSED, timestamp)
        val processedUri = insertBitmap(processed, processedName, settings, CaptureAsset.PROCESSED)
        val originalUri = if (settings.saveOriginal && original != null) {
            val originalName = fileName(settings, CaptureAsset.ORIGINAL_JPEG, timestamp)
            insertBitmap(original, originalName, settings, CaptureAsset.ORIGINAL_JPEG)
        } else {
            null
        }
        val rawUri = rawSidecar?.takeIf { it.isFile && it.length() > 0L }?.let { file ->
            val rawName = fileName(settings, CaptureAsset.RAW_DNG, timestamp)
            insertRawSidecar(file, rawName, settings)
        }
        return CaptureResult(
            processedUri = processedUri,
            originalUri = originalUri,
            rawUri = rawUri,
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
                // DNG sidecars intentionally stay out of the in-app JPEG gallery.
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
        asset: CaptureAsset,
    ): android.net.Uri {
        val values = mediaValues(
            displayName = displayName,
            mimeType = "image/jpeg",
            description = description(settings, asset),
        )
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore item")

        try {
            resolver.openOutputStream(uri, "w")?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)) {
                    throw IOException("Failed to compress bitmap")
                }
            } ?: throw IOException("Failed to open output stream")
            publishPending(uri, values)
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun insertRawSidecar(
        source: File,
        displayName: String,
        settings: CameraSettings,
    ): android.net.Uri {
        val values = mediaValues(
            displayName = displayName,
            mimeType = "image/x-adobe-dng",
            description = description(settings, CaptureAsset.RAW_DNG),
        )
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create RAW MediaStore item")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw IOException("Failed to open RAW output stream")
            publishPending(uri, values)
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun mediaValues(
        displayName: String,
        mimeType: String,
        description: String,
    ): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        put(MediaStore.Images.Media.DESCRIPTION, description)
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

    private fun publishPending(uri: android.net.Uri, values: ContentValues) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private fun description(settings: CameraSettings, asset: CaptureAsset): String =
        "${settings.sensorMode.label} • ${settings.preset.label} • ${settings.outputMode.label} • ${asset.description}"

    private fun fileName(settings: CameraSettings, asset: CaptureAsset, instant: Instant): String {
        val stamp = timestampFormatter.format(instant)
        return "spectral_${asset.token}_${settings.sensorMode.name}_${settings.preset.name}_${settings.outputMode.name}_$stamp.${asset.extension}"
    }

    private enum class CaptureAsset(
        val token: String,
        val extension: String,
        val description: String,
    ) {
        PROCESSED("proc", "jpg", "Processed export"),
        ORIGINAL_JPEG("orig", "jpg", "Original JPEG source"),
        RAW_DNG("dng", "dng", "Untouched RAW DNG sidecar"),
    }

    private data class ParsedName(
        val preset: SpectralPreset,
        val isOriginal: Boolean,
        val sensorMode: SensorMode,
        val outputMode: OutputMode,
    )

    private fun parseName(displayName: String): ParsedName? {
        val newRegex = Regex(
            "^spectral_(proc|orig)_(SIMULATED_IR|EXTERNAL_IR|THERMAL)_([A-Z0-9_]+)_(FULL_RESOLUTION|HQ_1080|FAST_1080)_([0-9]{8}_[0-9]{6})\\.jpg$",
            RegexOption.IGNORE_CASE,
        )
        newRegex.find(displayName)?.let { match ->
            val preset = runCatching {
                SpectralPreset.valueOf(match.groupValues[3].uppercase(Locale.US))
            }.getOrNull() ?: return null
            val sensorMode = runCatching {
                SensorMode.valueOf(match.groupValues[2].uppercase(Locale.US))
            }.getOrDefault(SensorMode.SIMULATED_IR)
            val outputMode = runCatching {
                OutputMode.valueOf(match.groupValues[4].uppercase(Locale.US))
            }.getOrDefault(OutputMode.FULL_RESOLUTION)
            return ParsedName(
                preset = preset,
                isOriginal = match.groupValues[1].equals("orig", ignoreCase = true),
                sensorMode = sensorMode,
                outputMode = outputMode,
            )
        }

        // Backward compatibility: earlier builds called the untouched JPEG
        // "raw" even though it was never a RAW/DNG sensor file.
        val legacyRegex = Regex(
            "^spectral_(raw|proc)_(SIMULATED_IR|EXTERNAL_IR|THERMAL)_([A-Z0-9_]+)_([0-9]{8}_[0-9]{6})\\.jpg$",
            RegexOption.IGNORE_CASE,
        )
        val match = legacyRegex.find(displayName) ?: return null
        val preset = runCatching {
            SpectralPreset.valueOf(match.groupValues[3].uppercase(Locale.US))
        }.getOrNull() ?: return null
        val sensorMode = runCatching {
            SensorMode.valueOf(match.groupValues[2].uppercase(Locale.US))
        }.getOrDefault(SensorMode.SIMULATED_IR)
        return ParsedName(
            preset = preset,
            isOriginal = match.groupValues[1].equals("raw", ignoreCase = true),
            sensorMode = sensorMode,
            outputMode = OutputMode.FULL_RESOLUTION,
        )
    }
}
