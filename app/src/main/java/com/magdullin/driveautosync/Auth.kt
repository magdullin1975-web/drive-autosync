package com.magdullin.driveautosync

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

sealed class TokenResult {
    data class Ok(val token: String) : TokenResult()
    data class NeedsUser(val intent: PendingIntent?) : TokenResult()
    data class Failed(val message: String) : TokenResult()
}

/** Доступ к Google Диску. Приложение видит на Диске только файлы, которые создало само. */
object Auth {
    private const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE)))
            .build()

    suspend fun token(c: Context): TokenResult =
        try {
            val r: AuthorizationResult =
                Identity.getAuthorizationClient(c).authorize(request()).await()
            if (r.hasResolution()) {
                TokenResult.NeedsUser(r.pendingIntent)
            } else {
                val t = r.accessToken
                if (t != null) TokenResult.Ok(t) else TokenResult.Failed("Google не выдал доступ")
            }
        } catch (e: Exception) {
            TokenResult.Failed(e.message ?: e.javaClass.simpleName)
        }

    /** Сбрасывает просроченный токен, чтобы в следующий раз получить новый. */
    fun clear(c: Context, token: String) {
        try {
            GoogleAuthUtil.clearToken(c, token)
        } catch (e: Exception) {
            // не критично
        }
    }
}
