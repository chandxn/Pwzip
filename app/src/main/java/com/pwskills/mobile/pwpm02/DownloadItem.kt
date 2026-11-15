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
            // Signed URLs and provider query tokens never leave native storage.
            .put("fileName", fileName)
            .put("mimeType", mimeType)
            .put("status", status.name)
            .put("bytesDownloaded", bytesDownloaded)
            .put("totalBytes", totalBytes)
            .put("progress", progress)
            // The native UI opens by request id; never expose filesystem or MediaStore URIs to JS.
            .put("hasPlayableFile", status == DownloadStatus.COMPLETED && destinationUri != null)
            .put("errorMessage", errorMessage?.replace(Regex("https?://\\S+"), "[redacted URL]")
                ?.replace(Regex("(?i)(?:file:/+|/(?:data|storage|mnt)/)[^\\s,;]+"), "[private path]") ?: JSONObject.NULL)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
    }

    fun canTransitionTo(next: DownloadStatus): Boolean = when (status) {
        DownloadStatus.QUEUED -> next in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.UNSUPPORTED)
        DownloadStatus.DOWNLOADING -> next in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.VERIFYING, DownloadStatus.FAILED, DownloadStatus.UNSUPPORTED)
        DownloadStatus.PAUSED -> next in setOf(DownloadStatus.PAUSED, DownloadStatus.QUEUED, DownloadStatus.FAILED)
        DownloadStatus.VERIFYING -> next in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        DownloadStatus.COMPLETED -> next == DownloadStatus.FAILED
        DownloadStatus.FAILED -> next in setOf(DownloadStatus.FAILED, DownloadStatus.QUEUED)
        DownloadStatus.UNSUPPORTED -> next == DownloadStatus.UNSUPPORTED
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
                sourceUrl = obj.optString("sourceUrl", ""),
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
