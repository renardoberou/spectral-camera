package com.renardoberou.spectralcamera.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.CaptureResult
import com.renardoberou.spectralcamera.core.GalleryItem
import com.renardoberou.spectralcamera.core.HdrCaptureMode
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
        rawSidecars: List<File>,
        settings: CameraSettings,
        ultraHdr: Boolean,
        hdrFrameCount: Int,
    ): CaptureResult {
        val timestamp = Instant.now()
        val frameCount = when (settings.hdrCaptureMode) {
            HdrCaptureMode.OFF -> 1
            HdrCaptureMode.THREE_FRAME,
            HdrCaptureMode.RAW_THREE_FRAME,
            -> hdrFrameCount.coerceIn(2, 9)
        }
        val processedAsset = if (ultraHdr) CaptureAsset.ULTRA_HDR else CaptureAsset.PROCESSED
        val processedName = fileName(settings, processedAsset.token, processedAsset.extension, timestamp, frameCount)
        val processedUri = insertBitmap(
            bitmap = processed,
            displayName = processedName,
            settings = settings,
            asset = processedAsset,
            hdrFrameCount = frameCount,
        )
        val originalUri = if (settings.saveOriginal && original != null) {
            val originalName = fileName(
                settings,
                CaptureAsset.ORIGINAL_JPEG.token,
                CaptureAsset.ORIGINAL_JPEG.extension,
                timestamp,
                frameCount,
            )
            insertBitmap(
                bitmap = original,
                displayName = originalName,
                settings = settings,
                asset = CaptureAsset.ORIGINAL_JPEG,
                hdrFrameCount = frameCount,
            )
        } else {
            null
        }

        val validRawFiles = rawSidecars.filter { it.isFile && it.length() > 0L }
        val rawUris = validRawFiles.mapIndexed { index, file ->
            val token = if (validRawFiles.size == 1) "dng" else "dng${(index + 1).toString().padStart(2, '0')}"
            val rawName = fileName(
                settings = settings,
                assetToken = token,
                extension = "dng",
                instant = timestamp,
                frameCount = frameCount,
            )
            insertRawSidecar(
                source = file,
                displayName = rawName,
                settings = settings,
                hdrFrameCount = frameCount,
                bracketIndex = index,
                bracketSize = validRawFiles.size,
            )
        }

        return CaptureResult(
            processedUri = processedUri,
            originalUri = originalUri,
            rawUri = rawUris.firstOrNull(),
            rawUris = rawUris,
            displayName = processedName,
            ultraHdr = ultraHdr,
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
                // DNG bracket members deliberately stay out of the JPEG gallery.
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
                    isUltraHdr = meta.isUltraHdr,
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
        hdrFrameCount: Int,
    ): Uri {
        val values = mediaValues(
            displayName = displayName,
            mimeType = "image/jpeg",
            description = description(settings, asset.description, hdrFrameCount),
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
        hdrFrameCount: Int,
        bracketIndex: Int,
        bracketSize: Int,
    ): Uri {
        val member = if (bracketSize > 1) {
            "RAW bracket frame ${bracketIndex + 1} of $bracketSize"
        } else {
            "Untouched RAW DNG sidecar"
        }
        val values = mediaValues(
            displayName = displayName,
            mimeType = "image/x-adobe-dng",
            description = description(settings, member, hdrFrameCount),
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
            put(MediaStore.Images.Media.DATA, File(legacyCurrentDirectory, displayName).absolutePath)
        }
    }

    private fun publishPending(uri: Uri, values: ContentValues) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private fun description(
        settings: CameraSettings,
        assetDescription: String,
        hdrFrameCount: Int,
    ): String {
        val capture = when (settings.hdrCaptureMode) {
            HdrCaptureMode.OFF -> "Standard capture"
            HdrCaptureMode.THREE_FRAME ->
                "$hdrFrameCount-frame JPEG Computational HDR • ${settings.hdrToneMap.label} tone map"
            HdrCaptureMode.RAW_THREE_FRAME ->
                "$hdrFrameCount-frame sensor-linear RAW HDR • ${settings.hdrToneMap.label} tone map"
        }
        return "${settings.sensorMode.label} • ${settings.preset.label} • ${settings.outputMode.label} • $capture • $assetDescription"
    }

    private fun fileName(
        settings: CameraSettings,
        assetToken: String,
        extension: String,
        instant: Instant,
        frameCount: Int,
    ): String {
        val stamp = timestampFormatter.format(instant)
        val captureToken = when (settings.hdrCaptureMode) {
            HdrCaptureMode.OFF -> "SDR1"
            HdrCaptureMode.THREE_FRAME -> "JHDR${frameCount.coerceIn(2, 9)}"
            HdrCaptureMode.RAW_THREE_FRAME -> "RHDR${frameCount.coerceIn(2, 9)}"
        }
        return "spectral_${assetToken}_${settings.sensorMode.name}_${settings.preset.name}_${settings.outputMode.name}_${captureToken}_$stamp.$extension"
    }

    private enum class CaptureAsset(
        val token: String,
        val extension: String,
        val description: String,
    ) {
        PROCESSED("proc", "jpg", "Processed SDR JPEG"),
        ULTRA_HDR("uhdr", "jpg", "Processed Ultra HDR JPEG/R"),
        ORIGINAL_JPEG("orig", "jpg", "Reference-exposure original JPEG"),
    }

    private data class ParsedName(
        val preset: SpectralPreset,
        val isOriginal: Boolean,
        val sensorMode: SensorMode,
        val outputMode: OutputMode,
        val isUltraHdr: Boolean,
    )

    private fun parseName(displayName: String): ParsedName? {
        val currentRegex = Regex(
            "^spectral_(proc|uhdr|orig)_(SIMULATED_IR|EXTERNAL_IR|THERMAL)_([A-Z0-9_]+)_(FULL_RESOLUTION|HQ_1080|FAST_1080)_((?:J|R)?HDR[2-9]|SDR1)_([0-9]{8}_[0-9]{6})\\.jpg$",
            RegexOption.IGNORE_CASE,
        )
        currentRegex.find(displayName)?.let { match ->
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
                isUltraHdr = match.groupValues[1].equals("uhdr", ignoreCase = true),
            )
        }

        val proOutputRegex = Regex(
            "^spectral_(proc|orig)_(SIMULATED_IR|EXTERNAL_IR|THERMAL)_([A-Z0-9_]+)_(FULL_RESOLUTION|HQ_1080|FAST_1080)_([0-9]{8}_[0-9]{6})\\.jpg$",
            RegexOption.IGNORE_CASE,
        )
        proOutputRegex.find(displayName)?.let { match ->
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
                isUltraHdr = false,
            )
        }

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
            isUltraHdr = false,
        )
    }
}
