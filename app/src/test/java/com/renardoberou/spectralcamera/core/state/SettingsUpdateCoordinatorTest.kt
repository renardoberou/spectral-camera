package com.renardoberou.spectralcamera.core.state

import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.ManualAdjustments
import com.renardoberou.spectralcamera.core.GrainPolicy
import com.renardoberou.spectralcamera.core.WhiteBalancePreset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsUpdateCoordinatorTest {
    @Test
    fun unrelatedDelayedUpdatePreservesPreviouslySelectedExtremeGrain() = runBlocking {
        val coordinator = SettingsUpdateCoordinator(CameraSettings())
        coordinator.initialize(CameraSettings())

        coordinator.update { current ->
            CameraSettingsFieldIntents.grain(current, GrainPolicy.EXTREME.strength)
        }
        coordinator.update { current ->
            current.copy(whiteBalancePreset = WhiteBalancePreset.SUNNY)
        }

        assertEquals(GrainPolicy.EXTREME.strength, coordinator.current.value.adjustments.grain)
        assertEquals(WhiteBalancePreset.SUNNY, coordinator.current.value.whiteBalancePreset)
    }

    @Test
    fun staleUiSnapshotCannotEraseExtremeWhenContrastIntentMergesOneField() = runBlocking {
        val coordinator = SettingsUpdateCoordinator(CameraSettings())
        coordinator.initialize(CameraSettings())
        val staleUiSnapshot = coordinator.current.value

        coordinator.update { current ->
            CameraSettingsFieldIntents.grain(current, GrainPolicy.EXTREME.strength)
        }
        coordinator.update { current ->
            CameraSettingsFieldIntents.contrast(current, 1.6f)
        }

        assertEquals(GrainPolicy.EXTREME.strength, coordinator.current.value.adjustments.grain)
        assertEquals(1.6f, coordinator.current.value.adjustments.contrast)
        assertEquals(0f, staleUiSnapshot.adjustments.grain)
    }

    @Test
    fun updateWaitsForInitializationBeforeTransformingPersistedSettings() = runBlocking {
        val coordinator = SettingsUpdateCoordinator(CameraSettings())
        val pending = async {
            coordinator.update { current ->
                CameraSettingsFieldIntents.grain(current, GrainPolicy.EXTREME.strength)
            }
        }

        coordinator.initialize(CameraSettings(adjustments = ManualAdjustments(contrast = 1.25f)))

        assertEquals(1.25f, pending.await().adjustments.contrast)
        assertEquals(GrainPolicy.EXTREME.strength, coordinator.current.value.adjustments.grain)
    }
}
