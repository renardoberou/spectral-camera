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
import com.renardoberou.spectralcamera.core.DoubleExposureMode
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
    companion object {
        /** Increment when the shared rendering stage changes saved-image meaning. */
        const val RENDERER_VERSION = 2
    }

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
        originals: List<Bitmap>,
        rawSidecars: List<File>,
        settings: CameraSettings,
        ultraHdr: Boolean,
        frameCount: Int,
        motionProtected: Boolean,
    ): CaptureResult {
        val timestamp = Instant.now()
        val safeFrameCount = when {
            settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED -> 2
            settings.hdrCaptureMode == HdrCaptureMode.OFF -> 1
            else -> frameCount.coerceIn(2, 9)
        }
        val captureToken = captureToken(settings, safeFrameCount, motionProtected)
        val captureLabel = captureModeLabel(captureToken)
        val processedToken = if (ultraHdr) "uhdr" else "proc"
        val processedDescription = if (ultraHdr) {
            "Processed Ultra HDR JPEG/R"
        } else {
            "Processed SDR JPEG"
        }
        val processedName = fileName(
            settings = settings,
            assetToken = processedToken,
            extension = "jpg",
            captureToken = captureToken,
            instant = timestamp,
        )
        val processedUri = insertBitmap(
            bitmap = processed,
            displayName = processedName,
            settings = settings,
            assetDescription = processedDescription,
            frameCount = safeFrameCount,
            motionProtected = motionProtected,
        )

        val originalUris = if (settings.saveOriginal) {
            originals.mapIndexed { index, bitmap ->
                val multiple = originals.size > 1
                val assetToken = if (multiple) {
                    "src${(index + 1).toString().padStart(2, '0')}"
                } else {
                    "orig"
                }
                val description = if (multiple) {
                    "Double-exposure source frame ${index + 1} of ${originals.size}"
                } else {
                    "Reference-exposure original JPEG"
                }
                val name = fileName(
                    settings = settings,
                    assetToken = assetToken,
                    extension = "jpg",
                    captureToken = captureToken,
                    instant = timestamp,
                )
                insertBitmap(
                    bitmap = bitmap,
                    displayName = name,
                    settings = settings,
                    assetDescription = description,
                    frameCount = safeFrameCount,
                    motionProtected = motionProtected,
                )
            }
        } else {
            emptyList()
        }

        val validRawFiles = rawSidecars.filter { it.isFile && it.length() > 0L }
        val rawUris = validRawFiles.mapIndexed { index, file ->
            val token = if (validRawFiles.size == 1) {
                "dng"
            } else {
                "dng${(index + 1).toString().padStart(2, '0')}"
            }
            val rawName = fileName(
                settings = settings,
                assetToken = token,
                extension = "dng",
                captureToken = captureToken,
                instant = timestamp,
            )
            insertRawSidecar(
                source = file,
                displayName = rawName,
                settings = settings,
                frameCount = safeFrameCount,
                bracketIndex = index,
                bracketSize = validRawFiles.size,
                motionProtected = motionProtected,
            )
        }

        return CaptureResult(
            processedUri = processedUri,
            originalUri = originalUris.firstOrNull(),
            originalUris = originalUris,
            rawUri = rawUris.firstOrNull(),
            rawUris = rawUris,
            displayName = processedName,
            ultraHdr = ultraHdr,
            captureModeLabel = captureLabel,
            captureDetail = "$safeFrameCount ${if (safeFrameCount == 1) "frame" else "frames"} • ${settings.outputMode.label}",
            frameCount = safeFrameCount,
            motionProtected = motionProtected,
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
                    // Monochrome presets need the hardware/simulation disclosure;
                    // visible-spectrum and Aerochrome presets need their actual
                    // rendering family instead of the default simulated-IR label.
                    sensorModeLabel = when (meta.preset.family) {
                        com.renardoberou.spectralcamera.core.LookFamily.MONOCHROME_IR -> meta.sensorMode.label
                        else -> meta.preset.family.label
                    },
                    isOriginal = meta.isOriginal,
                    isUltraHdr = meta.isUltraHdr,
                    captureModeLabel = meta.captureModeLabel,
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
        assetDescription: String,
        frameCount: Int,
        motionProtected: Boolean,
    ): Uri {
        val values = mediaValues(
            displayName = displayName,
            mimeType = "image/jpeg",
            description = description(
                settings = settings,
                assetDescription = assetDescription,
                frameCount = frameCount,
                motionProtected = motionProtected,
            ),
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
        frameCount: Int,
        bracketIndex: Int,
        bracketSize: Int,
        motionProtected: Boolean,
    ): Uri {
        val member = if (bracketSize > 1) {
            "RAW bracket frame ${bracketIndex + 1} of $bracketSize"
        } else {
            "Untouched RAW DNG sidecar"
        }
        val values = mediaValues(
            displayName = displayName,
            mimeType = "image/x-adobe-dng",
            description = description(
                settings = settings,
                assetDescription = member,
                frameCount = frameCount,
                motionProtected = motionProtected,
            ),
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
        frameCount: Int,
        motionProtected: Boolean,
    ): String {
        val capture = when {
            settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED ->
                "Two-frame balanced double exposure"
            settings.hdrCaptureMode == HdrCaptureMode.OFF ->
                "Standard capture"
            settings.hdrCaptureMode == HdrCaptureMode.THREE_FRAME ->
                "$frameCount-frame JPEG Computational HDR • ${settings.hdrToneMap.label} tone map"
            else ->
                "$frameCount-frame sensor-linear RAW HDR • ${settings.hdrToneMap.label} tone map"
        }
        val protection = if (motionProtected) " • motion-protected fusion" else ""
        return "renderer_version=$RENDERER_VERSION; profile_id=${settings.preset.name.lowercase(Locale.US)} • " +
            "${settings.sensorMode.label} • ${settings.preset.label} • ${settings.outputMode.label} • " +
            "Focus ${settings.focusMode.label} • $capture$protection • $assetDescription"
    }

    private fun captureToken(
        settings: CameraSettings,
        frameCount: Int,
        motionProtected: Boolean,
    ): String {
        val motionSuffix = if (motionProtected) "M" else ""
        return when {
            settings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED -> "DEXP2"
            settings.hdrCaptureMode == HdrCaptureMode.OFF -> "SDR1"
            settings.hdrCaptureMode == HdrCaptureMode.THREE_FRAME ->
                "JHDR${frameCount.coerceIn(2, 9)}$motionSuffix"
            else -> "RHDR${frameCount.coerceIn(2, 9)}$motionSuffix"
        }
    }

    private fun fileName(
        settings: CameraSettings,
        assetToken: String,
        extension: String,
        captureToken: String,
        instant: Instant,
    ): String {
        val stamp = timestampFormatter.format(instant)
        return "spectral_${assetToken}_${settings.sensorMode.name}_${settings.preset.name}_${settings.outputMode.name}_${captureToken}_$stamp.$extension"
    }

    private data class ParsedName(
        val preset: SpectralPreset,
        val isOriginal: Boolean,
        val sensorMode: SensorMode,
        val outputMode: OutputMode,
        val isUltraHdr: Boolean,
        val captureModeLabel: String,
    )

    private fun parseName(displayName: String): ParsedName? {
        val currentRegex = Regex(
            "^spectral_(proc|uhdr|orig|src[0-9]{2})_(SIMULATED_IR|EXTERNAL_IR|THERMAL)_([A-Z0-9_]+)_(FULL_RESOLUTION|HQ_1080|FAST_1080)_((?:J|R)?HDR[2-9]M?|DEXP2|SDR1)_([0-9]{8}_[0-9]{6})\\.jpg$",
            RegexOption.IGNORE_CASE,
        )
        currentRegex.find(displayName)?.let { match ->
            val asset = match.groupValues[1].lowercase(Locale.US)
            val preset = runCatching {
                SpectralPreset.valueOf(match.groupValues[3].uppercase(Locale.US))
            }.getOrNull() ?: return null
            val sensorMode = runCatching {
                SensorMode.valueOf(match.groupValues[2].uppercase(Locale.US))
            }.getOrDefault(SensorMode.SIMULATED_IR)
            val outputMode = runCatching {
                OutputMode.valueOf(match.groupValues[4].uppercase(Locale.US))
            }.getOrDefault(OutputMode.FULL_RESOLUTION)
            val token = match.groupValues[5].uppercase(Locale.US)
            return ParsedName(
                preset = preset,
                isOriginal = asset == "orig" || asset.startsWith("src"),
                sensorMode = sensorMode,
                outputMode = outputMode,
                isUltraHdr = asset == "uhdr",
                captureModeLabel = captureModeLabel(token),
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
                captureModeLabel = "Standard",
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
            captureModeLabel = "Standard",
        )
    }

    private fun captureModeLabel(token: String): String {
        val normalized = token.uppercase(Locale.US)
        val motion = normalized.endsWith("M")
        val base = when {
            normalized == "DEXP2" -> "Double Exposure"
            normalized.startsWith("RHDR") -> "True RAW HDR"
            normalized.startsWith("JHDR") ||
                (normalized.startsWith("HDR") && normalized != "SDR1") -> "Computational HDR"
            else -> "Standard"
        }
        return if (motion) "$base • motion protected" else base
    }
}
