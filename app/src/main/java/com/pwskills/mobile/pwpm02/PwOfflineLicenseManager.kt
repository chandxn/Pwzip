@file:OptIn(UnstableApi::class)

package com.pwskills.mobile.pwpm02

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.content.Context
import android.util.Pair
import androidx.media3.common.Format
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.OfflineLicenseHelper

/**
 * Standalone File: PwOfflineLicenseManager.kt
 * Classification: Newly Written (verified against official Media3 `OfflineLicenseHelper` API).
 *
 * Uses documented Media3 signatures:
 *   - OfflineLicenseHelper.newWidevineInstance(defaultLicenseUrl, forceDefaultLicenseUrl, dataSourceFactory, optionalKeyRequestParameters, eventDispatcher)
 *   - helper.downloadLicense(format: Format)
 *   - helper.getLicenseDurationRemainingSec(keySetId: ByteArray): Pair<Long, Long>
 *   - helper.renewLicense(keySetId: ByteArray): ByteArray
 *   - helper.releaseLicense(keySetId: ByteArray)
 */
data class PwOfflineEntitlement(
    val lessonId: String,
    val offlineAllowed: Boolean,
    val deliveryType: String,
    val mediaUrl: String?,
    val widevineLicenseUrl: String?,
    val requestHeaders: Map<String, String> = emptyMap(),
    val optionalKeyRequestParameters: Map<String, String> = emptyMap()
)

class PwOfflineLicenseManager(
    context: Context,
    private val licenseStorage: PwLicenseStorage = PwLicenseStorage(context.applicationContext)
) {
    fun acquirePersistentLicenseIfEntitled(
        lessonId: String,
        entitlement: PwOfflineEntitlement,
        drmFormat: Format
    ): ByteArray {
        require(entitlement.offlineAllowed) {
            "Provider entitlement denies offline download for lesson $lessonId (STREAMING_ONLY)."
        }
        val licenseUrl = entitlement.widevineLicenseUrl
            ?: throw IllegalStateException("Provider did not supply a Widevine offline license URL.")
        require(drmFormat.drmInitData != null) {
            "Supplied Media3 Format does not contain DrmInitData."
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(entitlement.requestHeaders)

        val eventDispatcher = DrmSessionEventListener.EventDispatcher()
        val helper = OfflineLicenseHelper.newWidevineInstance(
            licenseUrl,
            false,
            httpFactory,
            entitlement.optionalKeyRequestParameters,
            eventDispatcher
        )

        return try {
            val keySetId = helper.downloadLicense(drmFormat)
            val remaining: Pair<Long, Long> = helper.getLicenseDurationRemainingSec(keySetId)
            licenseStorage.saveKeySetId(
                lessonId = lessonId,
                keySetId = keySetId,
                licenseServerUrl = licenseUrl,
                licenseDurationRemainingSec = remaining.first,
                playbackDurationRemainingSec = remaining.second
            )
            keySetId
        } finally {
            helper.release()
        }
    }

    fun checkLicenseDurationRemainingSec(
        lessonId: String,
        licenseUrl: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): Pair<Long, Long> {
        val existingKeySetId = licenseStorage.getKeySetId(lessonId)
            ?: throw IllegalStateException("No stored keySetId found for lesson $lessonId")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
        val helper = OfflineLicenseHelper.newWidevineInstance(
            licenseUrl,
            httpFactory,
            DrmSessionEventListener.EventDispatcher()
        )
        return try {
            helper.getLicenseDurationRemainingSec(existingKeySetId)
        } finally {
            helper.release()
        }
    }

    fun renewPersistentLicense(
        lessonId: String,
        licenseUrl: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): ByteArray {
        val existingKeySetId = licenseStorage.getKeySetId(lessonId)
            ?: throw IllegalStateException("No stored keySetId found for lesson $lessonId")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
        val helper = OfflineLicenseHelper.newWidevineInstance(
            licenseUrl,
            httpFactory,
            DrmSessionEventListener.EventDispatcher()
        )
        return try {
            val renewed = helper.renewLicense(existingKeySetId)
            val remaining = helper.getLicenseDurationRemainingSec(renewed)
            licenseStorage.saveKeySetId(
                lessonId = lessonId,
                keySetId = renewed,
                licenseServerUrl = licenseUrl,
                licenseDurationRemainingSec = remaining.first,
                playbackDurationRemainingSec = remaining.second
            )
            renewed
        } finally {
            helper.release()
        }
    }

    fun releasePersistentLicense(
        lessonId: String,
        licenseUrl: String,
        requestHeaders: Map<String, String> = emptyMap()
    ) {
        val existingKeySetId = licenseStorage.getKeySetId(lessonId) ?: return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
        val helper = OfflineLicenseHelper.newWidevineInstance(
            licenseUrl,
            httpFactory,
            DrmSessionEventListener.EventDispatcher()
        )
        try {
            helper.releaseLicense(existingKeySetId)
        } finally {
            helper.release()
            licenseStorage.removeKeySetId(lessonId)
        }
    }
}
