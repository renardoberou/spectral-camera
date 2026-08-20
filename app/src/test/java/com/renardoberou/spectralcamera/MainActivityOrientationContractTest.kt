package com.renardoberou.spectralcamera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityOrientationContractTest {
    private val manifest: String = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun mainActivityUsesPortraitOrientationPolicy() {
        val activity = mainActivityDeclaration()

        assertTrue("MainActivity declaration is missing", activity != null)
        assertEquals("portrait", attribute(activity!!, "screenOrientation"))
    }

    @Test
    fun manifestDoesNotDeclareAutorotateOrientationPolicies() {
        assertFalse(
            "Manifest must not reintroduce fullSensor autorotation",
            manifest.contains("android:screenOrientation=\"fullSensor\""),
        )
        assertFalse(
            "Manifest must not leave orientation unspecified for autorotation",
            manifest.contains("android:screenOrientation=\"unspecified\""),
        )
    }

    private fun mainActivityDeclaration(): String? = Regex(
        "<activity\\s+[^>]*android:name=\"\\.MainActivity\"[^>]*>",
        RegexOption.DOT_MATCHES_ALL,
    ).find(manifest)?.value

    private fun attribute(element: String, name: String): String? =
        Regex("android:$name=\\\"([^\\\"]+)\\\"").find(element)?.groupValues?.get(1)
}
