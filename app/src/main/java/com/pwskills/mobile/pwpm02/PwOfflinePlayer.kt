@file:OptIn(UnstableApi::class)

package com.pwskills.mobile.pwpm02

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Requirement 3: Official In-App Offline Playback (`PwOfflinePlayer.kt`)
 *
 * Uses AndroidX Media3 `ExoPlayer` configured with `PwDownloadEngine.offlineCacheDataSourceFactory`
 * (`CacheDataSource.Factory` backed by `SimpleCache` with `NoOpCacheEvictor` and `upstreamFactory = null`)
 * so cached HLS, DASH, and Widevine-licensed streams play strictly from offline cache without network calls.
 * When a persistent Widevine `keySetId` is stored for the lesson, attaches it via `DrmConfiguration.Builder(C.WIDEVINE_UUID).setKeySetId(keySetId)`.
 */
class PwOfflinePlayer(private val context: Context) {

    private val downloadEngine = PwDownloadEngine.getInstance(context)
    private val licenseStorage = PwLicenseStorage(context)
    private var exoPlayer: ExoPlayer? = null

    fun createOrGetPlayer(): ExoPlayer {
        val existing = exoPlayer
        if (existing != null) return existing

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(downloadEngine.offlineCacheDataSourceFactory)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer = it }
    }

    fun prepareOfflinePlayback(
        lessonId: String,
        cachedUri: String,
        mimeType: String,
        licenseServerUrl: String? = null
    ): ExoPlayer {
        val player = createOrGetPlayer()
        val mediaItemBuilder = MediaItem.Builder()
            .setMediaId(lessonId)
            .setUri(Uri.parse(cachedUri))
            .setMimeType(mimeType)

        val storedKeySetId = licenseStorage.getKeySetId(lessonId)
        if (storedKeySetId != null && storedKeySetId.isNotEmpty() && !licenseServerUrl.isNullOrBlank()) {
            val drmConfig = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri(licenseServerUrl)
                .setKeySetId(storedKeySetId)
                .build()
            mediaItemBuilder.setDrmConfiguration(drmConfig)
        }

        player.setMediaItem(mediaItemBuilder.build())
        player.prepare()
        return player
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
