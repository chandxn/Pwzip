package com.pwskills.mobile.pwpm02

import org.json.JSONObject

/**
 * File 4: DownloadItem.kt & DownloadDatabase.kt
 * Classification: Newly Written (implementing the exact model fields required for persistent download state).
 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    UNSUPPORTED
}

data class DownloadItem(
    val requestId: String,
    val sourceUrl: String,
    val fileName: String,
    val mimeType: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progress: Int,
    val destinationUri: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("requestId", requestId)
            .put("sourceUrl", sourceUrl)
            .put("fileName", fileName)
            .put("mimeType", mimeType)
            .put("status", status.name)
            .put("bytesDownloaded", bytesDownloaded)
            .put("totalBytes", totalBytes)
            .put("progress", progress)
            .put("destinationUri", destinationUri ?: JSONObject.NULL)
            .put("errorMessage", errorMessage ?: JSONObject.NULL)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): DownloadItem {
            val statusStr = obj.optString("status", DownloadStatus.QUEUED.name)
            val parsedStatus = try {
                DownloadStatus.valueOf(statusStr)
            } catch (_: Exception) {
                DownloadStatus.FAILED
            }
            return DownloadItem(
                requestId = obj.getString("requestId"),
                sourceUrl = obj.getString("sourceUrl"),
                fileName = obj.getString("fileName"),
                mimeType = obj.optString("mimeType", "video/mp4"),
                status = parsedStatus,
                bytesDownloaded = obj.optLong("bytesDownloaded", 0L),
                totalBytes = obj.optLong("totalBytes", 0L),
                progress = obj.optInt("progress", 0),
                destinationUri = if (obj.isNull("destinationUri")) null else obj.optString("destinationUri"),
                errorMessage = if (obj.isNull("errorMessage")) null else obj.optString("errorMessage"),
                createdAt = obj.optLong("createdAt", 0L),
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        }
    }
}
