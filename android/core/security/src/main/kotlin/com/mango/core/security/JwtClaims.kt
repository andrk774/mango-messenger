package com.mango.core.security

import android.util.Base64
import org.json.JSONObject

/**
 * Чтение утверждений из access token.
 *
 * Подпись здесь не проверяется и не может быть проверена: ключ есть
 * только у сервера. Значение пригодно как идентификатор владельца
 * сессии, потому что токен получен от нашего сервера по TLS и хранится
 * зашифрованным. Для решения о праве доступа оно не используется — это
 * делает сервер по подписи.
 */
object JwtClaims {

    /** Идентификатор пользователя (`sub`) или `null`, если токен не разобрать. */
    fun subject(token: String): String? = runCatching {
        val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        JSONObject(String(bytes, Charsets.UTF_8)).optString("sub").ifBlank { null }
    }.getOrNull()
}
