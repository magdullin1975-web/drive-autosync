package com.magdullin.driveautosync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/** «Поделиться → Drive Autosync»: принимает любые файлы и ставит их в очередь на Диск. */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = incoming(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Нет файлов для отправки", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(this, "Готовлю файлов: ${uris.size}…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            var ok = 0
            for (u in uris) {
                try {
                    copy(u)
                    ok++
                } catch (e: Exception) {
                    // пропускаем недоступный файл
                }
            }
            Scheduler.syncNow(app)
            runOnUiThread {
                Toast.makeText(
                    app,
                    if (ok > 0) "Отправляю на Google Диск: $ok → ${Prefs.rootName(app)}/${Prefs.deviceName(app)}/Geteilt"
                    else "Не удалось прочитать файлы",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }.start()
    }

    private fun incoming(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        return when (i.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private fun nameOf(u: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) {
        }
        val n = name ?: u.lastPathSegment ?: "datei"
        return n.replace('/', '_').replace('\\', '_').ifEmpty { "datei" }
    }

    private fun copy(u: Uri) {
        val d = File(ShareQueue.dir(this), "${System.currentTimeMillis()}-${(0..99999).random()}")
        d.mkdirs()
        val f = File(d, nameOf(u))
        val input = contentResolver.openInputStream(u) ?: throw IllegalStateException("нет доступа")
        input.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        if (f.length() == 0L) {
            f.delete()
            d.delete()
            throw IllegalStateException("пустой файл")
        }
    }
}
