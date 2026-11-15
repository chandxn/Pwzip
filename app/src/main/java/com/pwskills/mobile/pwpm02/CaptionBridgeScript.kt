package com.pwskills.mobile.pwpm02

/** Keeps the pure-JavaScript caption normalizer ahead of the portal bridge that consumes it. */
internal object CaptionBridgeScript {
    fun combine(captionPipeline: String, portalBridge: String): String =
        "$captionPipeline\n$portalBridge"
}
