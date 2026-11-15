package com.pwskills.mobile.pwpm02

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import android.os.Environment
import java.io.File

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
        if (!trimmedUrl.startsWith("https://", ignoreCase = true)) {
            return ValidationResult(
                allowed = false,
                status = DownloadStatus.UNSUPPORTED.name,
                reason = "Only provider-authorized HTTPS URLs are supported."
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
        val existing = database.findBySourceUrl(url)?.let(::reconcileCompletedItem)
        if (existing != null && (existing.status == DownloadStatus.QUEUED ||
                    existing.status == DownloadStatus.DOWNLOADING ||
                    existing.status == DownloadStatus.VERIFYING ||
                    existing.status == DownloadStatus.PAUSED ||
                    existing.status == DownloadStatus.COMPLETED)
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
        // Legacy signature retained for binary compatibility. It has no lesson/entitlement
        // context, so it cannot safely schedule a provider download.
        throw SecurityException("Download requires an explicit provider authorization grant.")
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
        if (existing.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)) return false
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
        if (existing.status != DownloadStatus.PAUSED) return false
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
        if (existing.destinationUri != null && !deleteDestination(existing)) return false
        val tempPart = DownloadPaths.partialFile(context, requestId)
        if (tempPart.exists() && !tempPart.delete()) return false
        database.delete(requestId)
        return true
    }

    fun deleteDownload(requestId: String): Boolean = cancelDownload(requestId)

    private fun deleteDestination(item: DownloadItem): Boolean {
        val raw = item.destinationUri ?: return true
        val uri = Uri.parse(raw)
        if (uri.scheme == "content") {
            val expectedAuthority = android.provider.MediaStore.AUTHORITY
            val allowedAuthorities = setOf(expectedAuthority, "media")
            if (uri.authority !in allowedAuthorities) return false
            return runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        }
        val candidate = runCatching {
            File(if (uri.scheme == "file") uri.path ?: return false else raw).canonicalFile
        }.getOrNull() ?: return false
        val externalDocs = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.canonicalFile
        val appFiles = context.filesDir.canonicalFile
        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).canonicalFile
        val roots = listOfNotNull(
            externalDocs?.let { File(it, "PW Triple/PW-PM").canonicalFile },
            File(appFiles, "PW Triple/PW-PM").canonicalFile,
            File(publicDocs, "PW Triple/PW-PM").canonicalFile
        )
        val ownedPath = roots.any { root -> candidate.path.startsWith(root.path + File.separator, true) }
        if (!ownedPath || candidate.name != item.fileName) return false
        return !candidate.exists() || candidate.delete()
    }

    fun retryDownload(requestId: String): Boolean {
        val existing = database.findById(requestId) ?: return false
        if (existing.status != DownloadStatus.FAILED) return false
        val retried = existing.copy(
            status = DownloadStatus.QUEUED,
            bytesDownloaded = 0L,
            totalBytes = 0L,
            progress = 0,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        database.upsert(retried)
        scheduleWorkerForItem(retried)
        return true
    }

    fun getAllDownloads(): List<DownloadItem> = database.getAll().map { reconcileWorkStatus(reconcileCompletedItem(it)) }

    fun getDownload(requestId: String): DownloadItem? = database.findById(requestId)
        ?.let(::reconcileCompletedItem)?.let(::reconcileWorkStatus)

    fun savePlaybackPosition(mediaId: String, positionMs: Long) = database.savePlaybackPosition(mediaId, positionMs)
    fun getPlaybackPosition(mediaId: String): Long = database.getPlaybackPosition(mediaId)

    fun markUnavailable(requestId: String, error: String) {
        val item = database.findById(requestId) ?: return
        if (item.canTransitionTo(DownloadStatus.FAILED)) {
            database.upsert(item.copy(status = DownloadStatus.FAILED, destinationUri = null,
                errorMessage = error, updatedAt = System.currentTimeMillis()))
        }
    }

    private fun reconcileCompletedItem(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.COMPLETED) return item
        val uriString = item.destinationUri
        val valid = if (uriString.isNullOrBlank()) false else runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content") {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length < 0L || descriptor.length == item.bytesDownloaded
                } ?: false
            } else {
                val path = if (uri.scheme == "file") uri.path else uriString
                val file = File(path ?: return@runCatching false)
                file.isFile && file.length() == item.bytesDownloaded
            }
        }.getOrDefault(false)
        if (valid) return item
        return item.copy(
            status = DownloadStatus.FAILED,
            destinationUri = null,
            errorMessage = "Saved file is missing or has changed. Download it again to use the offline library.",
            updatedAt = System.currentTimeMillis()
        ).also(database::upsert)
    }

    private fun reconcileWorkStatus(item: DownloadItem): DownloadItem {
        if (item.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)) return item
        return try {
            val infos = workManager.getWorkInfosForUniqueWork("pw_pm_work_${item.requestId}")
                .get(750, TimeUnit.MILLISECONDS)
            val info = infos.firstOrNull()
            val next = when (info?.state) {
                androidx.work.WorkInfo.State.RUNNING -> DownloadStatus.DOWNLOADING
                androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> DownloadStatus.QUEUED
                androidx.work.WorkInfo.State.FAILED -> DownloadStatus.FAILED
                androidx.work.WorkInfo.State.CANCELLED -> DownloadStatus.PAUSED
                androidx.work.WorkInfo.State.SUCCEEDED -> if (item.status == DownloadStatus.COMPLETED) DownloadStatus.COMPLETED else DownloadStatus.FAILED
                null -> if (System.currentTimeMillis() - item.updatedAt > 30_000L) DownloadStatus.FAILED else return item
            }
            if (next == item.status) return item
            val error = when (next) {
                DownloadStatus.FAILED -> "Download task stopped before a verified file was saved. Retry to continue."
                DownloadStatus.PAUSED -> "Download was stopped by the system. Resume when ready."
                else -> null
            }
            item.copy(status = next, errorMessage = error, updatedAt = System.currentTimeMillis())
                .also(database::upsert)
        } catch (_: Exception) {
            item
        }
    }

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

object DownloadPaths {
    fun partialFile(context: Context, requestId: String): File {
        // Pick up partials created by versions prior to v24, whose UUID names were not hashed.
        val legacy = File(context.cacheDir, "pw_pm_part_${requestId}.part")
        if (requestId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) && legacy.isFile) {
            return legacy
        }
        val safeId = java.security.MessageDigest.getInstance("SHA-256")
            .digest(requestId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "pw_pm_part_$safeId.part")
    }
}
