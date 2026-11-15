@file:OptIn(UnstableApi::class)

package com.pwskills.mobile.pwpm02

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

/**
 * Fix 2: PwPmMedia3DownloadService.kt
 * Verified against AndroidX Media3 1.3.1 API signatures:
 * - Uses `androidx.media3.exoplayer.offline.DownloadNotificationHelper` (correct Media3 1.3.1 package).
 * - Annotated with `@file:OptIn(UnstableApi::class)` required by Media3 1.3.1.
 * - Returns the application-singleton `PwDownloadEngine.getInstance(this).downloadManager`.
 */
class PwPmMedia3DownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    NOTIFICATION_CHANNEL_ID,
    R.string.download_channel_name,
    0
) {
    private lateinit var notificationHelper: DownloadNotificationHelper

    override fun onCreate() {
        super.onCreate()
        notificationHelper = DownloadNotificationHelper(this, NOTIFICATION_CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager {
        return PwDownloadEngine.getInstance(this).downloadManager
    }

    override fun getScheduler(): Scheduler? {
        return PlatformScheduler(this, JOB_ID)
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        return notificationHelper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            /* contentIntent= */ null,
            /* message= */ "PW-PM Offline Media Download",
            downloads,
            notMetRequirements
        )
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "pw_pm_media3_download_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 2101
        private const val JOB_ID = 2102
    }
}
