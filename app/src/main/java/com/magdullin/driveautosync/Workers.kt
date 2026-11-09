package com.magdullin.driveautosync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

object Net {
    fun isUnmetered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

object Scheduler {
    private fun net() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensure(c: Context) {
        val wm = WorkManager.getInstance(c)
        wm.enqueueUniquePeriodicWork(
            "sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net()).build()
        )
        wm.enqueueUniquePeriodicWork(
            "watchdog", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(6, TimeUnit.HOURS).build()
        )
        wm.enqueueUniquePeriodicWork(
            "update", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS).setConstraints(net()).build()
        )
    }

    fun syncNow(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "sync-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net()).build()
        )
    }

    fun continueLater(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "sync-more", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(net())
                .build()
        )
    }
}

/** Главная работа: находит новые файлы и загружает их на Диск. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private val lock = Mutex()
        private const val BIG_FILE = 50L * 1024 * 1024
        private const val SETTLE_MS = 60_000L
    }

    override suspend fun doWork(): Result {
        if (!lock.tryLock()) return Result.success()
        try {
            return withContext(Dispatchers.IO) { runSync() }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun runSync(): Result {
        val c = applicationContext
        val folders = Prefs.folders(c)
        val shared = ShareQueue.count(c)
        if (folders.isEmpty() && shared == 0) return Result.success()

        val needsMedia = folders.any { !Docs.isTree(it) }
        if (needsMedia && !Media.hasAccess(c)) {
            Notifier.problem(
                c, Notifier.ID_STORAGE, "Нет доступа к фото и видео",
                "Откройте Drive Autosync и нажмите «Разрешить доступ к фото и видео»."
            )
        } else {
            Notifier.clear(c, Notifier.ID_STORAGE)
        }

        val foreground = try {
            setForeground(Notifier.foregroundInfo(c))
            true
        } catch (e: Exception) {
            false
        }

        val token = when (val t = Auth.token(c)) {
            is TokenResult.Ok -> t.token
            is TokenResult.NeedsUser -> {
                Prefs.setSignedIn(c, false)
                Notifier.problem(
                    c, Notifier.ID_AUTH, "Нужно войти в Google",
                    "Откройте Drive Autosync и нажмите «Войти в Google»."
                )
                Prefs.recordError(c, "Нужно войти в Google")
                return Result.success()
            }
            is TokenResult.Failed -> {
                report(c, "Вход в Google: ${t.message}", 0, emptyList())
                return Result.retry()
            }
        }
        Prefs.setSignedIn(c, true)
        Notifier.clear(c, Notifier.ID_AUTH)

        val drive = Drive(token)
        val db = Db.get(c)
        val dirs = Folders(c, drive)
        val started = System.currentTimeMillis()
        val limit = if (foreground) Long.MAX_VALUE else 8 * 60_000L
        val unmetered = Net.isUnmetered(c)
        val wifiOnly = Prefs.wifiOnlyBig(c)

        var failed = 0
        var waitingWifi = 0
        var more = false
        var lastErr: String? = null
        val failedNames = ArrayList<String>()

        try {
            dirs.validateRoot()
            for ((qdir, item) in ShareQueue.items(c)) {
                if (isStopped) break
                Uploader.upload(c, drive, db, dirs, item, null)
                qdir.deleteRecursively()
            }
            outer@ for (folder in folders) {
                val baseline = Prefs.baseline(c, folder)
                val items = when {
                    Docs.isTree(folder) -> Docs.scan(c, folder)
                    Media.hasAccess(c) -> Media.scan(c, folder)
                    else -> emptyList()
                }
                for (item in items) {
                    if (isStopped) break@outer
                    if (System.currentTimeMillis() - started > limit) {
                        more = true
                        break@outer
                    }
                    if (item.size <= 0L || item.mtime < baseline) continue
                    if (System.currentTimeMillis() - item.mtime < SETTLE_MS) {
                        more = true // файл ещё записывается — заберём чуть позже
                        continue
                    }
                    val rec = db.find(item.key)
                    if (rec?.driveId != null && rec.size == item.size && kotlin.math.abs(rec.mtime - item.mtime) < 2000L) continue
                    if (wifiOnly && !unmetered && item.size > BIG_FILE) {
                        waitingWifi++
                        continue
                    }
                    try {
                        Uploader.upload(c, drive, db, dirs, item, rec)
                    } catch (e: DriveException) {
                        when {
                            e.code == 401 -> {
                                Auth.clear(c, token)
                                throw e
                            }
                            e.reason == "storageQuotaExceeded" || e.reason == "quotaExceeded" -> {
                                Notifier.problem(
                                    c, Notifier.ID_QUOTA, "Google Диск заполнен",
                                    "Загрузка остановлена. Освободите место на Google Диске."
                                )
                                throw e
                            }
                            e.code == 429 || e.reason.contains("rateLimit", ignoreCase = true) -> throw e
                            else -> {
                                if (e.code == 404) dirs.reset()
                                failed++
                                failedNames.add(item.name)
                                lastErr = e.message
                            }
                        }
                    }
                }
            }
        } catch (e: DriveException) {
            lastErr = e.message
        } catch (e: IOException) {
            lastErr = "Нет связи: ${e.message ?: "сеть"}"
        }

        Prefs.setWaitingWifi(c, waitingWifi)
        report(c, lastErr, failed, failedNames)
        if (more) Scheduler.continueLater(c)
        return Result.success()
    }

    private fun report(c: Context, err: String?, failed: Int, names: List<String>) {
        if (err == null) {
            Prefs.recordOk(c)
            Notifier.clear(c, Notifier.ID_FAILED, Notifier.ID_REPEAT, Notifier.ID_QUOTA, Notifier.ID_STALE)
            return
        }
        val times = Prefs.recordError(c, err)
        if (failed > 0) {
            Notifier.problem(
                c, Notifier.ID_FAILED, "Не удалось загрузить файлов: $failed",
                names.take(5).joinToString(", ") + "\nПопробую снова автоматически."
            )
        }
        if (times >= 5) {
            Notifier.problem(
                c, Notifier.ID_REPEAT, "Синхронизация постоянно не удаётся",
                "Ошибка: $err\nЕсли так и останется — напишите Claude: возможно, нужна новая версия приложения."
            )
        }
    }
}

/** Сторож: предупреждает, если синхронизация давно не проходила. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (Prefs.folders(c).isEmpty()) return Result.success()
        val last = Prefs.lastSuccess(c)
        if (last > 0 && System.currentTimeMillis() - last > TimeUnit.HOURS.toMillis(24)) {
            Notifier.problem(
                c, Notifier.ID_STALE, "Синхронизация не работала больше суток",
                "Откройте Drive Autosync: проверьте интернет и отключите для приложения экономию батареи."
            )
        }
        if (Prefs.folders(c).any { !Docs.isTree(it) } && !Media.hasAccess(c)) {
            Notifier.problem(
                c, Notifier.ID_STORAGE, "Нет доступа к фото и видео",
                "Откройте Drive Autosync и нажмите «Разрешить доступ к фото и видео»."
            )
        }
        return Result.success()
    }
}

/** Раз в день проверяет, есть ли новая версия приложения. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        try {
            val r = Updater.latest()
            if (r != null && Updater.isNewer(r) && Prefs.notifiedVersion(c) != r.code) {
                Notifier.update(c, r.name)
                Prefs.setNotifiedVersion(c, r.code)
            }
        } catch (e: Exception) {
            // нет сети — проверим завтра
        }
        Result.success()
    }
}
