package com.pwskills.mobile.pwpm02

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * File 2: DownloadRepository.kt
 * Classification: Reconstructed & Completed from DEX signature + production WorkManager pipeline.
 *
 * Responsibilities:
 * - Validate direct HTTPS URLs and reject unsupported DRM or unauthorized manifests.
 * - Sanitize filenames and prevent path traversal.
 * - Prevent duplicate active downloads for the same source URL.
 * - Persist download state transitions (QUEUED, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, FAILED, UNSUPPORTED).
 * - Schedule DownloadWorker via AndroidX WorkManager.
 */
class DownloadRepository private constructor(private val context: Context) {

    private val database = DownloadDatabase(context.applicationContext)
    private val workManager = WorkManager.getInstance(context.applicationContext)

    data class ValidationResult(
        val allowed: Boolean,
        val status: String,
        val reason: String
    )

    fun sanitizeFileName(rawName: String): String {
        val cleaned = rawName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("..", "_")
        return if (cleaned.isNotEmpty()) cleaned else "pw_pm_lecture_${System.currentTimeMillis()}.mp4"
    }

    fun validateRequestSynchronously(
        url: String,
        fileName: String,
        mimeType: String
    ): ValidationResult {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("https://", ignoreCase = true) &&
            !trimmedUrl.startsWith("file:///android_asset/dev_", ignoreCase = true)
        ) {
            return ValidationResult(
                allowed = false,
                status = DownloadStatus.UNSUPPORTED.name,
                reason = "Only HTTPS URLs or bundled developer verification assets are permitted."
            )
        }

        val lowerUrl = trimmedUrl.lowercase()
        val lowerMime = mimeType.lowercase()

        // Never pretend an HLS (.m3u8) or DASH (.mpd) manifest is a direct progressive MP4 download
        if (lowerUrl.contains(".m3u8") || lowerMime.contains("mpegurl") ||
            lowerUrl.contains(".mpd") || lowerMime.contains("dash+xml")
        ) {
            if (lowerUrl.contains("drm") || lowerUrl.contains("widevine") || lowerUrl.contains("enc")) {
                return ValidationResult(
                    allowed = false,
                    status = DownloadStatus.UNSUPPORTED.name,
                    reason = "Protected streaming manifest (DRM/Widevine). Offline download requires provider offline entitlement and persistent Widevine license."
                )
            }
            return ValidationResult(
                allowed = false,
                status = DownloadStatus.UNSUPPORTED.name,
                reason = "Adaptive HLS/DASH stream must use PwDownloadEngine (Media3 SimpleCache) and cannot be saved directly as a standalone .mp4 without remuxing."
            )
        }

        val safeName = sanitizeFileName(fileName)
        if (!DownloadWorker.isSafeFileName(safeName)) {
            return ValidationResult(
                allowed = false,
                status = DownloadStatus.FAILED.name,
                reason = "Unsafe target file name rejected."
            )
        }

        return ValidationResult(
            allowed = true,
            status = DownloadStatus.QUEUED.name,
            reason = "Approved direct file download."
        )
    }

    @Synchronized
    fun enqueueAuthorizedDirectDownload(
        requestId: String,
        url: String,
        fileName: String,
        mimeType: String
    ): DownloadItem {
        val safeName = sanitizeFileName(fileName)
        val validation = validateRequestSynchronously(url, safeName, mimeType)
        val now = System.currentTimeMillis()

        if (!validation.allowed) {
            val rejected = DownloadItem(
                requestId = requestId,
                sourceUrl = url,
                fileName = safeName,
                mimeType = mimeType,
                status = DownloadStatus.valueOf(validation.status),
                bytesDownloaded = 0L,
                totalBytes = 0L,
                progress = 0,
                destinationUri = null,
                errorMessage = validation.reason,
                createdAt = now,
                updatedAt = now
            )
            database.upsert(rejected)
            return rejected
        }

        // Prevent duplicate active downloads for the same sourceUrl
        val existing = database.findBySourceUrl(url)
        if (existing != null && (existing.status == DownloadStatus.QUEUED ||
                    existing.status == DownloadStatus.DOWNLOADING ||
                    existing.status == DownloadStatus.VERIFYING)
        ) {
            return existing
        }

        val item = DownloadItem(
            requestId = requestId,
            sourceUrl = url,
            fileName = safeName,
            mimeType = mimeType,
            status = DownloadStatus.QUEUED,
            bytesDownloaded = 0L,
            totalBytes = 0L,
            progress = 0,
            destinationUri = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now
        )
        database.upsert(item)
        scheduleWorkerForItem(item)
        return item
    }

    /**
     * Preserved static/instance helper matching PW-PM.apk DEX signature:
     * scheduleDownload(Context, String, String)
     */
    fun scheduleDownload(context: Context, url: String, fileName: String) {
        val requestId = "req_${System.currentTimeMillis()}"
        val mime = if (fileName.endsWith(".pdf", true)) "application/pdf" else "video/mp4"
        val item = DownloadItem(
            requestId = requestId,
            sourceUrl = url,
            fileName = sanitizeFileName(fileName),
            mimeType = mime,
            status = DownloadStatus.QUEUED,
            bytesDownloaded = 0L,
            totalBytes = 0L,
            progress = 0,
            destinationUri = null,
            errorMessage = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        database.upsert(item)
        scheduleWorkerForItem(item)
    }

    private fun scheduleWorkerForItem(item: DownloadItem) {
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_REQUEST_ID, item.requestId)
            .putString(DownloadWorker.KEY_SOURCE_URL, item.sourceUrl)
            .putString(DownloadWorker.KEY_FILE_NAME, item.fileName)
            .putString(DownloadWorker.KEY_MIME_TYPE, item.mimeType)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("pw_pm_download_${item.requestId}")
            .build()

        workManager.enqueueUniqueWork(
            "pw_pm_work_${item.requestId}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun pauseDownload(requestId: String): Boolean {
        val existing = database.findById(requestId) ?: return false
        workManager.cancelUniqueWork("pw_pm_work_$requestId")
        database.upsert(
            existing.copy(
                status = DownloadStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    fun resumeDownload(requestId: String): Boolean {
        val existing = database.findById(requestId) ?: return false
        val resumed = existing.copy(
            status = DownloadStatus.QUEUED,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        database.upsert(resumed)
        scheduleWorkerForItem(resumed)
        return true
    }

    fun cancelDownload(requestId: String): Boolean {
        val existing = database.findById(requestId) ?: return false
        workManager.cancelUniqueWork("pw_pm_work_$requestId")
        database.delete(requestId)
        val tempPart = java.io.File(context.cacheDir, "pw_pm_part_${requestId}.part")
        if (tempPart.exists()) {
            tempPart.delete()
        }
        return true
    }

    fun retryDownload(requestId: String): Boolean {
        val existing = database.findById(requestId) ?: return false
        val retried = existing.copy(
            status = DownloadStatus.QUEUED,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        database.upsert(retried)
        scheduleWorkerForItem(retried)
        return true
    }

    fun getAllDownloads(): List<DownloadItem> = database.getAll()

    companion object {
        @Volatile
        private var INSTANCE: DownloadRepository? = null

        fun getInstance(context: Context): DownloadRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
