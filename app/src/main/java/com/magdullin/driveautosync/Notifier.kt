package com.magdullin.driveautosync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo

/** Уведомления: проблемы, обновления, ход загрузки. */
object Notifier {
    private const val CH_PROBLEMS = "problems"
    private const val CH_SYNC = "sync"
    private const val CH_UPDATES = "updates"

    const val ID_FG = 1
    const val ID_AUTH = 10
    const val ID_STORAGE = 11
    const val ID_QUOTA = 12
    const val ID_FAILED = 13
    const val ID_STALE = 14
    const val ID_REPEAT = 15
    const val ID_UPDATE = 20

    private fun nm(c: Context): NotificationManager =
        c.getSystemService(NotificationManager::class.java)

    fun channels(c: Context) {
        val m = nm(c)
        m.createNotificationChannel(
            NotificationChannel(CH_PROBLEMS, "Проблемы синхронизации", NotificationManager.IMPORTANCE_DEFAULT)
        )
        m.createNotificationChannel(
            NotificationChannel(CH_SYNC, "Идёт загрузка", NotificationManager.IMPORTANCE_LOW)
        )
        m.createNotificationChannel(
            NotificationChannel(CH_UPDATES, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun openApp(c: Context, action: String? = null): PendingIntent {
        val i = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (action != null) i.action = action
        return PendingIntent.getActivity(
            c,
            action?.hashCode() ?: 0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun problem(c: Context, id: Int, title: String, text: String, pi: PendingIntent = openApp(c)) {
        channels(c)
        val n = Notification.Builder(c, CH_PROBLEMS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm(c).notify(id, n)
        } catch (e: SecurityException) {
            // уведомления запрещены пользователем
        }
    }

    fun update(c: Context, name: String) {
        channels(c)
        val n = Notification.Builder(c, CH_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Доступно обновление Drive Autosync")
            .setContentText("$name — нажмите, чтобы установить")
            .setContentIntent(openApp(c, MainActivity.ACTION_UPDATE))
            .setAutoCancel(true)
            .build()
        try {
            nm(c).notify(ID_UPDATE, n)
        } catch (e: SecurityException) {
        }
    }

    fun clear(c: Context, vararg ids: Int) {
        val m = nm(c)
        for (id in ids) m.cancel(id)
    }

    fun foregroundInfo(c: Context): ForegroundInfo {
        channels(c)
        val n = Notification.Builder(c, CH_SYNC)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Drive Autosync")
            .setContentText("Загружаю файлы на Google Диск…")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_FG, n)
        }
    }
}
