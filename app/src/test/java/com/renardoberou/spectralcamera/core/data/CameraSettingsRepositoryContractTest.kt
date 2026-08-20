package com.renardoberou.spectralcamera.core.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsRepositoryContractTest {
    private val repositorySource = File(
        "src/main/java/com/renardoberou/spectralcamera/core/data/CameraSettingsRepository.kt",
    ).readText()

    @Test
    fun `empty preference store resolves to Warm Negative`() {
        assertTrue(
            repositorySource.contains("?: SpectralPreset.WARM_NEGATIVE"),
        )
        assertFalse(
            repositorySource.contains("?: SpectralPreset.B_W_INFRARED"),
        )
    }
}
