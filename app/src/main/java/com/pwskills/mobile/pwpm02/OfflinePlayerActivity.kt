package com.pwskills.mobile.pwpm02

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import java.io.File

/** Native offline library player. It only accepts an ID resolved by NativeDownloadBridge. */
class OfflinePlayerActivity : Activity() {
    private lateinit var player: PwOfflinePlayer
    private lateinit var status: TextView
    private lateinit var progressText: TextView
    private lateinit var itemId: String
    private var isPrepared = false
    private var lastSavedPositionMs = -1L
    private val handler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            if (!isPrepared) return
            val exo = player.createOrGetPlayer()
            val duration = exo.duration.coerceAtLeast(0L)
            val position = exo.currentPosition.coerceAtLeast(0L)
            progressText.text = "${time(position)} / ${time(duration)}"
            if (exo.playbackState == Player.STATE_ENDED) savePosition(0L, true) else savePosition(position)
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = intent.getStringExtra(EXTRA_MEDIA_ID).orEmpty()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (itemId.isBlank()) {
            finishWithError("This download could not be opened.")
            return
        }
        val source = intent.getStringExtra(EXTRA_URI).orEmpty()
        if (source.isBlank()) {
            finishWithError("The saved file is no longer available.")
            return
        }
        val uri = Uri.parse(source).let { if (it.scheme == null) Uri.fromFile(File(source)) else it }
        val mime = intent.getStringExtra(EXTRA_MIME).orEmpty()
        val cacheOnly = intent.getBooleanExtra(EXTRA_CACHE_ONLY, false)
        val lessonId = intent.getStringExtra(EXTRA_LESSON_ID).orEmpty()
        val license = PwLicenseStorage(this).getLicenseMetadata(lessonId)
        if (cacheOnly && intent.getBooleanExtra(EXTRA_REQUIRES_LICENSE, false) &&
            (license == null || license.isExpired() || license.licenseServerUrl.isBlank())
        ) {
            finishWithError("The provider's offline license is missing or expired. Connect to the provider to renew it.")
            return
        }
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        val supported = if (cacheOnly) mime in setOf("application/x-mpegURL", "application/vnd.apple.mpegurl", "application/dash+xml") ||
            mime.startsWith("video/") || mime.startsWith("audio/")
        else DownloadedMediaVerifier.isSupportedVideo(fileName, mime)
        if (!supported) {
            finishWithError("This media type is not supported by the in-app offline player.")
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(7, 11, 20))
            setPadding(12, 14, 12, 12)
        }
        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Offline playback"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 12)
        }
        status = TextView(this).apply {
            text = "Checking saved media…"
            setTextColor(android.graphics.Color.LTGRAY)
            textSize = 13f
            setPadding(4, 8, 4, 8)
        }
        progressText = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextColor(android.graphics.Color.LTGRAY)
            textSize = 12f
            setPadding(4, 6, 4, 12)
        }
        val playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val close = TextView(this).apply {
            text = "Close player"
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(12, 14, 12, 14)
            setOnClickListener { finish() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(progressText)
        root.addView(playerView)
        root.addView(close)
        setContentView(root)

        player = PwOfflinePlayer(this)
        val exo = player.prepareOfflinePlayback(
            lessonId = if (lessonId.isBlank()) itemId else lessonId,
            cachedUri = uri.toString(),
            mimeType = mime,
            licenseServerUrl = license?.licenseServerUrl,
            offlineCacheOnly = cacheOnly
        )
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                status.text = when (playbackState) {
                    Player.STATE_BUFFERING -> "Preparing saved media…"
                    Player.STATE_READY -> "Playing saved media offline"
                    Player.STATE_ENDED -> "Playback finished"
                    else -> "Ready to play"
                }
                isPrepared = playbackState == Player.STATE_READY
                if (playbackState == Player.STATE_ENDED) {
                    savePosition(0L, true)
                    handler.removeCallbacks(progressTick)
                }
                if (isPrepared) handler.removeCallbacks(progressTick).also { handler.post(progressTick) }
            }

            override fun onPlayerError(error: PlaybackException) {
                status.text = "Playback error: ${error.errorCodeName}. This file or offline license may be unavailable."
                isPrepared = false
            }
        })
        exo.seekTo(DownloadRepository.getInstance(this).getPlaybackPosition(itemId))
        exo.playWhenReady = true
    }

    override fun onPause() {
        if (::player.isInitialized && isPrepared) savePosition(player.createOrGetPlayer().currentPosition, true)
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(progressTick)
        if (::player.isInitialized) {
            if (isPrepared) savePosition(player.createOrGetPlayer().currentPosition, true)
            player.createOrGetPlayer().pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTick)
        if (::player.isInitialized) player.release()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun savePosition(positionMs: Long, force: Boolean = false) {
        val normalized = positionMs.coerceAtLeast(0L)
        if (!force && lastSavedPositionMs >= 0L && kotlin.math.abs(normalized - lastSavedPositionMs) < 1500L) return
        lastSavedPositionMs = normalized
        DownloadRepository.getInstance(this).savePlaybackPosition(itemId, normalized)
    }

    private fun finishWithError(message: String) {
        setResult(RESULT_CANCELED, intent.putExtra(EXTRA_ERROR, message))
        finish()
    }

    fun playbackStateForTesting(): Int = if (::player.isInitialized) player.createOrGetPlayer().playbackState else Player.STATE_IDLE
    fun playbackPositionForTesting(): Long = if (::player.isInitialized) player.createOrGetPlayer().currentPosition else 0L
    fun durationForTesting(): Long = if (::player.isInitialized) player.createOrGetPlayer().duration.coerceAtLeast(0L) else 0L
    fun seekForTesting(positionMs: Long) { if (::player.isInitialized) player.createOrGetPlayer().seekTo(positionMs) }
    fun pauseForTesting() { if (::player.isInitialized) player.createOrGetPlayer().pause() }

    private fun time(ms: Long): String {
        val seconds = ms / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    companion object {
        const val EXTRA_MEDIA_ID = "media_id"
        const val EXTRA_URI = "media_uri"
        const val EXTRA_MIME = "media_mime"
        const val EXTRA_TITLE = "media_title"
        const val EXTRA_FILE_NAME = "media_file_name"
        const val EXTRA_CACHE_ONLY = "media_cache_only"
        const val EXTRA_LESSON_ID = "media_lesson_id"
        const val EXTRA_REQUIRES_LICENSE = "media_requires_license"
        const val EXTRA_ERROR = "media_error"
    }
}
