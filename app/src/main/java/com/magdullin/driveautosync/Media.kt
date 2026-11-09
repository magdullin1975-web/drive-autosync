package com.magdullin.driveautosync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Один файл из медиатеки телефона. */
data class MediaItem(
    val uri: Uri,
    val name: String,
    val relativePath: String, // например "Download/" или "Pictures/Gemini/"
    val size: Long,
    val mtime: Long,          // миллисекунды
    val mime: String,
) {
    /** Уникальный ключ для учёта загрузок. */
    val key: String get() = relativePath + name
}

/**
 * Доступ к файлам через медиатеку Android (как у галереи):
 * картинки, видео и аудио из любых папок, включая Download.
 */
object Media {

    private val collections: List<Uri>
        get() = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        )

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                c.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Все папки, где есть медиафайлы, с количеством файлов. */
    fun folders(c: Context): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        for (col in collections) {
            try {
                c.contentResolver.query(
                    col, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val p = cur.getString(0) ?: continue
                        counts[p] = (counts[p] ?: 0) + 1
                    }
                }
            } catch (e: Exception) {
                // коллекция недоступна — пропускаем
            }
        }
        // Добавляем и родительские папки (Pictures/ для Pictures/Gemini/)
        val total = HashMap<String, Int>()
        for ((p, n) in counts) {
            val parts = p.trimEnd('/').split('/')
            for (i in 1..parts.size) {
                val prefix = parts.take(i).joinToString("/") + "/"
                total[prefix] = (total[prefix] ?: 0) + n
            }
        }
        return total.entries.sortedBy { it.key.lowercase() }.map { Pair(it.key, it.value) }
    }

    /** Файлы в папке (и её подпапках), от старых к новым. */
    fun scan(c: Context, folder: String): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        val args = arrayOf(folder.replace("%", "\\%") + "%")
        for (col in collections) {
            try {
                c.contentResolver.query(col, proj, sel, args, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getLong(0)
                        val name = cur.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        out.add(
                            MediaItem(
                                uri = ContentUris.withAppendedId(col, id),
                                name = name,
                                relativePath = cur.getString(2) ?: folder,
                                size = cur.getLong(3),
                                mtime = maxOf(cur.getLong(4), cur.getLong(6)) * 1000L,
                                mime = cur.getString(5) ?: Drive.mimeOf(name),
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // нет доступа к этой коллекции
            }
        }
        return out.sortedBy { it.mtime }
    }
}

/**
 * Папки с документами (любые файлы), выбранные через стандартное окно Android.
 * Хранятся в настройках как "tree:<uri>".
 */
object Docs {
    const val PREFIX = "tree:"

    fun isTree(folder: String) = folder.startsWith(PREFIX)

    fun treeUri(folder: String): Uri = Uri.parse(folder.removePrefix(PREFIX))

    /** Путь вида "Documents/Rechnungen/" из id документа ("primary:Documents/Rechnungen"). */
    private fun pathOf(docId: String): String {
        val p = docId.substringAfter(':', "").trim('/')
        return if (p.isEmpty()) "" else "$p/"
    }

    fun label(folder: String): String {
        if (!isTree(folder)) return folder.trimEnd('/')
        return try {
            val id = android.provider.DocumentsContract.getTreeDocumentId(treeUri(folder))
            val p = pathOf(id).trimEnd('/')
            "📄 " + (if (p.isEmpty()) "Память устройства" else p)
        } catch (e: Exception) {
            "📄 папка документов"
        }
    }

    fun scan(c: Context, folder: String): List<MediaItem> {
        val tree = treeUri(folder)
        val out = ArrayList<MediaItem>()
        try {
            val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
            walk(c, tree, rootId, 0, out)
        } catch (e: Exception) {
            // доступ к папке потерян
        }
        return out.sortedBy { it.mtime }
    }

    private fun walk(c: Context, tree: Uri, docId: String, depth: Int, out: MutableList<MediaItem>) {
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val proj = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_SIZE,
            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        c.contentResolver.query(children, proj, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(0) ?: continue
                val name = cur.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = cur.getString(2) ?: ""
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 6 && name != "Android") walk(c, tree, id, depth + 1, out)
                } else {
                    val full = pathOf(id) // "Documents/a/file.pdf/"
                    val rel = full.trimEnd('/').substringBeforeLast('/', "")
                    out.add(
                        MediaItem(
                            uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name = name,
                            relativePath = if (rel.isEmpty()) "" else "$rel/",
                            size = if (cur.isNull(3)) 0L else cur.getLong(3),
                            mtime = if (cur.isNull(4)) 0L else cur.getLong(4),
                            mime = mime.ifEmpty { Drive.mimeOf(name) },
                        )
                    )
                }
            }
        }
    }
}

/** Файлы, отправленные через «Поделиться → Drive Autosync». Лежат во внутренней папке до загрузки. */
object ShareQueue {
    fun dir(c: Context): java.io.File = java.io.File(c.filesDir, "share").apply { mkdirs() }

    fun items(c: Context): List<Pair<java.io.File, MediaItem>> {
        val out = ArrayList<Pair<java.io.File, MediaItem>>()
        for (d in dir(c).listFiles() ?: emptyArray()) {
            val f = d.listFiles()?.firstOrNull { it.isFile } ?: continue
            out.add(
                Pair(
                    d,
                    MediaItem(
                        uri = Uri.fromFile(f),
                        name = f.name,
                        relativePath = "Geteilt/",
                        size = f.length(),
                        mtime = f.lastModified(),
                        mime = Drive.mimeOf(f.name),
                    )
                )
            )
        }
        return out
    }

    fun count(c: Context): Int = dir(c).listFiles()?.size ?: 0
}
