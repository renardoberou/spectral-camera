package com.renardoberou.spectralcamera.core.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestSettingsHandoffTest {
    @Test
    fun latestPublishedSettingsWinsOverOlderQueuedValues() {
        val handoff = LatestSettingsHandoff<String>()

        val offSequence = handoff.publish("off")
        val extremeSequence = handoff.publish("extreme")
        val mediumSequence = handoff.publish("medium")

        val pending = handoff.consumeNewerThan(0)

        assertEquals(mediumSequence, pending?.sequence)
        assertEquals("medium", pending?.value)
        assertEquals(offSequence + 1, extremeSequence)
        assertEquals(extremeSequence + 1, mediumSequence)
        assertNull(handoff.consumeNewerThan(mediumSequence))
    }
}
