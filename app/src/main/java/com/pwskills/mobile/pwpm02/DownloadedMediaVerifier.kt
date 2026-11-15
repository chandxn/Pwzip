package com.pwskills.mobile.pwpm02

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.InputStream

/** Validation shared by the download worker and the offline library before reopening a saved item. */
object DownloadedMediaVerifier {
    fun isSupportedVideo(fileName: String, mimeType: String): Boolean {
        val name = fileName.lowercase()
        val mime = mimeType.lowercase()
        return (name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".webm")) &&
            (mime.startsWith("video/") || mime == "application/octet-stream")
    }

    fun verify(file: File, fileName: String, mimeType: String): String? {
        if (!file.exists() || file.length() <= 0L) return "Saved file is missing or empty."
        val isPdf = mimeType.contains("pdf", true) || fileName.endsWith(".pdf", true)
        val headerError = file.inputStream().use { validateHeader(it, isPdf, fileName) }
        if (headerError != null || isPdf) return headerError
        if (!isSupportedVideo(fileName, mimeType)) return "This file format is not supported by the in-app player."
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            validateTracks(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            )
        } catch (e: Exception) {
            "Saved media could not be decoded: ${e.message ?: "unsupported media"}"
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun verify(context: Context, uri: Uri, fileName: String, mimeType: String): String? {
        val isPdf = mimeType.contains("pdf", true) || fileName.endsWith(".pdf", true)
        val headerError = context.contentResolver.openInputStream(uri)?.use { validateHeader(it, isPdf, fileName) }
            ?: "Saved file is missing or cannot be opened."
        if (headerError != null || isPdf) return headerError
        if (!isSupportedVideo(fileName, mimeType)) return "This file format is not supported by the in-app player."
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            validateTracks(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            )
        } catch (e: Exception) {
            "Saved media could not be decoded: ${e.message ?: "unsupported media"}"
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun validateHeader(input: InputStream, isPdf: Boolean, fileName: String): String? {
        val header = ByteArray(16)
        var count = 0
        while (count < header.size) {
            val read = input.read(header, count, header.size - count)
            if (read <= 0) break
            count += read
        }
        return validateHeaderBytes(header, count, isPdf, fileName)
    }

    internal fun validateHeaderBytes(header: ByteArray, count: Int, isPdf: Boolean, fileName: String): String? {
        if (isPdf) return if (count >= 5 && String(header, 0, 5, Charsets.US_ASCII) == "%PDF-") null
            else "Saved file failed PDF integrity verification."
        if (fileName.endsWith(".webm", true)) {
            val valid = count >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            return if (valid) null else "Saved file failed WebM integrity verification."
        }
        val box = if (count >= 8) String(header, 4, 4, Charsets.US_ASCII) else ""
        return if (box == "ftyp" || box == "moov" || box == "wide") null
            else "Saved file failed MP4 integrity verification."
    }

    private fun validateTracks(durationMs: Long, video: String?, audio: String?): String? =
        if (durationMs > 0L && (video == "yes" || audio == "yes")) null
        else "Saved file has no playable audio/video track or duration."
}
