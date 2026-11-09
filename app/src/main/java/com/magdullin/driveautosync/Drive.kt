package com.magdullin.driveautosync

import android.webkit.MimeTypeMap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class DriveException(val code: Int, val reason: String, message: String) : Exception(message)

sealed class UploadStatus {
    data class Done(val id: String) : UploadStatus()
    data class Partial(val offset: Long) : UploadStatus()
    object Expired : UploadStatus()
}

/** Минимальный клиент Google Drive API v3. */
class Drive(private val token: String) {

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER = "application/vnd.google-apps.folder"
        private val JSON_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
    }

    private fun req(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    /** Возвращает тело ответа или бросает DriveException с причиной от Google. */
    private fun check(r: Response): String {
        val body = r.body?.string() ?: ""
        if (!r.isSuccessful) {
            val reason = try {
                val err = JSONObject(body).getJSONObject("error")
                err.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
                    ?: err.optString("status", "")
            } catch (e: Exception) {
                ""
            }
            throw DriveException(r.code, reason, "Google Диск: HTTP ${r.code} $reason".trim())
        }
        return body
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    fun findFolder(name: String, parent: String): String? {
        val q = "name = '${esc(name)}' and mimeType = '$FOLDER' and '$parent' in parents and trashed = false"
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        http.newCall(req(url.toString()).get().build()).execute().use { r ->
            val files = JSONObject(check(r)).optJSONArray("files") ?: return null
            return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
        }
    }

    fun createFolder(name: String, parent: String): String {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER)
            .put("parents", JSONArray().put(parent))
        val r = req("$API?fields=id").post(meta.toString().toRequestBody(JSON_TYPE)).build()
        http.newCall(r).execute().use { resp -> return JSONObject(check(resp)).getString("id") }
    }

    /** Существует ли файл/папка и не в корзине ли. */
    fun exists(id: String): Boolean {
        http.newCall(req("$API/$id?fields=id,trashed").get().build()).execute().use { r ->
            if (r.code == 404) return false
            return !JSONObject(check(r)).optBoolean("trashed", false)
        }
    }

    /** Начинает загрузку (новый файл или новая версия существующего). Возвращает адрес сессии. */
    fun startUpload(
        name: String,
        parent: String,
        existingId: String?,
        mime: String,
        size: Long,
        mtime: Long,
    ): String {
        val meta = JSONObject().put("modifiedTime", Instant.ofEpochMilli(mtime).toString())
        val builder = if (existingId == null) {
            meta.put("name", name).put("parents", JSONArray().put(parent))
            req("$UPLOAD?uploadType=resumable&fields=id")
                .post(meta.toString().toRequestBody(JSON_TYPE))
        } else {
            req("$UPLOAD/$existingId?uploadType=resumable&fields=id")
                .patch(meta.toString().toRequestBody(JSON_TYPE))
        }
        builder.header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", size.toString())
        http.newCall(builder.build()).execute().use { r ->
            check(r)
            return r.header("Location")
                ?: throw DriveException(r.code, "noLocation", "Google Диск не вернул адрес загрузки")
        }
    }

    /** Сколько байт уже загружено в незавершённой сессии. */
    fun status(session: String, size: Long): UploadStatus {
        val r = req(session)
            .header("Content-Range", "bytes */$size")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(r).execute().use { resp ->
            return when (resp.code) {
                200, 201 -> UploadStatus.Done(JSONObject(resp.body?.string() ?: "{}").optString("id"))
                308 -> {
                    val range = resp.header("Range")
                    val last = range?.substringAfterLast('-')?.toLongOrNull()
                    UploadStatus.Partial(if (last == null) 0L else last + 1)
                }
                else -> UploadStatus.Expired
            }
        }
    }

    /** Отправляет файл (с места offset). Возвращает id файла на Диске. */
    fun upload(session: String, f: File, offset: Long, size: Long, mime: String): String {
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = mime.toMediaTypeOrNull()
            override fun contentLength(): Long = size - offset
            override fun writeTo(sink: BufferedSink) {
                FileInputStream(f).use { input ->
                    var skipped = 0L
                    while (skipped < offset) {
                        val s = input.skip(offset - skipped)
                        if (s <= 0) throw IOException("Не удалось продолжить загрузку")
                        skipped += s
                    }
                    val buf = ByteArray(256 * 1024)
                    var remaining = size - offset
                    while (remaining > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) throw IOException("Файл изменился во время загрузки")
                        sink.write(buf, 0, n)
                        remaining -= n
                    }
                }
            }
        }
        val r = req(session)
            .header("Content-Range", "bytes $offset-${size - 1}/$size")
            .put(body)
            .build()
        http.newCall(r).execute().use { resp -> return JSONObject(check(resp)).optString("id") }
    }
}
