package com.magdullin.driveautosync

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val ACTION_UPDATE = "com.magdullin.driveautosync.UPDATE"
        private const val RC_AUTH = 100
        private const val RC_PERMS = 101
    }

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5000)
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var folderBox: LinearLayout
    private lateinit var recent: TextView
    private lateinit var updateBanner: Button
    private var shownFolders: List<String>? = null
    private var pendingRelease: Release? = null

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.channels(this)
        Scheduler.ensure(this)

        val sv = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(12), dp(16), dp(32))
        sv.addView(root)
        setContentView(sv)
        build()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_PERMS)
        }
        handleIntent(intent)
        silentUpdateCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == ACTION_UPDATE) checkUpdate()
    }

    // ------------------------------------------------------------------ экран

    private fun build() {
        root.addView(title("Drive Autosync"))
        root.addView(
            text(
                "Сам загружает новые файлы с этого устройства на Google Диск. " +
                    "Оттуда они появляются на компьютере и других устройствах."
            )
        )
        status = text("")
        status.setPadding(0, dp(8), 0, dp(8))
        root.addView(status)

        updateBanner = button("") { checkUpdate() }
        updateBanner.visibility = View.GONE
        root.addView(updateBanner)

        permBox = vbox()
        root.addView(permBox)

        root.addView(button("Синхронизировать сейчас") {
            Scheduler.syncNow(this)
            toast("Синхронизация запущена")
        })

        root.addView(header("Папки на этом устройстве"))
        folderBox = vbox()
        root.addView(folderBox)
        root.addView(button("＋ Добавить папку") { pickFolder(null) })

        root.addView(header("Настройки"))
        root.addView(text("Главная папка на Google Диске:"))
        val rootEdit = edit(Prefs.rootName(this))
        root.addView(rootEdit)
        root.addView(text("Имя этого устройства (подпапка на Диске):"))
        val devEdit = edit(Prefs.deviceName(this))
        root.addView(devEdit)
        root.addView(button("Сохранить названия") {
            Prefs.setRootName(this, rootEdit.text.toString().trim().ifEmpty { Prefs.DEFAULT_ROOT })
            Prefs.setDeviceName(this, devEdit.text.toString().trim().ifEmpty { Prefs.defaultDeviceName(this) })
            toast("Сохранено")
        })
        val cb = CheckBox(this)
        cb.text = "Файлы больше 50 МБ — только по Wi‑Fi"
        cb.isChecked = Prefs.wifiOnlyBig(this)
        cb.setOnCheckedChangeListener { _, v -> Prefs.setWifiOnlyBig(this, v) }
        root.addView(cb)

        root.addView(header("Последние загрузки"))
        recent = text("—")
        root.addView(recent)

        root.addView(header("О приложении"))
        root.addView(text("Версия ${BuildConfig.VERSION_NAME}"))
        root.addView(button("Проверить обновления") { checkUpdate() })
    }

    private fun refresh() {
        val sb = StringBuilder()
        sb.append(if (Prefs.signedIn(this)) "✅ Google Диск подключён\n" else "⚠️ Google Диск не подключён\n")
        val last = Prefs.lastSuccess(this)
        sb.append("Последняя синхронизация: ")
            .append(if (last == 0L) "ещё не было" else fmt(last, "dd.MM.yyyy HH:mm"))
            .append('\n')
        sb.append("Загружено файлов: ${Db.get(this).countDone()}\n")
        sb.append("Куда: Google Диск / ${Prefs.rootName(this)} / ${Prefs.deviceName(this)}\n")
        val w = Prefs.waitingWifi(this)
        if (w > 0) sb.append("Ждут Wi‑Fi (большие файлы): $w\n")
        Prefs.lastError(this)?.let { sb.append("❗ $it\n") }
        status.text = sb.toString().trim()

        permBox.removeAllViews()
        if (!Prefs.signedIn(this)) permBox.addView(button("1. Войти в Google") { signIn() })
        if (!Storage.hasAccess(this)) permBox.addView(button("2. Разрешить доступ к файлам") { requestStorage() })
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            permBox.addView(button("3. Отключить экономию батареи для приложения") { requestBattery() })
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permBox.addView(button("Разрешить уведомления") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_PERMS)
            })
        }

        val folders = Prefs.folders(this)
        if (folders != shownFolders) {
            shownFolders = folders
            folderBox.removeAllViews()
            if (folders.isEmpty()) {
                folderBox.addView(text("Пока нет папок. Добавьте, например, Download и Pictures."))
            }
            for (p in folders) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                val t = text("📁 " + Storage.pretty(p))
                row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(button("Убрать") { confirmRemove(p) })
                folderBox.addView(row)
            }
        }

        val items = Db.get(this).recent(15)
        recent.text = if (items.isEmpty()) "—" else
            items.joinToString("\n") { "${fmt(it.second, "dd.MM HH:mm")}  ${it.first}" }
    }

    // ------------------------------------------------------------------ Google

    private fun signIn() {
        scope.launch {
            try {
                val r = Identity.getAuthorizationClient(this@MainActivity).authorize(Auth.request()).await()
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    startIntentSenderForResult(pi.intentSender, RC_AUTH, null, 0, 0, 0)
                } else if (r.accessToken != null) {
                    onSignedIn()
                } else {
                    toast("Google не выдал доступ")
                }
            } catch (e: Exception) {
                showError("Не удалось войти в Google", e.message ?: e.toString())
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_AUTH) return
        if (data == null) {
            toast("Вход отменён")
            return
        }
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
            if (r.accessToken != null) onSignedIn() else toast("Вход не выполнен")
        } catch (e: Exception) {
            showError("Не удалось войти в Google", e.message ?: e.toString())
        }
    }

    private fun onSignedIn() {
        Prefs.setSignedIn(this, true)
        Notifier.clear(this, Notifier.ID_AUTH)
        toast("Google Диск подключён")
        Scheduler.syncNow(this)
        refresh()
    }

    // ------------------------------------------------------------------ разрешения

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), RC_PERMS)
        }
    }

    private fun requestBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ------------------------------------------------------------------ выбор папок

    private fun pickFolder(dir: File?) {
        if (!Storage.hasAccess(this)) {
            toast("Сначала разрешите доступ к файлам")
            requestStorage()
            return
        }
        if (dir == null) {
            val roots = Storage.roots(this)
            val primary = Storage.primary()
            val quick = listOf("Download", "Pictures", "DCIM", "Movies", "Music", "Documents")
                .map { File(primary, it) }
                .filter { it.isDirectory }
            val choices = roots + quick
            val labels = roots.mapIndexed { i, f -> if (i == 0) "📱 Вся память устройства" else "💾 Карта памяти (${f.name})" } +
                quick.map { "📁 ${it.name}" }
            AlertDialog.Builder(this)
                .setTitle("Где искать папку?")
                .setItems(labels.toTypedArray()) { _, w -> pickFolder(choices[w]) }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        val subs = (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val labels = listOf("✔ Выбрать эту папку", "⬆ Назад") + subs.map { "📁 ${it.name}" }
        AlertDialog.Builder(this)
            .setTitle(Storage.pretty(dir.absolutePath))
            .setItems(labels.toTypedArray()) { _, w ->
                when (w) {
                    0 -> askBaseline(dir)
                    1 -> {
                        val isRoot = Storage.roots(this).any { it.absolutePath == dir.absolutePath }
                        pickFolder(if (isRoot) null else dir.parentFile)
                    }
                    else -> pickFolder(subs[w - 2])
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askBaseline(dir: File) {
        if (Prefs.folders(this).contains(dir.absolutePath)) {
            toast("Эта папка уже добавлена")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(dir.name)
            .setMessage("Загрузить на Диск и файлы, которые уже лежат в этой папке?\n\n«Только новые» — загружать то, что появится с этого момента.")
            .setPositiveButton("Да, все файлы") { _, _ -> addFolder(dir, 0L) }
            .setNegativeButton("Только новые") { _, _ -> addFolder(dir, System.currentTimeMillis()) }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun addFolder(dir: File, baseline: Long) {
        Prefs.addFolder(this, dir.absolutePath, baseline)
        toast("Папка добавлена")
        Scheduler.syncNow(this)
        refresh()
    }

    private fun confirmRemove(path: String) {
        AlertDialog.Builder(this)
            .setTitle("Убрать папку?")
            .setMessage("${Storage.pretty(path)}\n\nФайлы на телефоне и на Google Диске останутся, просто новые больше не будут загружаться.")
            .setPositiveButton("Убрать") { _, _ ->
                Prefs.removeFolder(this, path)
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ------------------------------------------------------------------ обновления

    private fun silentUpdateCheck() {
        scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { Updater.latest() }
            } catch (e: Exception) {
                null
            }
            if (r != null && Updater.isNewer(r)) {
                pendingRelease = r
                updateBanner.text = "⬆ Доступно обновление: ${r.name} — нажмите, чтобы установить"
                updateBanner.visibility = View.VISIBLE
            }
        }
    }

    private fun checkUpdate() {
        scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { Updater.latest() }
            } catch (e: Exception) {
                null
            }
            if (r == null) {
                toast("Не удалось проверить обновления")
                return@launch
            }
            if (!Updater.isNewer(r)) {
                updateBanner.visibility = View.GONE
                toast("У вас последняя версия")
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Доступна новая версия")
                .setMessage("${r.name}\n\nУстановить сейчас?")
                .setPositiveButton("Обновить") { _, _ -> installUpdate(r) }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    private fun installUpdate(r: Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Разрешите установку обновлений, затем нажмите «Обновить» ещё раз")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        toast("Скачиваю обновление…")
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { Updater.download(this@MainActivity, r) }
                withContext(Dispatchers.IO) { Updater.install(this@MainActivity, apk) }
            } catch (e: Exception) {
                showError("Обновление не удалось", e.message ?: e.toString())
            }
        }
    }

    // ------------------------------------------------------------------ помощники

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 15f
        t.setTextIsSelectable(true)
        return t
    }

    private fun title(s: String): TextView {
        val t = text(s)
        t.textSize = 24f
        t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun header(s: String): TextView {
        val t = text(s)
        t.textSize = 18f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, dp(20), 0, dp(6))
        return t
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun edit(value: String): EditText {
        val e = EditText(this)
        e.setText(value)
        e.setSingleLine()
        return e
    }

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun fmt(t: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun showError(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg + "\n\nЕсли повторяется — сделайте скриншот и отправьте Claude.")
            .setPositiveButton("OK", null)
            .show()
    }
}
