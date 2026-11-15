package com.pwskills.mobile.pwpm02

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionBridgeScriptTest {
    @Test
    fun captionPipelineLoadsBeforeBridgeAndKeepsScriptBoundary() {
        val combined = CaptionBridgeScript.combine("window.PwCaptionPipeline = {};", "window.bridgeReady = true;")

        assertEquals("window.PwCaptionPipeline = {};\nwindow.bridgeReady = true;", combined)
        assertTrue(combined.indexOf("PwCaptionPipeline") < combined.indexOf("bridgeReady"))
    }
}
