package com.pwskills.mobile.pwpm02

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException

/**
 * File 3: DownloadWorker.kt
 * Classification: Reconstructed & Completed (preserves DEX `isSafeFileName` + full streaming worker).
 *
 * Streams HTTP bytes incrementally into a temporary `.part` file (with HTTP Range resume support),
 * validates file size, MIME header, container magic bytes (`ftyp` for MP4, `%PDF-` for PDF),
 * and MediaMetadataRetriever duration/tracks before exporting to:
 * - Documents/PW Triple/PW-PM/Videos/ (for validated video files)
 * - Documents/PW Triple/PW-PM/Files/ (for validated PDF/attachment files)
 */
class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val database = DownloadDatabase(appContext)
    private val exporter = PwPhoneStorageExporter(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return@withContext Result.failure()
        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: return@withContext Result.failure()
        val rawFileName = inputData.getString(KEY_FILE_NAME) ?: "lecture.mp4"
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "video/mp4"

        if (!isSafeFileName(rawFileName)) {
            markFailed(requestId, "Rejected unsafe file name: $rawFileName")
            return@withContext Result.failure()
        }

        val tempFile = File(applicationContext.cacheDir, "pw_pm_part_${requestId}.part")
        var connection: HttpURLConnection? = null

        try {
            setForeground(downloadForegroundInfo(requestId, rawFileName))
            val existingItem = database.findById(requestId)
            val resumeBytes = if (tempFile.exists()) tempFile.length() else 0L

            updateState(
                requestId = requestId,
                status = DownloadStatus.DOWNLOADING,
                bytesDownloaded = resumeBytes,
                totalBytes = existingItem?.totalBytes ?: 0L,
                errorMessage = null
            )

            if (!sourceUrl.startsWith("https://", ignoreCase = true)) {
                markUnsupported(requestId, "Only provider-authorized HTTPS files can be downloaded.")
                return@withContext Result.failure()
            } else {
                val url = URL(sourceUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PW-PM-Android/21.0")
                    if (resumeBytes > 0L) {
                        setRequestProperty("Range", "bytes=$resumeBytes-")
                    }
                }
                connection.connect()

                val code = connection.responseCode
                val isPartial = (code == HttpURLConnection.HTTP_PARTIAL)
                if (code != HttpURLConnection.HTTP_OK && !isPartial) {
                    if (isRetryableHttpStatus(code)) {
                        updateState(requestId, DownloadStatus.QUEUED, tempFile.length(), database.findById(requestId)?.totalBytes ?: -1L,
                            "Server temporarily unavailable (HTTP $code); retrying automatically.")
                        return@withContext Result.retry()
                    }
                    markFailed(requestId, "HTTP error $code from server")
                    if (tempFile.exists()) tempFile.delete()
                    return@withContext Result.failure()
                }
                if (isPartial) {
                    val contentRange = connection.getHeaderField("Content-Range") ?: ""
                    if (!contentRange.startsWith("bytes $resumeBytes-", ignoreCase = true)) {
                        markFailed(requestId, "Server returned an invalid byte range; resume was stopped to protect file integrity.")
                        tempFile.delete()
                        return@withContext Result.failure()
                    }
                }

                val contentType = (connection.contentType ?: "").lowercase()
                if (contentType.contains("mpegurl") || contentType.contains("dash+xml") || contentType.contains("text/html")) {
                    markUnsupported(
                        requestId,
                        "Server returned '$contentType' instead of a direct binary file. Streaming manifests/HTML cannot be saved as fake MP4 files."
                    )
                    if (tempFile.exists()) tempFile.delete()
                    return@withContext Result.failure()
                }

                val contentLength = connection.contentLengthLong
                val expectedTotal = if (contentLength > 0) {
                    if (isPartial) resumeBytes + contentLength else contentLength
                } else -1L

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile, isPartial).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = if (isPartial) resumeBytes else 0L
                        var lastPersistTime = 0L

                        while (true) {
                            if (isStopped) {
                                return@withContext Result.retry()
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastPersistTime >= 350L) {
                                lastPersistTime = now
                                updateState(
                                    requestId = requestId,
                                    status = DownloadStatus.DOWNLOADING,
                                    bytesDownloaded = downloaded,
                                    totalBytes = expectedTotal,
                                    errorMessage = null
                                )
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Transition to VERIFYING before publishing to phone storage
            val finalSize = tempFile.length()
            updateState(
                requestId = requestId,
                status = DownloadStatus.VERIFYING,
                bytesDownloaded = finalSize,
                totalBytes = finalSize,
                errorMessage = null
            )

            val verificationError = verifyDownloadedFile(tempFile, rawFileName, mimeType)
            if (verificationError != null) {
                tempFile.delete()
                markFailed(requestId, verificationError)
                return@withContext Result.failure()
            }

            // Publish verified file to Documents/PW Triple/PW-PM/Videos/ or Files/
            val isPdfOrAttachment = mimeType.contains("pdf", ignoreCase = true) ||
                    rawFileName.endsWith(".pdf", ignoreCase = true)
            val exportResult = exporter.exportVerifiedFileToPhoneStorage(
                sourceFile = tempFile,
                fileName = rawFileName,
                mimeType = mimeType,
                subFolder = if (isPdfOrAttachment) "Files" else "Videos"
            )

            tempFile.delete()

            if (!exportResult.success || exportResult.destinationUri == null) {
                markFailed(requestId, exportResult.errorMessage ?: "MediaStore export failed")
                return@withContext Result.failure()
            }

            val existing = database.findById(requestId)
            if (existing != null) {
                database.upsert(
                    existing.copy(
                        status = DownloadStatus.COMPLETED,
                        bytesDownloaded = finalSize,
                        totalBytes = finalSize,
                        progress = 100,
                        destinationUri = exportResult.destinationUri,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            Result.success()
        } catch (e: Exception) {
            if (isStopped) return@withContext Result.retry()
            if (e is IOException) {
                updateState(requestId, DownloadStatus.QUEUED, tempFile.length(), database.findById(requestId)?.totalBytes ?: -1L,
                    "Network interrupted; will resume automatically when connectivity returns.")
                return@withContext Result.retry()
            }
            if (tempFile.exists()) tempFile.delete()
            markFailed(requestId, "Download exception: ${e.message ?: e.javaClass.simpleName}")
            Result.failure()
        } finally {
            connection?.disconnect()
        }
    }

    private fun downloadForegroundInfo(requestId: String, fileName: String): ForegroundInfo {
        val channelId = "pw_downloads"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Download in progress")
            .setContentText(fileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        val notificationId = requestId.hashCode().let { if (it == 0) 1 else kotlin.math.abs(it) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }

    private fun verifyDownloadedFile(file: File, fileName: String, mimeType: String): String? {
        if (!file.exists() || file.length() <= 0L) {
            return "Verification failed: downloaded file is empty (0 bytes)."
        }

        val header = ByteArray(16)
        RandomAccessFile(file, "r").use { raf ->
            val read = raf.read(header)
            if (read < 8) {
                return "Verification failed: file shorter than 8-byte header."
            }
        }

        val headerAscii = String(header, Charsets.US_ASCII)
        if (headerAscii.trimStart().startsWith("#EXTM3U") || headerAscii.trimStart().startsWith("<MPD")) {
            return "Verification failed: file is an HLS/DASH manifest, not a media container."
        }

        val isPdf = mimeType.contains("pdf", ignoreCase = true) || fileName.endsWith(".pdf", ignoreCase = true)
        if (isPdf) {
            if (!headerAscii.startsWith("%PDF-")) {
                return "Verification failed: PDF magic header (%PDF-) missing."
            }
            return null
        }

        // Video container verification: check ISO BMFF `ftyp` at offset 4
        val boxType = String(header, 4, 4, Charsets.US_ASCII)
        if (boxType != "ftyp" && boxType != "moov" && boxType != "wide") {
            return "Verification failed: invalid MP4 container box '$boxType' (expected 'ftyp')."
        }

        // MediaMetadataRetriever check for duration and playable video/audio track
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            if (durationMs <= 0L && hasVideo == null && hasAudio == null) {
                "Verification failed: MediaMetadataRetriever found no playable tracks or duration."
            } else {
                null
            }
        } catch (e: Exception) {
            "Verification failed during media metadata probe: ${e.message}"
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun updateState(
        requestId: String,
        status: DownloadStatus,
        bytesDownloaded: Long,
        totalBytes: Long,
        errorMessage: String?
    ) {
        val existing = database.findById(requestId) ?: return
        val progress = if (totalBytes > 0L) {
            ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else 0
        database.upsert(
            existing.copy(
                status = status,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                progress = progress,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun markFailed(requestId: String, error: String) {
        val existing = database.findById(requestId) ?: return
        database.upsert(
            existing.copy(
                status = DownloadStatus.FAILED,
                errorMessage = error,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun markUnsupported(requestId: String, reason: String) {
        val existing = database.findById(requestId) ?: return
        database.upsert(
            existing.copy(
                status = DownloadStatus.UNSUPPORTED,
                errorMessage = reason,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_MIME_TYPE = "mime_type"

        /**
         * Preserved verbatim from PW-PM.apk DEX:
         * Lcom/pwskills/mobile/pwpm02/DownloadWorker;->isSafeFileName(Ljava/lang/String;)Z
         */
        @JvmStatic
        fun isSafeFileName(fileName: String?): Boolean {
            if (fileName.isNullOrBlank()) return false
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                return false
            }
            return fileName.endsWith(".mp4", ignoreCase = true) ||
                    fileName.endsWith(".m4v", ignoreCase = true) ||
                    fileName.endsWith(".webm", ignoreCase = true) ||
                    fileName.endsWith(".pdf", ignoreCase = true)
        }

        @JvmStatic
        fun isRetryableHttpStatus(code: Int): Boolean = code == 408 || code == 429 || code in 500..599
    }
}
