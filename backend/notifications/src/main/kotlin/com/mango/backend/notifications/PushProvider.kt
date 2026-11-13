package com.mango.backend.notifications

import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Push-сообщение.
 *
 * Только данные (data message), без заголовка и текста: по ADR-0010
 * содержимое переписки не проходит через инфраструктуру провайдера.
 * Устройство получает идентификаторы, само забирает изменения с сервера
 * и показывает уведомление из своих данных.
 */
data class PushMessage(val data: Map<String, String>)

sealed interface PushResult {
    data object Delivered : PushResult

    /** Токен больше не действителен (приложение удалено, данные очищены). */
    data object InvalidToken : PushResult

    data class Failed(val reason: String) : PushResult
}

/** Доставка push. Реализации — FCM и журнал для разработки (ADR-0010). */
interface PushProvider {
    /** Значение `devices.push_provider`, токены которого обслуживает реализация. */
    val name: String

    suspend fun send(token: String, message: PushMessage): PushResult
}

/** Без Firebase: push пишется в журнал. Работает и в тестах, и в разработке. */
class LoggingPushProvider(override val name: String = "FCM") : PushProvider {
    private val log = LoggerFactory.getLogger(LoggingPushProvider::class.java)

    override suspend fun send(token: String, message: PushMessage): PushResult {
        log.info("push (не отправлен — Firebase не настроен): {} для токена …{}", message.data["type"], token.takeLast(6))
        return PushResult.Delivered
    }
}

/** Ключ сервисного аккаунта Firebase — поля, которые нужны для отправки. */
@Serializable
data class ServiceAccount(
    val project_id: String,
    val client_email: String,
    val private_key: String,
    val private_key_id: String = "",
    val token_uri: String = "https://oauth2.googleapis.com/token",
)

/**
 * FCM HTTP v1.
 *
 * Два запроса: обмен подписанного ключом сервисного аккаунта JWT на
 * токен доступа OAuth (кешируется на час) и отправка сообщения.
 * Клиентская библиотека Google не используется: она тянет десятки
 * зависимостей ради этих двух запросов.
 */
class FcmPushProvider(
    private val account: ServiceAccount,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val fcmBaseUrl: String = "https://fcm.googleapis.com",
    private val clock: () -> Instant = Instant::now,
) : PushProvider {

    override val name: String = "FCM"

    private val log = LoggerFactory.getLogger(FcmPushProvider::class.java)
    private val key: PrivateKey = parsePrivateKey(account.private_key)

    @Volatile
    private var cached: Pair<String, Instant>? = null

    override suspend fun send(token: String, message: PushMessage): PushResult {
        val accessToken = try {
            accessToken()
        } catch (e: Exception) {
            log.warn("FCM: не получен токен доступа: {}", e.message)
            return PushResult.Failed("oauth")
        }

        val body = buildJsonObject {
            putJsonObject("message") {
                put("token", token)
                putJsonObject("data") { message.data.forEach { (k, v) -> put(k, v) } }
                putJsonObject("android") {
                    // Высокий приоритет — чтобы устройство в режиме Doze
                    // проснулось и показало уведомление о сообщении сразу.
                    put("priority", "high")
                    put("ttl", "86400s")
                }
            }
        }

        val request = HttpRequest.newBuilder(URI.create("$fcmBaseUrl/v1/projects/${account.project_id}/messages:send"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = try {
            http.sendAsyncAwait(request)
        } catch (e: Exception) {
            return PushResult.Failed("network: ${e.message}")
        }

        return when {
            response.statusCode() in 200..299 -> PushResult.Delivered
            isInvalidToken(response.statusCode(), response.body()) -> PushResult.InvalidToken
            response.statusCode() == 401 -> {
                cached = null
                PushResult.Failed("unauthorized")
            }
            else -> {
                log.warn("FCM: ответ {}", response.statusCode())
                PushResult.Failed("http ${response.statusCode()}")
            }
        }
    }

    /**
     * Токен «не зарегистрирован»: 404 UNREGISTERED либо 400 с указанием
     * на сам токен. Прочие 400 — ошибка в запросе, токен не виноват.
     */
    private fun isInvalidToken(status: Int, body: String): Boolean =
        status == 404 || (status == 400 && ("registration token" in body || "UNREGISTERED" in body))

    private suspend fun accessToken(): String {
        val now = clock()
        cached?.let { (token, expiresAt) -> if (now.isBefore(expiresAt.minusSeconds(60))) return token }

        val assertion = signedAssertion(now)
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(assertion, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create(account.token_uri))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = http.sendAsyncAwait(request)
        check(response.statusCode() == 200) { "token endpoint ответил ${response.statusCode()}" }

        val json = Json.parseToJsonElement(response.body()).jsonObject
        val token = json.getValue("access_token").jsonPrimitive.content
        val ttl = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
        cached = token to now.plusSeconds(ttl)
        return token
    }

    /** JWT RS256 для обмена на токен доступа (RFC 7523). */
    internal fun signedAssertion(now: Instant): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = buildJsonObject {
            put("alg", "RS256")
            put("typ", "JWT")
            if (account.private_key_id.isNotEmpty()) put("kid", account.private_key_id)
        }
        val claims = buildJsonObject {
            put("iss", account.client_email)
            put("scope", SCOPE)
            put("aud", account.token_uri)
            put("iat", now.epochSecond)
            put("exp", now.epochSecond + 3600)
        }
        val signingInput = encoder.encodeToString(header.toString().toByteArray()) + "." +
            encoder.encodeToString(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(key)
            update(signingInput.toByteArray())
        }.sign()
        return signingInput + "." + encoder.encodeToString(signature)
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

        private val json = Json { ignoreUnknownKeys = true }

        fun fromFile(path: Path): FcmPushProvider =
            FcmPushProvider(json.decodeFromString(ServiceAccount.serializer(), Files.readString(path)))

        fun parsePrivateKey(pem: String): PrivateKey {
            val base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .filterNot { it.isWhitespace() }
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
        }
    }
}

private suspend fun HttpClient.sendAsyncAwait(request: HttpRequest): HttpResponse<String> =
    sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
