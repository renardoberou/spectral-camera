package com.renardoberou.spectralcamera.core.media

import android.Manifest
import android.os.Build

enum class GalleryAccessLevel {
    FULL,
    PARTIAL,
    APP_OWNED_ONLY,
    DENIED,
}

object GalleryPermissionPolicy {
    fun requiredPermissions(sdkInt: Int): Array<String> = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun resolve(
        sdkInt: Int,
        hasReadMediaImages: Boolean,
        hasSelectedPhotoAccess: Boolean,
        hasLegacyStorageAccess: Boolean,
    ): GalleryAccessLevel = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasReadMediaImages ->
            GalleryAccessLevel.FULL
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasSelectedPhotoAccess ->
            GalleryAccessLevel.PARTIAL
        sdkInt >= Build.VERSION_CODES.TIRAMISU && hasReadMediaImages ->
            GalleryAccessLevel.FULL
        sdkInt <= Build.VERSION_CODES.S_V2 && hasLegacyStorageAccess ->
            GalleryAccessLevel.FULL
        sdkInt >= Build.VERSION_CODES.Q ->
            GalleryAccessLevel.APP_OWNED_ONLY
        else ->
            GalleryAccessLevel.DENIED
    }
}
