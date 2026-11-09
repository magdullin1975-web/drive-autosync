package com.magdullin.driveautosync

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Release(val code: Int, val name: String, val apkUrl: String)

/** Проверка новых версий в Releases на GitHub. Установка — через браузер одним нажатием. */
object Updater {
    private const val REPO = "magdullin1975-web/drive-autosync"
    const val PAGE = "https://github.com/$REPO/releases/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
}
