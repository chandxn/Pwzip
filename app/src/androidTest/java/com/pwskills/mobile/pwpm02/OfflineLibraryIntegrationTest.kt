package com.pwskills.mobile.pwpm02

import android.os.Environment
import android.os.SystemClock
import android.net.Uri
import androidx.test.core.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.common.Player
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Runs on an Android emulator with Wi-Fi and cellular data disabled by the CI step. */
@RunWith(AndroidJUnit4::class)
class OfflineLibraryIntegrationTest {
    @Test
    fun completedDownloadOpensOfflineRestoresProgressAndDeletesOnlyItsOwnedFile() {
        val instrumentation = getInstrumentation()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val fileName = "offline-test-$id.mp4"
        val outputDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "PW Triple/PW-PM/Videos")
        assertTrue(outputDir.exists() || outputDir.mkdirs())
        val media = File(outputDir, fileName)
        context.assets.open("dev_authorized_test.mp4").use { input -> media.outputStream().use { output -> input.copyTo(output) } }
        assertNull(DownloadedMediaVerifier.verify(media, fileName, "video/mp4"))
        val unrelated = File(context.cacheDir, fileName).apply { writeText("preserve me") }

        val database = DownloadDatabase(context)
        val item = DownloadItem(
            requestId = id,
            sourceUrl = "https://test.invalid/authorized-fixture.mp4",
            fileName = fileName,
            mimeType = "video/mp4",
            status = DownloadStatus.COMPLETED,
            bytesDownloaded = media.length(),
            totalBytes = media.length(),
            progress = 100,
            destinationUri = Uri.fromFile(media).toString(),
            errorMessage = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        database.upsert(item)
        database.savePlaybackPosition(id, 1_000L)
        val repository = DownloadRepository.getInstance(context)
        val bridge = NativeDownloadBridge(context, repository)

        val firstPlayer = launchThroughLibrary(instrumentation, bridge, id)
        val duration = getOnMain(instrumentation) { firstPlayer.durationForTesting() }
        assertTrue("Decoded duration should be available", duration > 0L)
        instrumentation.runOnMainSync {
            firstPlayer.seekForTesting((duration / 2).coerceAtLeast(500L))
            firstPlayer.pauseForTesting()
        }
        instrumentation.runOnMainSync { firstPlayer.finish() }
        assertTrue("Playback progress must persist on close", repository.getPlaybackPosition(id) > 0L)

        val reopened = launchThroughLibrary(instrumentation, bridge, id)
        assertTrue("Saved position should be restored", getOnMain(instrumentation) { reopened.playbackPositionForTesting() } > 0L)
        instrumentation.runOnMainSync { reopened.finish() }

        assertTrue("Completed library entry should delete its own file", repository.deleteDownload(id))
        assertFalse(media.exists())
        assertTrue("Deletion must not touch unrelated files", unrelated.exists())
        assertNull(database.findById(id))
        unrelated.delete()
        database.close()
    }

    private fun launchThroughLibrary(instrumentation: android.app.Instrumentation, bridge: NativeDownloadBridge, id: String): OfflinePlayerActivity {
        val monitor = instrumentation.addMonitor(OfflinePlayerActivity::class.java.name, null, false)
        val result = JSONObject(bridge.openDownloadedFile(id))
        assertTrue(result.optString("error"), result.optBoolean("ok"))
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? OfflinePlayerActivity
        assertNotNull("Offline player Activity did not open", activity)
        val offlineActivity = activity!!
        instrumentation.removeMonitor(monitor)
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline && getOnMain(instrumentation) { offlineActivity.playbackStateForTesting() } != Player.STATE_READY) {
            SystemClock.sleep(150)
        }
        assertEquals("Local media should decode without network access", Player.STATE_READY,
            getOnMain(instrumentation) { offlineActivity.playbackStateForTesting() })
        return offlineActivity
    }

    private fun <T> getOnMain(instrumentation: android.app.Instrumentation, block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
