package com.renardoberou.spectralcamera.core.camera

/**
 * Android camera characteristics arrive as android.util.Range and are converted
 * to Kotlin LongRange for application state. These names keep endpoint checks
 * explicit at the interop boundary without confusing inclusive `last` with a
 * collection index.
 */
internal val LongRange.lower: Long get() = first
internal val LongRange.upper: Long get() = last
