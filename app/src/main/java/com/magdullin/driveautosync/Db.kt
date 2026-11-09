package com.magdullin.driveautosync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Учёт загруженных файлов, чтобы ничего не загружать дважды. */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, "uploads.db", null, 1) {

    data class Rec(
        val path: String,
        val size: Long,
        val mtime: Long,
        val driveId: String?,
        val session: String?,
        val sSize: Long,
        val sMtime: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE files (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, " +
                "drive_id TEXT, session TEXT, s_size INTEGER, s_mtime INTEGER, " +
                "uploaded_at INTEGER, name TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun find(path: String): Rec? {
        readableDatabase.rawQuery(
            "SELECT path, size, mtime, drive_id, session, s_size, s_mtime FROM files WHERE path = ?",
            arrayOf(path)
        ).use { cur ->
            if (!cur.moveToFirst()) return null
            return Rec(
                path = cur.getString(0),
                size = cur.getLong(1),
                mtime = cur.getLong(2),
                driveId = if (cur.isNull(3)) null else cur.getString(3),
                session = if (cur.isNull(4)) null else cur.getString(4),
                sSize = cur.getLong(5),
                sMtime = cur.getLong(6),
            )
        }
    }

    private fun ensureRow(path: String) {
        val v = ContentValues()
        v.put("path", path)
        writableDatabase.insertWithOnConflict("files", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun saveSession(path: String, session: String, size: Long, mtime: Long) {
        ensureRow(path)
        val v = ContentValues()
        v.put("session", session)
        v.put("s_size", size)
        v.put("s_mtime", mtime)
        writableDatabase.update("files", v, "path = ?", arrayOf(path))
    }

    fun markDone(path: String, size: Long, mtime: Long, driveId: String, name: String) {
        ensureRow(path)
        val v = ContentValues()
        v.put("size", size)
        v.put("mtime", mtime)
        v.put("drive_id", driveId)
        v.putNull("session")
        v.put("uploaded_at", System.currentTimeMillis())
        v.put("name", name)
        writableDatabase.update("files", v, "path = ?", arrayOf(path))
    }

    fun countDone(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM files WHERE drive_id IS NOT NULL", null)
            .use { cur -> return if (cur.moveToFirst()) cur.getLong(0) else 0L }
    }

    /** Последние загрузки: имя файла и время. */
    fun recent(limit: Int): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery(
            "SELECT name, uploaded_at FROM files WHERE drive_id IS NOT NULL " +
                "ORDER BY uploaded_at DESC LIMIT $limit",
            null
        ).use { cur ->
            while (cur.moveToNext()) out.add(Pair(cur.getString(0) ?: "?", cur.getLong(1)))
        }
        return out
    }

    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(c: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(c.applicationContext).also { instance = it }
            }
    }
}
