package com.renardoberou.spectralcamera.core.state

import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.ManualAdjustments
import com.renardoberou.spectralcamera.core.GrainPolicy
import com.renardoberou.spectralcamera.core.WhiteBalancePreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsUpdateCoordinatorTest {
    @Test
    fun unrelatedDelayedUpdatePreservesPreviouslySelectedExtremeGrain() = runBlocking {
        val coordinator = SettingsUpdateCoordinator(CameraSettings())
        coordinator.initialize(CameraSettings())

        coordinator.update { current ->
            current.copy(adjustments = current.adjustments.copy(grain = GrainPolicy.EXTREME.strength))
        }
        coordinator.update { current ->
            current.copy(whiteBalancePreset = WhiteBalancePreset.SUNNY)
        }

        assertEquals(GrainPolicy.EXTREME.strength, coordinator.current.value.adjustments.grain)
        assertEquals(WhiteBalancePreset.SUNNY, coordinator.current.value.whiteBalancePreset)
    }
}
