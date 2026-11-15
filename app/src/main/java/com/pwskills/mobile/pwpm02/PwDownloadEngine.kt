@file:OptIn(UnstableApi::class)

package com.pwskills.mobile.pwpm02

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Standalone File: PwDownloadEngine.kt
 * Classification: Newly Written (verified against official AndroidX Media3 1.3.1 API).
 *
 * Implements Path B (APP_OFFLINE) using:
 * - StandaloneDatabaseProvider(appContext)
 * - SimpleCache(downloadCacheDirectory, NoOpCacheEvictor(), databaseProvider)
 * - DownloadManager(appContext, databaseProvider, downloadCache, upstreamFactory, executor)
 * - DownloadRequest.Builder(contentId, Uri.parse(httpsUrl)).setMimeType(mimeType).setKeySetId(keySetId)
 * - DownloadService.sendAddDownload / sendRemoveDownload / sendPauseDownloads / sendResumeDownloads
 */
class PwDownloadEngine private constructor(context: Context) {

    private val appContext = context.applicationContext

    val databaseProvider = StandaloneDatabaseProvider(appContext)

    val downloadCacheDirectory: File =
        File(appContext.filesDir, "pw_offline_downloads").apply {
            check(mkdirs() || isDirectory)
        }

    val downloadCache: Cache =
        SimpleCache(
            downloadCacheDirectory,
            NoOpCacheEvictor(),
            databaseProvider
        )

    val upstreamFactory: DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(Util.getUserAgent(appContext, "PW-PM"))
            .setAllowCrossProtocolRedirects(true)

    private val executor: ExecutorService = Executors.newFixedThreadPool(3)

    val downloadManager: DownloadManager = DownloadManager(
        appContext,
        databaseProvider,
        downloadCache,
        upstreamFactory,
        executor
    ).apply {
        maxParallelDownloads = 3
        minRetryCount = 5
    }

    /**
     * Read-only CacheDataSource.Factory for offline playback from SimpleCache without network calls.
     */
    val offlineCacheDataSourceFactory: CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(0)

    /**
     * Creates a DownloadRequest for HLS (.m3u8), DASH (.mpd), or progressive media,
     * attaching a persistent Widevine `keySetId` when authorized by the provider.
     * Custom cache keys are omitted for HLS/DASH per Media3 documentation.
     */
    fun buildDownloadRequest(
        contentId: String,
        httpsUrl: String,
        mimeType: String,
        keySetId: ByteArray? = null
    ): DownloadRequest {
        require(httpsUrl.startsWith("https://", ignoreCase = true)) {
            "Media3 DownloadRequest requires an HTTPS URL."
        }
        val builder = DownloadRequest.Builder(contentId, Uri.parse(httpsUrl))
            .setMimeType(mimeType)

        if (keySetId != null && keySetId.isNotEmpty()) {
            builder.setKeySetId(keySetId)
        }
        return builder.build()
    }

    fun enqueueAppOfflineDownload(
        context: Context,
        contentId: String,
        httpsUrl: String,
        mimeType: String? = null,
        keySetId: ByteArray? = null
    ): DownloadRequest {
        val resolvedMime = mimeType ?: inferMedia3MimeType(httpsUrl)
        val request = buildDownloadRequest(contentId, httpsUrl, resolvedMime, keySetId)
        DownloadService.sendAddDownload(
            context.applicationContext,
            PwPmMedia3DownloadService::class.java,
            request,
            /* foreground= */ false
        )
        return request
    }

    fun removeAppOfflineDownload(context: Context, contentId: String) {
        DownloadService.sendRemoveDownload(
            context.applicationContext,
            PwPmMedia3DownloadService::class.java,
            contentId,
            /* foreground= */ false
        )
    }

    fun pauseAllAppOfflineDownloads(context: Context) {
        DownloadService.sendPauseDownloads(
            context.applicationContext,
            PwPmMedia3DownloadService::class.java,
            /* foreground= */ false
        )
    }

    fun resumeAllAppOfflineDownloads(context: Context) {
        DownloadService.sendResumeDownloads(
            context.applicationContext,
            PwPmMedia3DownloadService::class.java,
            /* foreground= */ false
        )
    }

    fun inferMedia3MimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.contains(".webm") -> MimeTypes.VIDEO_WEBM
            else -> MimeTypes.VIDEO_MP4
        }
    }

    fun release() {
        downloadManager.release()
        downloadCache.release()
        executor.shutdown()
    }

    companion object {
        @Volatile
        private var INSTANCE: PwDownloadEngine? = null

        fun getInstance(context: Context): PwDownloadEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PwDownloadEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
