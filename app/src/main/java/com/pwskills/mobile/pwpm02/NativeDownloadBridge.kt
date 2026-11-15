package com.pwskills.mobile.pwpm02

import android.content.Context
import android.os.Environment
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
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
        val docsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return JSONObject()
            .put("ok", true)
            .put("ready", true)
            .put("version", "23.0.0-DualMode")
            .put("packageId", context.packageName)
            .put("sharedExportVideosRoot", "Documents/PW Triple/PW-PM/Videos/")
            .put("sharedExportFilesRoot", "Documents/PW Triple/PW-PM/Files/")
            .put("appOfflineCacheDir", media3Engine.downloadCacheDirectory.absolutePath)
            .put("usableSpaceBytes", docsRoot.usableSpace)
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
        if (!url.startsWith("file:///android_asset/dev_") &&
            !providerAdapter.isUrlAuthorizedForMode(url, DownloadMode.SHARED_EXPORT)
        ) {
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

        val accepted = (item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.DOWNLOADING)
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
                .put("cacheDir", media3Engine.downloadCacheDirectory.absolutePath)
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
        return JSONObject()
            .put("ok", updated)
            .put("requestId", requestId)
            .put("status", if (updated) DownloadStatus.PAUSED.name else "NOT_FOUND")
            .toString()
    }

    @JavascriptInterface
    fun resumeDownload(requestId: String): String {
        val updated = repository.resumeDownload(requestId)
        return JSONObject()
            .put("ok", updated)
            .put("requestId", requestId)
            .put("status", if (updated) DownloadStatus.QUEUED.name else "NOT_FOUND")
            .toString()
    }

    @JavascriptInterface
    fun cancelDownload(requestId: String): String {
        val updated = repository.cancelDownload(requestId)
        media3Engine.removeAppOfflineDownload(context, requestId)
        return JSONObject()
            .put("ok", updated)
            .put("requestId", requestId)
            .put("status", if (updated) "CANCELLED" else "NOT_FOUND")
            .toString()
    }

    @JavascriptInterface
    fun retryDownload(requestId: String): String {
        val updated = repository.retryDownload(requestId)
        return JSONObject()
            .put("ok", updated)
            .put("requestId", requestId)
            .put("status", if (updated) DownloadStatus.QUEUED.name else "NOT_FOUND")
            .toString()
    }

    @JavascriptInterface
    fun getDownloadsStatusJson(): String {
        val items = repository.getAllDownloads()
        val arr = JSONArray()
        for (item in items) {
            arr.put(item.toJson())
        }
        return JSONObject()
            .put("ok", true)
            .put("count", items.size)
            .put("downloads", arr)
            .toString()
    }

    @JavascriptInterface
    fun getStorageDirectoryInfo(): String {
        val docsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val pwRoot = File(docsRoot, "PW Triple/PW-PM")
        val videosDir = File(pwRoot, "Videos")
        val filesDir = File(pwRoot, "Files")

        return JSONObject()
            .put("ok", true)
            .put("root", "Documents/PW Triple/PW-PM/Videos/")
            .put("engine", "AndroidX Media3 + Android WorkManager")
            .put("videosFolder", "Documents/PW Triple/PW-PM/Videos/")
            .put("filesFolder", "Documents/PW Triple/PW-PM/Files/")
            .put("videosAbsolutePath", videosDir.absolutePath)
            .put("filesAbsolutePath", filesDir.absolutePath)
            .put("appOfflineCachePath", media3Engine.downloadCacheDirectory.absolutePath)
            .put("freeSpaceBytes", docsRoot.usableSpace)
            .toString()
    }

    fun shutdown() {
        scope.cancel()
    }
}
