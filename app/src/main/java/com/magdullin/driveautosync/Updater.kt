package com.magdullin.driveautosync

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Release(val code: Int, val name: String, val apkUrl: String)

/** Автообновление: новые версии берутся из Releases на GitHub. */
object Updater {
    private const val REPO = "magdullin1975-web/drive-autosync"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun latest(): Release? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "drive-autosync")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val j = JSONObject(r.body?.string() ?: return null)
            val code = j.optString("tag_name").removePrefix("v").toIntOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    val name = j.optString("name").ifEmpty { "Версия $code" }
                    return Release(code, name, a.getString("browser_download_url"))
                }
            }
            return null
        }
    }

    fun isNewer(r: Release): Boolean = r.code > BuildConfig.VERSION_CODE

    fun download(c: Context, r: Release): File {
        val f = File(c.cacheDir, "update.apk")
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "drive-autosync").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("пустой ответ")
            f.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        return f
    }

    fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                c, sessionId, Intent(c, InstallReceiver::class.java), flags
            )
            session.commit(pi.intentSender)
        }
    }
}

/** Получает результат установки обновления. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        c.startActivity(confirm)
                    } catch (e: Exception) {
                        Notifier.problem(
                            c, Notifier.ID_UPDATE, "Подтвердите обновление",
                            "Откройте Drive Autosync и нажмите «Обновить» ещё раз."
                        )
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Notifier.clear(c, Notifier.ID_UPDATE)
            else -> Notifier.problem(
                c, Notifier.ID_UPDATE, "Обновление не установилось",
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Неизвестная ошибка"
            )
        }
    }
}
