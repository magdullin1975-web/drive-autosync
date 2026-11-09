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
