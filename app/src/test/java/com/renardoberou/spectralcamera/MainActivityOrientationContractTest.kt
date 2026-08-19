package com.renardoberou.spectralcamera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityOrientationContractTest {
    private val manifest: String = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun mainActivityUsesFullSensorOrientationPolicy() {
        val activity = Regex(
            "<activity\\s+[^>]*android:name=\"\\.MainActivity\"[^>]*>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)?.value

        assertTrue("MainActivity declaration is missing", activity != null)
        assertEquals("fullSensor", attribute(activity!!, "screenOrientation"))
    }

    @Test
    fun mainActivityIsNotPortraitLocked() {
        val activity = Regex(
            "<activity\\s+[^>]*android:name=\"\\.MainActivity\"[^>]*>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)?.value.orEmpty()

        assertFalse(
            "MainActivity must not reintroduce a portrait-only policy",
            attribute(activity, "screenOrientation") == "portrait",
        )
    }

    private fun attribute(element: String, name: String): String? =
        Regex("android:$name=\\\"([^\\\"]+)\\\"").find(element)?.groupValues?.get(1)
}
