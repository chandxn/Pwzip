package com.pwskills.mobile.pwpm02

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * Standalone File: PwLicenseStorage.kt
 * Classification: Newly Written (persists Widevine offline keySetId and expiry metadata).
 */
data class StoredLicenseMetadata(
    val lessonId: String,
    val keySetId: ByteArray,
    val licenseServerUrl: String,
    val licenseDurationRemainingSec: Long,
    val playbackDurationRemainingSec: Long,
    val savedAtMs: Long,
    val expiresAtMs: Long
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    fun toJson(): JSONObject = JSONObject()
        .put("lessonId", lessonId)
        .put("keySetIdBase64", Base64.encodeToString(keySetId, Base64.NO_WRAP))
        .put("licenseServerUrl", licenseServerUrl)
        .put("licenseDurationRemainingSec", licenseDurationRemainingSec)
        .put("playbackDurationRemainingSec", playbackDurationRemainingSec)
        .put("savedAtMs", savedAtMs)
        .put("expiresAtMs", expiresAtMs)
        .put("expired", isExpired())
}

class PwLicenseStorage(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pw_pm_widevine_licenses", Context.MODE_PRIVATE)

    fun saveKeySetId(
        lessonId: String,
        keySetId: ByteArray,
        licenseServerUrl: String = "",
        licenseDurationRemainingSec: Long,
        playbackDurationRemainingSec: Long
    ) {
        val now = System.currentTimeMillis()
        val effectiveSec = if (licenseDurationRemainingSec > 0 && licenseDurationRemainingSec < Long.MAX_VALUE / 2000) {
            licenseDurationRemainingSec
        } else {
            172_800L // 48 hours fallback when license server returns C.TIME_UNSET
        }
        val expiresAtMs = now + (effectiveSec * 1000L)
        val encoded = Base64.encodeToString(keySetId, Base64.NO_WRAP)
        prefs.edit()
            .putString("keyset_$lessonId", encoded)
            .putString("lic_url_$lessonId", licenseServerUrl)
            .putLong("lic_rem_$lessonId", licenseDurationRemainingSec)
            .putLong("play_rem_$lessonId", playbackDurationRemainingSec)
            .putLong("saved_at_$lessonId", now)
            .putLong("expires_at_$lessonId", expiresAtMs)
            .apply()
    }

    fun getKeySetId(lessonId: String): ByteArray? {
        val encoded = prefs.getString("keyset_$lessonId", null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    fun getLicenseMetadata(lessonId: String): StoredLicenseMetadata? {
        val keySetId = getKeySetId(lessonId) ?: return null
        return StoredLicenseMetadata(
            lessonId = lessonId,
            keySetId = keySetId,
            licenseServerUrl = prefs.getString("lic_url_$lessonId", "") ?: "",
            licenseDurationRemainingSec = prefs.getLong("lic_rem_$lessonId", 0L),
            playbackDurationRemainingSec = prefs.getLong("play_rem_$lessonId", 0L),
            savedAtMs = prefs.getLong("saved_at_$lessonId", 0L),
            expiresAtMs = prefs.getLong("expires_at_$lessonId", 0L)
        )
    }

    fun removeKeySetId(lessonId: String) {
        prefs.edit()
            .remove("keyset_$lessonId")
            .remove("lic_url_$lessonId")
            .remove("lic_rem_$lessonId")
            .remove("play_rem_$lessonId")
            .remove("saved_at_$lessonId")
            .remove("expires_at_$lessonId")
            .apply()
    }
}
