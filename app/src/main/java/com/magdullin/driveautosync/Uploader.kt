package com.magdullin.driveautosync

import android.content.Context
import java.io.File

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

    /** Путь на Диске: <Главная папка>/<Устройство>/<Папка телефона>/<подпапки>. */
    fun driveParts(c: Context, f: File, base: File): List<String> {
        val parts = mutableListOf(Prefs.rootName(c), Prefs.deviceName(c), base.name)
        val parent = f.parentFile
        if (parent != null) {
            val rel = parent.relativeTo(base).path
            rel.split(File.separatorChar).filter { it.isNotEmpty() && it != "." }.forEach { parts.add(it) }
        }
        return parts
    }

    fun upload(c: Context, drive: Drive, db: Db, folders: Folders, f: File, base: File, rec: Db.Rec?) {
        val parentId = folders.ensure(driveParts(c, f, base))
        val size = f.length()
        val mtime = f.lastModified()
        val mime = Drive.mimeOf(f.name)
        val path = f.absolutePath

        var session: String? =
            if (rec?.session != null && rec.sSize == size && rec.sMtime == mtime) rec.session else null
        var offset = 0L

        if (session != null) {
            when (val st = drive.status(session, size)) {
                is UploadStatus.Done -> {
                    db.markDone(path, size, mtime, st.id, f.name)
                    return
                }
                is UploadStatus.Partial -> offset = st.offset
                UploadStatus.Expired -> session = null
            }
        }

        if (session == null) {
            val existing = rec?.driveId?.takeIf { it.isNotEmpty() }
            val newSession = try {
                drive.startUpload(f.name, parentId, existing, mime, size, mtime)
            } catch (e: DriveException) {
                if (existing != null && e.code == 404) {
                    drive.startUpload(f.name, parentId, null, mime, size, mtime)
                } else {
                    throw e
                }
            }
            db.saveSession(path, newSession, size, mtime)
            session = newSession
            offset = 0L
        }

        val s = session ?: throw DriveException(0, "noSession", "Нет сессии загрузки")
        val id = drive.upload(s, f, offset, size, mime)
        db.markDone(path, size, mtime, id, f.name)
    }
}

object Scanner {
    /** Все файлы в папке и подпапках (кроме скрытых), от старых к новым. */
    fun scan(dir: File): List<File> {
        val out = ArrayList<File>()
        walk(dir, 0, out)
        return out.sortedBy { it.lastModified() }
    }

    private fun walk(d: File, depth: Int, out: MutableList<File>) {
        val list = d.listFiles() ?: return
        for (f in list) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth < 6 && f.name != "Android") walk(f, depth + 1, out)
            } else if (f.isFile) {
                out.add(f)
            }
        }
    }
}
