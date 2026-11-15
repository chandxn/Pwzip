package com.pwskills.mobile.pwpm02

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * File 5E: PwPhoneStorageExporter.kt
 * Exports ONLY verified standalone files (direct MP4 or PDF) to:
 *   Documents/PW Triple/PW-PM/Videos/
 *   Documents/PW Triple/PW-PM/Files/
 * Never exports segmented HLS/DASH caches or encrypted Widevine streams as fake MP4 files.
 */
class PwPhoneStorageExporter(private val context: Context) {

    data class ExportResult(
        val success: Boolean,
        val destinationUri: String?,
        val errorMessage: String?
    )

    fun exportVerifiedFileToPhoneStorage(
        sourceFile: File,
        fileName: String,
        mimeType: String,
        subFolder: String
    ): ExportResult {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            return ExportResult(false, null, "Source file does not exist or is 0 bytes.")
        }

        val relativeFolder = "Documents/PW Triple/PW-PM/$subFolder"
        var pendingUri: android.net.Uri? = null

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Files.getContentUri("external")
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeFolder/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val itemUri = resolver.insert(collection, values)
                    ?: return exportFallbackToAppExternalFiles(sourceFile, fileName, subFolder)
                pendingUri = itemUri

                val copied = resolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        val count = input.copyTo(out, bufferSize = 32 * 1024)
                        out.flush()
                        count
                    }
                }
                if (copied == null || copied != sourceFile.length()) {
                    resolver.delete(itemUri, null, null)
                    pendingUri = null
                    return ExportResult(false, null, "Saved file size did not match the verified source; incomplete file was removed.")
                }

                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                if (resolver.update(itemUri, publishValues, null, null) <= 0) {
                    resolver.delete(itemUri, null, null)
                    pendingUri = null
                    return ExportResult(false, null, "Could not publish the verified file in MediaStore.")
                }
                pendingUri = null
                ExportResult(true, itemUri.toString(), null)
            } else {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val targetDir = File(docsDir, "PW Triple/PW-PM/$subFolder")
            if (!targetDir.exists() && !targetDir.mkdirs()) return ExportResult(false, null, "Could not create the download folder.")
                val destFile = File(targetDir, fileName)
            if (destFile.exists()) return ExportResult(false, null, "A file with this name already exists; existing content was preserved.")
            val copied = FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { out ->
                    val count = input.copyTo(out, bufferSize = 32 * 1024)
                        out.flush()
                    count
                    }
                }
            if (copied != sourceFile.length() || destFile.length() != sourceFile.length()) {
                destFile.delete()
                return ExportResult(false, null, "Saved file size did not match the verified source.")
            }
                ExportResult(true, destFile.absolutePath, null)
            }
        } catch (e: Exception) {
            pendingUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            ExportResult(false, null, "Could not safely publish the verified file: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun exportFallbackToAppExternalFiles(
        sourceFile: File,
        fileName: String,
        subFolder: String
    ): ExportResult {
        return try {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            val targetDir = File(base, "PW Triple/PW-PM/$subFolder")
            if (!targetDir.exists() && !targetDir.mkdirs()) return ExportResult(false, null, "Could not create the app download folder.")
            val destFile = File(targetDir, fileName)
            if (destFile.exists()) return ExportResult(false, null, "A file with this name already exists; existing content was preserved.")
            val copied = FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { out ->
                    val count = input.copyTo(out, bufferSize = 32 * 1024)
                    out.flush()
                    count
                }
            }
            if (copied != sourceFile.length() || destFile.length() != sourceFile.length()) {
                destFile.delete()
                return ExportResult(false, null, "Saved file size did not match the verified source.")
            }
            ExportResult(true, destFile.absolutePath, null)
        } catch (e: Exception) {
            ExportResult(false, null, "Fallback storage write failed: ${e.message}")
        }
    }
}
