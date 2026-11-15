package com.pwskills.mobile.pwpm02

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * File 5A: DownloadDatabase.kt
 * SQLite persistence backing DownloadRepository so downloads survive app restarts.
 */
class DownloadDatabase(context: Context) :
    SQLiteOpenHelper(context, "pw_pm_downloads.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloads (
                request_id TEXT PRIMARY KEY,
                source_url TEXT NOT NULL,
                file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                status TEXT NOT NULL,
                bytes_downloaded INTEGER NOT NULL,
                total_bytes INTEGER NOT NULL,
                progress INTEGER NOT NULL,
                destination_uri TEXT,
                error_message TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS downloads")
        onCreate(db)
    }

    @Synchronized
    fun upsert(item: DownloadItem) {
        val values = ContentValues().apply {
            put("request_id", item.requestId)
            put("source_url", item.sourceUrl)
            put("file_name", item.fileName)
            put("mime_type", item.mimeType)
            put("status", item.status.name)
            put("bytes_downloaded", item.bytesDownloaded)
            put("total_bytes", item.totalBytes)
            put("progress", item.progress)
            put("destination_uri", item.destinationUri)
            put("error_message", item.errorMessage)
            put("created_at", item.createdAt)
            put("updated_at", item.updatedAt)
        }
        writableDatabase.insertWithOnConflict(
            "downloads",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun findById(requestId: String): DownloadItem? {
        readableDatabase.query(
            "downloads",
            null,
            "request_id = ?",
            arrayOf(requestId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return readRow(cursor)
            }
        }
        return null
    }

    @Synchronized
    fun findBySourceUrl(sourceUrl: String): DownloadItem? {
        readableDatabase.query(
            "downloads",
            null,
            "source_url = ?",
            arrayOf(sourceUrl),
            null,
            null,
            "updated_at DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return readRow(cursor)
            }
        }
        return null
    }

    @Synchronized
    fun getAll(): List<DownloadItem> {
        val list = mutableListOf<DownloadItem>()
        readableDatabase.query(
            "downloads",
            null,
            null,
            null,
            null,
            null,
            "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(readRow(cursor))
            }
        }
        return list
    }

    @Synchronized
    fun delete(requestId: String) {
        writableDatabase.delete("downloads", "request_id = ?", arrayOf(requestId))
    }

    private fun readRow(cursor: android.database.Cursor): DownloadItem {
        val statusName = cursor.getString(cursor.getColumnIndexOrThrow("status"))
        val status = try {
            DownloadStatus.valueOf(statusName)
        } catch (_: Exception) {
            DownloadStatus.FAILED
        }
        return DownloadItem(
            requestId = cursor.getString(cursor.getColumnIndexOrThrow("request_id")),
            sourceUrl = cursor.getString(cursor.getColumnIndexOrThrow("source_url")),
            fileName = cursor.getString(cursor.getColumnIndexOrThrow("file_name")),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
            status = status,
            bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow("bytes_downloaded")),
            totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow("total_bytes")),
            progress = cursor.getInt(cursor.getColumnIndexOrThrow("progress")),
            destinationUri = cursor.getString(cursor.getColumnIndexOrThrow("destination_uri")),
            errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("error_message")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }
}
