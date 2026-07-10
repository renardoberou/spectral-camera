package com.renardoberou.spectralcamera.core.media

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryPermissionPolicyTest {
    @Test
    fun android14FullAccessWinsOverSelectedAccess() {
        assertEquals(
            GalleryAccessLevel.FULL,
            GalleryPermissionPolicy.resolve(
                sdkInt = 34,
                hasReadMediaImages = true,
                hasSelectedPhotoAccess = true,
                hasLegacyStorageAccess = false,
            ),
        )
    }

    @Test
    fun android14SelectedAccessIsPartial() {
        assertEquals(
            GalleryAccessLevel.PARTIAL,
            GalleryPermissionPolicy.resolve(
                sdkInt = 34,
                hasReadMediaImages = false,
                hasSelectedPhotoAccess = true,
                hasLegacyStorageAccess = false,
            ),
        )
    }

    @Test
    fun android14WithoutGrantStillHasAppOwnedAccess() {
        assertEquals(
            GalleryAccessLevel.APP_OWNED_ONLY,
            GalleryPermissionPolicy.resolve(
                sdkInt = 34,
                hasReadMediaImages = false,
                hasSelectedPhotoAccess = false,
                hasLegacyStorageAccess = false,
            ),
        )
    }

    @Test
    fun android13UsesReadMediaImages() {
        assertEquals(
            GalleryAccessLevel.FULL,
            GalleryPermissionPolicy.resolve(
                sdkInt = 33,
                hasReadMediaImages = true,
                hasSelectedPhotoAccess = false,
                hasLegacyStorageAccess = false,
            ),
        )
    }

    @Test
    fun android12WithoutLegacyGrantHasAppOwnedAccess() {
        assertEquals(
            GalleryAccessLevel.APP_OWNED_ONLY,
            GalleryPermissionPolicy.resolve(
                sdkInt = 32,
                hasReadMediaImages = false,
                hasSelectedPhotoAccess = false,
                hasLegacyStorageAccess = false,
            ),
        )
    }

    @Test
    fun android8WithoutLegacyGrantIsDenied() {
        assertEquals(
            GalleryAccessLevel.DENIED,
            GalleryPermissionPolicy.resolve(
                sdkInt = 26,
                hasReadMediaImages = false,
                hasSelectedPhotoAccess = false,
                hasLegacyStorageAccess = false,
            ),
        )
    }

    @Test
    fun requiredPermissionsFollowAndroidStorageModel() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            GalleryPermissionPolicy.requiredPermissions(34),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
            GalleryPermissionPolicy.requiredPermissions(33),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            GalleryPermissionPolicy.requiredPermissions(32),
        )
    }
}
