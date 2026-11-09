package com.magdullin.driveautosync

import android.content.Context
import java.io.IOException

/** Папки на Google Диске: создаёт недостающие и запоминает их id. */
class Folders(private val c: Context, private val drive: Drive) {
    private val cache = Prefs.folderCache(c)

    /** Если главную папку удалили с Диска — забываем все запомненные папки. */
    fun validateRoot() {
        val id = cache[Prefs.rootName(c)] ?: return
        if (!drive.exists(id)) reset()
    }

    fun reset() {
        cache.clear()
        Prefs.saveFolderCache(c, cache)
    }

    fun ensure(parts: List<String>): String {
        var parent = "root"
        val key = StringBuilder()
        for (p in parts) {
            if (key.isNotEmpty()) key.append('/')
            key.append(p)
            val k = key.toString()
            val known = cache[k]
            val id = if (known != null) {
                known
            } else {
                val found = drive.findFolder(p, parent) ?: drive.createFolder(p, parent)
                cache[k] = found
                Prefs.saveFolderCache(c, cache)
                found
            }
            parent = id
        }
        return parent
    }
}

object Uploader {

    /** Путь на Диске: <Главная папка>/<Устройство>/<папка телефона и подпапки>. */
    fun driveParts(c: Context, item: MediaItem): List<String> {
        val parts = mutableListOf(Prefs.rootName(c), Prefs.deviceName(c))
        item.relativePath.split('/').filter { it.isNotEmpty() }.forEach { parts.add(it) }
        return parts
    }

    fun upload(c: Context, drive: Drive, db: Db, folders: Folders, item: MediaItem, rec: Db.Rec?) {
        val parentId = folders.ensure(driveParts(c, item))
        val size = item.size
        val mtime = item.mtime
        val key = item.key
        val open = {
            c.contentResolver.openInputStream(item.uri) ?: throw IOException("Файл недоступен: ${item.name}")
        }

        var session: String? =
            if (rec?.session != null && rec.sSize == size && rec.sMtime == mtime) rec.session else null
        var offset = 0L

        if (session != null) {
            when (val st = drive.status(session, size)) {
                is UploadStatus.Done -> {
                    db.markDone(key, size, mtime, st.id, item.name)
                    return
                }
                is UploadStatus.Partial -> offset = st.offset
                UploadStatus.Expired -> session = null
            }
        }

        if (session == null) {
            val existing = rec?.driveId?.takeIf { it.isNotEmpty() }
            val newSession = try {
                drive.startUpload(item.name, parentId, existing, item.mime, size, mtime)
            } catch (e: DriveException) {
                if (existing != null && e.code == 404) {
                    drive.startUpload(item.name, parentId, null, item.mime, size, mtime)
                } else {
                    throw e
                }
            }
            db.saveSession(key, newSession, size, mtime)
            session = newSession
            offset = 0L
        }

        val s = session ?: throw DriveException(0, "noSession", "Нет сессии загрузки")
        val id = drive.upload(s, open, offset, size, item.mime)
        db.markDone(key, size, mtime, id, item.name)
    }
}
