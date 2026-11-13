package com.mango.backend.notifications

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FCM HTTP v1 против локального сервера, изображающего Google.
 *
 * Проверяется то, что легко сломать незаметно: подпись JWT, кеширование
 * токена доступа и распознавание «токен больше не действителен».
 */
class FcmPushProviderTest {

    private lateinit var server: HttpServer
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val tokenRequests = CopyOnWriteArrayList<String>()
    private val sendRequests = CopyOnWriteArrayList<Pair<String, String>>()
    private var sendStatus = 200
    private var sendBody = """{"name":"projects/p/messages/1"}"""

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            tokenRequests += exchange.requestBody.readBytes().decodeToString()
            val body = """{"access_token":"access-1","expires_in":3600,"token_type":"Bearer"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/projects/test-project/messages:send") { exchange ->
            sendRequests += exchange.requestHeaders.getFirst("Authorization") to
                exchange.requestBody.readBytes().decodeToString()
            val body = sendBody.toByteArray()
            exchange.sendResponseHeaders(sendStatus, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun provider(): FcmPushProvider {
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder().encodeToString(keys.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val base = "http://127.0.0.1:${server.address.port}"
        return FcmPushProvider(
            account = ServiceAccount(
                project_id = "test-project",
                client_email = "push@test-project.iam.gserviceaccount.com",
                private_key = pem,
                private_key_id = "key-1",
                token_uri = "$base/token",
            ),
            fcmBaseUrl = base,
            clock = { Instant.parse("2026-11-13T10:00:00Z") },
        )
    }

    @Test
    fun `сообщение уходит с токеном доступа и только с данными`() = runTest {
        val result = provider().send("device-token", PushMessage(mapOf("type" to "message", "conversationId" to "c1")))

        assertEquals(PushResult.Delivered, result)
        val (auth, body) = sendRequests.single()
        assertEquals("Bearer access-1", auth)
        val message = Json.parseToJsonElement(body).jsonObject.getValue("message").jsonObject
        assertEquals("device-token", message.getValue("token").jsonPrimitive.content)
        assertEquals("c1", message.getValue("data").jsonObject.getValue("conversationId").jsonPrimitive.content)
        assertTrue("notification" !in message, "Заголовок и текст не передаются провайдеру (ADR-0010)")
        assertEquals("high", message.getValue("android").jsonObject.getValue("priority").jsonPrimitive.content)
    }

    @Test
    fun `JWT подписан ключом сервисного аккаунта и адресован token_uri`() = runTest {
        provider().send("t", PushMessage(mapOf("type" to "x")))

        val form = tokenRequests.single().split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])

        val (header, claims, signature) = form.getValue("assertion").split(".")
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keys.public as RSAPublicKey)
            update("$header.$claims".toByteArray())
        }
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)), "Подпись не сходится с открытым ключом")

        val decoded = Json.parseToJsonElement(Base64.getUrlDecoder().decode(claims).decodeToString()).jsonObject
        assertEquals("push@test-project.iam.gserviceaccount.com", decoded.getValue("iss").jsonPrimitive.content)
        assertEquals(FcmPushProvider.SCOPE, decoded.getValue("scope").jsonPrimitive.content)
        assertTrue(decoded.getValue("aud").jsonPrimitive.content.endsWith("/token"))
    }

    @Test
    fun `токен доступа запрашивается один раз на час, а не на каждое сообщение`() = runTest {
        val provider = provider()
        repeat(3) { provider.send("t$it", PushMessage(mapOf("type" to "x"))) }

        assertEquals(1, tokenRequests.size)
        assertEquals(3, sendRequests.size)
    }

    @Test
    fun `удалённое приложение распознаётся как недействительный токен`() = runTest {
        sendStatus = 404
        sendBody = """{"error":{"code":404,"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}"""

        assertEquals(PushResult.InvalidToken, provider().send("gone", PushMessage(mapOf("type" to "x"))))
    }

    @Test
    fun `перегрузка FCM — сбой, а не повод снять токен`() = runTest {
        sendStatus = 503
        sendBody = """{"error":{"code":503,"status":"UNAVAILABLE"}}"""

        val result = provider().send("ok-token", PushMessage(mapOf("type" to "x")))
        assertTrue(result is PushResult.Failed)
    }

    @Test
    fun `ключ из JSON с экранированными переводами строк читается`() {
        val pem = "-----BEGIN PRIVATE KEY-----\\n" +
            Base64.getEncoder().encodeToString(keys.private.encoded).chunked(64).joinToString("\\n") +
            "\\n-----END PRIVATE KEY-----\\n"
        assertEquals(keys.private, FcmPushProvider.parsePrivateKey(pem))
    }
}
