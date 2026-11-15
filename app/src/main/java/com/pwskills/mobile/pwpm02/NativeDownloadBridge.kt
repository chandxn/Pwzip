package com.pwskills.mobile.pwpm02

import android.content.Context
import android.os.Environment
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MimeTypes
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.exoplayer.offline.Download
import java.io.File
import java.util.UUID

/**
 * Standalone File: NativeDownloadBridge.kt
 * Classification: Recovered & Upgraded (integrates Path A `DownloadRepository` + Path B `PwDownloadEngine`
 * + `PwPortalProviderAdapter` + `PwLicenseStorage`).
 */
class NativeDownloadBridge(
    private val context: Context,
    private val repository: DownloadRepository = DownloadRepository.getInstance(context),
    private val providerAdapter: PwPortalProviderAdapter = PwPortalProviderAdapter(context, repository),
    private val media3Engine: PwDownloadEngine = PwDownloadEngine.getInstance(context),
    private val licenseStorage: PwLicenseStorage = PwLicenseStorage(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JavascriptInterface
    fun isBridgeReady(): String {
        return "PW_PM_V16_DOWNLOAD_ENGINE_READY"
    }

    @JavascriptInterface
    fun checkBridgeHealthJson(): String {
        return JSONObject()
            .put("ok", true)
            .put("ready", true)
            .put("version", "23.0.0-DualMode")
            .put("packageId", context.packageName)
            .put("sharedExportVideosRoot", "Documents/PW Triple/PW-PM/Videos/")
            .put("sharedExportFilesRoot", "Documents/PW Triple/PW-PM/Files/")
            .put("usableSpaceBytes", context.filesDir.usableSpace)
            .toString()
    }

    @JavascriptInterface
    fun getBridgeVersion(): String = "PW-PM-NativeDownloadBridge/23.0.0"

    /**
     * Path A (SHARED_EXPORT): 3-argument overload for authorized direct MP4 and PDF files.
     */
    @JavascriptInterface
    fun enqueueDownload(
        url: String,
        fileName: String,
        mimeType: String
    ): String {
        val requestId = UUID.randomUUID().toString()
        // Phase 5: Validate provider authorization independently of HTTPS validation (prevent arbitrary URL downloads)
        if (!providerAdapter.isUrlAuthorizedForMode(url, DownloadMode.SHARED_EXPORT)) {
            return JSONObject()
                .put("ok", false)
                .put("success", false)
                .put("requestId", requestId)
                .put("status", ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE.name)
                .put("error", "PROVIDER_OFFLINE_ACCESS_UNAVAILABLE: URL is not authorized by provider entitlement for SHARED_EXPORT.")
                .toString()
        }
        val validation = repository.validateRequestSynchronously(url, fileName, mimeType)
        if (!validation.allowed) {
            return JSONObject()
                .put("ok", false)
                .put("success", false)
                .put("requestId", requestId)
                .put("status", validation.status)
                .put("error", validation.reason)
                .toString()
        }

        // Fix 4: Synchronously persist and schedule in WorkManager BEFORE reporting QUEUED
        val item = try {
            repository.enqueueAuthorizedDirectDownload(
                requestId = requestId,
                url = url,
                fileName = fileName,
                mimeType = mimeType
            )
        } catch (e: Exception) {
            return JSONObject()
                .put("ok", false)
                .put("success", false)
                .put("requestId", requestId)
                .put("status", DownloadStatus.FAILED.name)
                .put("error", "Native repository enqueue failed: ${e.message ?: e.javaClass.simpleName}")
                .toString()
        }

        val accepted = item.status in setOf(
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.COMPLETED
        )
        return JSONObject()
            .put("ok", accepted)
            .put("success", accepted)
            .put("id", item.requestId)
            .put("requestId", item.requestId)
            .put("downloadMode", DownloadMode.SHARED_EXPORT.name)
            .put("status", item.status.name)
            .put("fileName", item.fileName)
            .put("mimeType", item.mimeType)
            .put("error", item.errorMessage ?: JSONObject.NULL)
            .put("targetFolder", if (mimeType.contains("pdf", ignoreCase = true) || fileName.endsWith(".pdf", true))
                "Documents/PW Triple/PW-PM/Files/" else "Documents/PW Triple/PW-PM/Videos/")
            .toString()
    }

    /**
     * Path A (SHARED_EXPORT): 2-argument overload matching original classes.dex signature.
     */
    @JavascriptInterface
    fun enqueueDownload(url: String, fileName: String): String {
        val inferredMime = when {
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            fileName.endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            fileName.endsWith(".mpd", ignoreCase = true) -> "application/dash+xml"
            else -> "video/mp4"
        }
        return enqueueDownload(url, fileName, inferredMime)
    }

    @JavascriptInterface
    fun downloadApprovedDirectFile(url: String, fileName: String): String {
        return enqueueDownload(url, fileName)
    }

    /**
     * Path B (APP_OFFLINE): Enqueues an authorized non-DRM HLS/DASH stream or provider-authorized
     * Widevine stream into AndroidX Media3 `PwDownloadEngine` (`SimpleCache` + `PwPmMedia3DownloadService`).
     */
    @JavascriptInterface
    fun enqueueAppOfflineDownload(
        lessonId: String,
        httpsUrl: String,
        mimeType: String
    ): String {
        if (!httpsUrl.startsWith("https://", ignoreCase = true)) {
            return JSONObject()
                .put("ok", false)
                .put("status", DownloadStatus.UNSUPPORTED.name)
                .put("error", "Strict HTTPS URL required")
                .toString()
        }
        if (!providerAdapter.isUrlAuthorizedForMode(httpsUrl, DownloadMode.APP_OFFLINE)) {
            return JSONObject()
                .put("ok", false)
                .put("status", ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE.name)
                .put("error", "PROVIDER_OFFLINE_ACCESS_UNAVAILABLE: URL is not authorized by provider entitlement for APP_OFFLINE.")
                .toString()
        }
        return try {
            val storedKeySetId = licenseStorage.getKeySetId(lessonId)
            val req = media3Engine.enqueueAppOfflineDownload(
                context = context,
                contentId = lessonId,
                httpsUrl = httpsUrl,
                mimeType = mimeType,
                keySetId = storedKeySetId
            )
            JSONObject()
                .put("ok", true)
                .put("requestId", req.id)
                .put("downloadMode", DownloadMode.APP_OFFLINE.name)
                .put("status", DownloadStatus.QUEUED.name)
                .put("hasStoredWidevineLicense", storedKeySetId != null)
                .toString()
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("status", DownloadStatus.FAILED.name)
                .put("error", e.message ?: e.javaClass.simpleName)
                .toString()
        }
    }


    @JavascriptInterface
    fun renewOfflineLicenseJson(lessonId: String, licenseUrl: String): String {
        if (!licenseUrl.startsWith("https://", ignoreCase = true)) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Strict HTTPS license server URL required")
                .toString()
        }
        return try {
            val licenseManager = PwOfflineLicenseManager(context, licenseStorage)
            licenseManager.renewPersistentLicense(lessonId, licenseUrl)
            val meta = licenseStorage.getLicenseMetadata(lessonId)
            JSONObject()
                .put("ok", true)
                .put("lessonId", lessonId)
                .put("keySetId", meta?.toJson()?.optString("keySetIdBase64", "") ?: "")
                .put("expiresAtMs", meta?.expiresAtMs ?: 0L)
                .toString()
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName)
                .toString()
        }
    }

    @JavascriptInterface
    fun requestProviderOfflineAccessJson(lessonId: String, quality: String): String {
        val grant = providerAdapter.requestOfflineAccess(lessonId, quality)
        return grant.toJson().toString()
    }

    @JavascriptInterface
    fun releaseProviderOfflineAccessJson(downloadId: String): String {
        val released = providerAdapter.releaseOfflineAccess(downloadId)
        media3Engine.removeAppOfflineDownload(context, downloadId)
        return JSONObject()
            .put("ok", released)
            .put("downloadId", downloadId)
            .toString()
    }

    @JavascriptInterface
    fun pauseDownload(requestId: String): String {
        val updated = repository.pauseDownload(requestId)
        val offline = findMedia3Download(requestId)
        if (!updated && offline != null) media3Engine.downloadManager.setStopReason(requestId, 1)
        return JSONObject()
            .put("ok", updated || offline != null)
            .put("requestId", requestId)
            .put("status", if (updated || offline != null) DownloadStatus.PAUSED.name else "NOT_FOUND")
            .toString()
    }

    @JavascriptInterface
    fun resumeDownload(requestId: String): String {
        val updated = repository.resumeDownload(requestId)
        val offline = findMedia3Download(requestId)
        if (!updated && offline != null) media3Engine.downloadManager.setStopReason(requestId, Download.STOP_REASON_NONE)
        return JSONObject()
            .put("ok", updated || offline != null)
            .put("requestId", requestId)
            .put("status", if (updated || offline != null) DownloadStatus.QUEUED.name else "NOT_FOUND")
            .toString()
    }

    @JavascriptInterface
    fun cancelDownload(requestId: String): String {
        val updated = repository.cancelDownload(requestId)
        val offline = findMedia3Download(requestId)
        if (!updated && offline != null) media3Engine.downloadManager.removeDownload(requestId)
        media3Engine.removeAppOfflineDownload(context, requestId)
        return JSONObject()
            .put("ok", updated || offline != null)
            .put("requestId", requestId)
            .put("status", if (updated || offline != null) "CANCELLED" else "NOT_FOUND")
            .toString()
    }

    @JavascriptInterface
    fun retryDownload(requestId: String): String {
        val updated = repository.retryDownload(requestId)
        val offline = findMedia3Download(requestId)
        if (!updated && offline != null) {
            media3Engine.downloadManager.setStopReason(requestId, Download.STOP_REASON_NONE)
            media3Engine.downloadManager.addDownload(offline.request)
        }
        return JSONObject()
            .put("ok", updated || offline != null)
            .put("requestId", requestId)
            .put("status", if (updated || offline != null) DownloadStatus.QUEUED.name else "NOT_FOUND")
            .toString()
    }

    @JavascriptInterface
    fun openDownloadedFile(requestId: String): String {
        val item = repository.getDownload(requestId)
        if (item != null) {
            if (item.status != DownloadStatus.COMPLETED || item.destinationUri.isNullOrBlank()) {
                return JSONObject().put("ok", false).put("error", item.errorMessage ?: "File is not fully downloaded and verified").toString()
            }
            val uri = Uri.parse(item.destinationUri).let { if (it.scheme == null) Uri.fromFile(File(item.destinationUri)) else it }
            val verification = DownloadedMediaVerifier.verify(context, uri, item.fileName, item.mimeType)
            if (verification != null) {
                repository.markUnavailable(requestId, verification)
                return JSONObject().put("ok", false).put("error", verification).toString()
            }
            if (item.mimeType.contains("pdf", true) || item.fileName.endsWith(".pdf", true)) {
                return openPdf(uri, item.mimeType)
            }
            if (!DownloadedMediaVerifier.isSupportedVideo(item.fileName, item.mimeType)) {
                return JSONObject().put("ok", false).put("error", "Only verified MP4, M4V and WebM files can be played in the app.").toString()
            }
            return launchOfflinePlayer(requestId, uri, item.mimeType, item.fileName, cacheOnly = false, lessonId = requestId, requiresLicense = false)
        }

        val media3 = findMedia3Download(requestId)
            ?: return JSONObject().put("ok", false).put("error", "Download not found").toString()
        if (media3.state != Download.STATE_COMPLETED) {
            return JSONObject().put("ok", false).put("error", "Provider download is not complete yet.").toString()
        }
        val keySet = media3.request.keySetId
        val license = licenseStorage.getLicenseMetadata(requestId)
        if (keySet != null && (license == null || license.keySetId.isEmpty() || license.isExpired() || license.licenseServerUrl.isBlank())) {
            return JSONObject().put("ok", false).put("error", "The provider's offline license is missing or expired.").toString()
        }
        val mime = media3.request.mimeType ?: media3Engine.inferMedia3MimeType(media3.request.uri.toString())
        val supportedOffline = mime in setOf(MimeTypes.APPLICATION_M3U8, MimeTypes.APPLICATION_MPD) ||
            mime.startsWith("video/") || mime.startsWith("audio/")
        if (!supportedOffline) return JSONObject().put("ok", false).put("error", "This provider media format is not supported offline.").toString()
        launchOfflinePlayer(requestId, media3.request.uri, mime, requestId, cacheOnly = true, lessonId = requestId, requiresLicense = keySet != null)
    }

    private fun launchOfflinePlayer(
        id: String, uri: Uri, mime: String, title: String, cacheOnly: Boolean, lessonId: String, requiresLicense: Boolean
    ): String = try {
        context.startActivity(Intent(context, OfflinePlayerActivity::class.java).apply {
            putExtra(OfflinePlayerActivity.EXTRA_MEDIA_ID, id)
            putExtra(OfflinePlayerActivity.EXTRA_URI, uri.toString())
            putExtra(OfflinePlayerActivity.EXTRA_MIME, mime)
            putExtra(OfflinePlayerActivity.EXTRA_TITLE, title)
            putExtra(OfflinePlayerActivity.EXTRA_FILE_NAME, title)
            putExtra(OfflinePlayerActivity.EXTRA_CACHE_ONLY, cacheOnly)
            putExtra(OfflinePlayerActivity.EXTRA_LESSON_ID, lessonId)
            putExtra(OfflinePlayerActivity.EXTRA_REQUIRES_LICENSE, requiresLicense)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        JSONObject().put("ok", true).toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", e.message ?: "Could not open offline player").toString()
    }

    private fun openPdf(uri: Uri, mime: String): String = try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        JSONObject().put("ok", true).toString()
    } catch (_: Exception) {
        JSONObject().put("ok", false).put("error", "No installed PDF viewer can open this saved file.").toString()
    }

    @JavascriptInterface
    fun getDownloadsStatusJson(): String {
        val items = repository.getAllDownloads()
        val arr = JSONArray()
        for (item in items) {
            arr.put(item.toJson())
        }
        for (download in getAllMedia3Downloads()) {
            val state = when (download.state) {
                Download.STATE_QUEUED, Download.STATE_RESTARTING -> "QUEUED"
                Download.STATE_DOWNLOADING -> "DOWNLOADING"
                Download.STATE_STOPPED -> "PAUSED"
                Download.STATE_COMPLETED -> "COMPLETED"
                Download.STATE_FAILED -> "FAILED"
                Download.STATE_REMOVING -> "REMOVING"
                else -> "QUEUED"
            }
            val progress = download.percentDownloaded.takeIf { it >= 0f }?.toInt() ?: 0
            arr.put(JSONObject()
                .put("requestId", download.request.id)
                .put("fileName", download.request.id)
                .put("mimeType", download.request.mimeType ?: "application/octet-stream")
                .put("status", state)
                .put("bytesDownloaded", download.bytesDownloaded)
                .put("totalBytes", download.contentLength)
                .put("progress", progress)
                .put("hasPlayableFile", download.state == Download.STATE_COMPLETED)
                .put("errorMessage", if (download.failureReason != Download.FAILURE_REASON_NONE) "Provider-authorized offline download failed (code ${download.failureReason})." else JSONObject.NULL)
                .put("downloadMode", DownloadMode.APP_OFFLINE.name))
        }
        return JSONObject()
            .put("ok", true)
            .put("count", arr.length())
            .put("downloads", arr)
            .toString()
    }

    @JavascriptInterface
    fun getStorageDirectoryInfo(): String {
        val savedBytes = repository.getAllDownloads().filter { it.status == DownloadStatus.COMPLETED }.sumOf { it.bytesDownloaded }
        val cacheBytes = directorySize(media3Engine.downloadCacheDirectory)

        return JSONObject()
            .put("ok", true)
            .put("root", "Documents/PW Triple/PW-PM/")
            .put("engine", "AndroidX Media3 + Android WorkManager")
            .put("savedFilesBytes", savedBytes)
            .put("appOfflineCacheBytes", cacheBytes)
            .put("usedBytes", savedBytes + cacheBytes)
            .put("freeSpaceBytes", context.filesDir.usableSpace)
            .toString()
    }

    private fun directorySize(file: File): Long = if (!file.exists()) 0L else if (file.isFile) file.length()
        else file.listFiles()?.sumOf(::directorySize) ?: 0L

    private fun findMedia3Download(requestId: String): Download? = runCatching {
        media3Engine.downloadManager.downloadIndex.getDownload(requestId)
    }.getOrNull()

    private fun getAllMedia3Downloads(): List<Download> = runCatching {
        media3Engine.downloadManager.downloadIndex.getDownloads(intArrayOf()).use { cursor ->
            val downloads = mutableListOf<Download>()
            while (cursor.moveToNext()) downloads.add(cursor.download)
            downloads
        }
    }.getOrDefault(emptyList())

    fun shutdown() {
        scope.cancel()
    }
}
