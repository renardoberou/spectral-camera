package com.renardoberou.spectralcamera.core.gl

/** Keep development-only classifier pixels out of every non-debug renderer. */
internal fun classifierDebugEnabled(requested: Boolean, debugBuild: Boolean): Boolean =
    requested && debugBuild
