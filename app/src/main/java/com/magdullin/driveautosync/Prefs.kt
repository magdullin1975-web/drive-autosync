package com.magdullin.driveautosync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/** Все настройки и состояние приложения. */
object Prefs {
    const val DEFAULT_ROOT = "00_Inbox"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- папки на устройстве ----
    fun folders(c: Context): List<String> =
        (sp(c).getStringSet("folders", emptySet()) ?: emptySet()).sorted()

    fun addFolder(c: Context, path: String, baseline: Long) {
        val set = (sp(c).getStringSet("folders", emptySet()) ?: emptySet()).toMutableSet()
        set.add(path)
        sp(c).edit().putStringSet("folders", set).putLong("base_$path", baseline).apply()
    }

    fun removeFolder(c: Context, path: String) {
        val set = (sp(c).getStringSet("folders", emptySet()) ?: emptySet()).toMutableSet()
        set.remove(path)
        sp(c).edit().putStringSet("folders", set).remove("base_$path").apply()
    }

    /** Файлы старше этого времени не загружаются (0 = загружать всё). */
    fun baseline(c: Context, path: String): Long = sp(c).getLong("base_$path", 0L)

    // ---- куда загружать ----
    fun rootName(c: Context): String {
        val v = sp(c).getString("root", null)
        // старое русское имя переименовано на Диске в 00_Inbox
        return if (v == null || v == "00_Входящие") DEFAULT_ROOT else v
    }

    fun setRootName(c: Context, v: String) {
        sp(c).edit().putString("root", v).apply()
        clearFolderCache(c)
    }

    fun defaultDeviceName(c: Context): String {
        val n = try {
            Settings.Global.getString(c.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        return clean(if (n.isNullOrBlank()) Build.MODEL ?: "Android" else n)
    }

    fun deviceName(c: Context): String = sp(c).getString("device", null) ?: defaultDeviceName(c)

    fun setDeviceName(c: Context, v: String) {
        sp(c).edit().putString("device", clean(v)).apply()
        clearFolderCache(c)
    }

    private fun clean(s: String): String = s.replace('/', '-').replace('\\', '-').trim()

    fun wifiOnlyBig(c: Context): Boolean = sp(c).getBoolean("wifi_big", true)
    fun setWifiOnlyBig(c: Context, v: Boolean) = sp(c).edit().putBoolean("wifi_big", v).apply()

    // ---- Google ----
    fun signedIn(c: Context): Boolean = sp(c).getBoolean("signed_in", false)
    fun setSignedIn(c: Context, v: Boolean) = sp(c).edit().putBoolean("signed_in", v).apply()

    // ---- кэш папок на Диске: "путь" -> id ----
    fun folderCache(c: Context): MutableMap<String, String> {
        val m = HashMap<String, String>()
        try {
            val j = JSONObject(sp(c).getString("fcache", "{}") ?: "{}")
            for (k in j.keys()) m[k] = j.getString(k)
        } catch (e: Exception) {
            // повреждённый кэш просто сбрасываем
        }
        return m
    }

    fun saveFolderCache(c: Context, m: Map<String, String>) {
        val j = JSONObject()
        for ((k, v) in m) j.put(k, v)
        sp(c).edit().putString("fcache", j.toString()).apply()
    }

    fun clearFolderCache(c: Context) = sp(c).edit().remove("fcache").apply()

    // ---- состояние синхронизации ----
    fun lastSuccess(c: Context): Long = sp(c).getLong("last_ok", 0L)
    fun lastError(c: Context): String? = sp(c).getString("last_error", null)

    fun recordOk(c: Context) {
        sp(c).edit()
            .putLong("last_ok", System.currentTimeMillis())
            .remove("last_error")
            .putInt("err_count", 0)
            .remove("err_sig")
            .apply()
    }

    /** Запоминает ошибку; возвращает, сколько раз подряд повторилась та же ошибка. */
    fun recordError(c: Context, msg: String): Int {
        val sig = msg.take(60)
        val prev = sp(c).getString("err_sig", null)
        val count = if (prev == sig) sp(c).getInt("err_count", 0) + 1 else 1
        sp(c).edit()
            .putString("last_error", msg)
            .putString("err_sig", sig)
            .putInt("err_count", count)
            .apply()
        return count
    }

    fun waitingWifi(c: Context): Int = sp(c).getInt("waiting_wifi", 0)
    fun setWaitingWifi(c: Context, n: Int) = sp(c).edit().putInt("waiting_wifi", n).apply()

    fun notifiedVersion(c: Context): Int = sp(c).getInt("notified_version", 0)
    fun setNotifiedVersion(c: Context, v: Int) = sp(c).edit().putInt("notified_version", v).apply()
}
