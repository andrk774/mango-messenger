package com.mango.backend.app

import com.mango.backend.auth.TokenPairResponse
import com.mango.backend.messaging.ConversationDto
import com.mango.backend.messaging.ConversationsResponse
import com.mango.backend.messaging.CreateConversationRequest
import com.mango.backend.messaging.EditMessageRequest
import com.mango.backend.messaging.MarkReadRequest
import com.mango.backend.messaging.MembersResponse
import com.mango.backend.messaging.MessageDto
import com.mango.backend.messaging.MessagesResponse
import com.mango.backend.messaging.SendMessageRequest
import com.mango.backend.messaging.UserSearchResponse
import com.mango.backend.sync.SyncResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сквозные проверки переписки.
 *
 * Проверяются инварианты, на которых держится синхронизация: порядок
 * сообщений, идемпотентность отправки, изоляция чужих чатов и попадание
 * каждого изменения в журнал событий.
 */
class MessagingIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun cleanup() = resetDatabase()

    // ──────────────────────────── чаты ────────────────────────────

    @Test
    fun `личный чат не создаётся дважды`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val borisId = client.me(boris).id

        val first: ConversationDto = client.createDirect(anna, borisId).body()
        val second: ConversationDto = client.createDirect(anna, borisId).body()

        assertEquals(
            first.id,
            second.id,
            "Повторное открытие переписки обязано вернуть тот же чат",
        )

        // И со стороны собеседника это тот же чат, а не его копия.
        val borisList: ConversationsResponse = client.conversations(boris).body()
        assertEquals(listOf(first.id), borisList.items.map { it.id })
    }

    @Test
    fun `название личного чата подставляется из имени собеседника`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna", displayName = "Анна Петрова")
        val boris = client.newUser("boris@example.com", "boris", displayName = "Борис Иванов")

        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        assertEquals("Борис Иванов", conversation.title)

        val forBoris: ConversationsResponse = client.conversations(boris).body()
        assertEquals("Анна Петрова", forBoris.items.single().title)
    }

    @Test
    fun `групповому чату обязательно название`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val borisId = client.me(client.newUser("boris@example.com", "boris")).id

        val response = client.post("/v1/conversations") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(type = "GROUP", memberIds = listOf(borisId)))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `создатель группы становится владельцем`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val borisId = client.me(client.newUser("boris@example.com", "boris")).id

        val group: ConversationDto = client.post("/v1/conversations") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                CreateConversationRequest(
                    type = "GROUP",
                    memberIds = listOf(borisId),
                    title = "Рабочая группа",
                ),
            )
        }.body()

        assertEquals("OWNER", group.myRole)
        assertEquals(2, group.memberCount)

        val members: MembersResponse =
            client.get("/v1/conversations/${group.id}/members") {
                bearerAuth(anna.accessToken)
            }.body()

        assertEquals(setOf("OWNER", "MEMBER"), members.items.map { it.role }.toSet())
    }

    // ──────────────────────── отправка ────────────────────────

    @Test
    fun `сообщение доходит до собеседника и получает первый номер`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        val sent = client.send(anna, conversation.id, "op-1", "Привет!")
        assertEquals(HttpStatusCode.Created, sent.status)

        val message: MessageDto = sent.body()
        assertEquals(1L, message.seq, "Первое сообщение в чате получает номер 1")
        assertEquals("Привет!", message.body)

        val history: MessagesResponse = client.history(boris, conversation.id).body()
        assertEquals(listOf("Привет!"), history.items.map { it.body })
    }

    @Test
    fun `повторная отправка с тем же идентификатором не создаёт второе сообщение`() =
        withApp { client ->
            val anna = client.newUser("anna@example.com", "anna")
            val boris = client.newUser("boris@example.com", "boris")
            val conversation: ConversationDto =
                client.createDirect(anna, client.me(boris).id).body()

            val first = client.send(anna, conversation.id, "op-1", "Единственное")
            val second = client.send(anna, conversation.id, "op-1", "Единственное")

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(
                HttpStatusCode.OK,
                second.status,
                "Повтор уже выполненной операции — не создание",
            )

            val firstMessage: MessageDto = first.body()
            val secondMessage: MessageDto = second.body()
            assertEquals(firstMessage.id, secondMessage.id)
            assertEquals(firstMessage.seq, secondMessage.seq)

            val history: MessagesResponse = client.history(anna, conversation.id).body()
            assertEquals(1, history.items.size, "В чате должно остаться одно сообщение")
        }

    @Test
    fun `номера сообщений идут подряд при одновременной отправке`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        // Одновременные отправки сериализуются блокировкой строки чата.
        // Без неё два запроса получили бы одинаковый номер, и один из них
        // отвалился бы на уникальном индексе.
        val concurrent = 12
        coroutineScope {
            (1..concurrent).map { index ->
                async { client.send(anna, conversation.id, "op-$index", "Сообщение $index") }
            }.awaitAll()
        }

        val history: MessagesResponse =
            client.history(anna, conversation.id, limit = 100).body()

        val numbers = history.items.map { it.seq }.sorted()
        assertEquals(
            (1L..concurrent.toLong()).toList(),
            numbers,
            "Номера обязаны идти подряд без пропусков и повторов",
        )
    }

    @Test
    fun `пустое текстовое сообщение отклоняется`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        val response = client.send(anna, conversation.id, "op-1", "   ")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `ответ на сообщение из другого чата отклоняется`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val clara = client.newUser("clara@example.com", "clara")

        val withBoris: ConversationDto = client.createDirect(anna, client.me(boris).id).body()
        val withClara: ConversationDto = client.createDirect(anna, client.me(clara).id).body()

        val inBoris: MessageDto = client.send(anna, withBoris.id, "op-1", "Тут").body()

        val response = client.post("/v1/conversations/${withClara.id}/messages") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                SendMessageRequest(clientMsgId = "op-2", body = "Ответ", replyToId = inBoris.id),
            )
        }

        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            response.status,
            "Иначе текст чужого чата утёк бы через снимок ответа",
        )
    }

    @Test
    fun `ответ сохраняет снимок исходного сообщения`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna", displayName = "Анна")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        val original: MessageDto = client.send(anna, conversation.id, "op-1", "Исходное").body()

        val reply: MessageDto = client.post("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(boris.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = "op-2", body = "Ответ", replyToId = original.id))
        }.body()

        val reference = assertNotNull(reply.replyTo)
        assertEquals(original.id, reference.messageId)
        assertEquals("Исходное", reference.textSnapshot)
        assertEquals("Анна", reference.senderName)
        assertTrue(!reference.isUnavailable)
    }

    // ──────────────────────── изоляция чатов ────────────────────────

    @Test
    fun `посторонний не читает и не пишет в чужой чат`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val stranger = client.newUser("stranger@example.com", "stranger")

        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()
        client.send(anna, conversation.id, "op-1", "Приватное")

        assertEquals(
            HttpStatusCode.Forbidden,
            client.history(stranger, conversation.id).status,
            "Доступ закрывается проверкой участия, а не непредсказуемостью идентификатора",
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.send(stranger, conversation.id, "op-x", "Я здесь").status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/conversations/${conversation.id}") {
                bearerAuth(stranger.accessToken)
            }.status,
        )
    }

    @Test
    fun `несуществующий чат и чужой чат отвечают одинаково`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val stranger = client.newUser("stranger@example.com", "stranger")

        val real: ConversationDto = client.createDirect(anna, client.me(boris).id).body()

        val foreign = client.get("/v1/conversations/${real.id}") {
            bearerAuth(stranger.accessToken)
        }
        val absent = client.get("/v1/conversations/${UUID.randomUUID()}") {
            bearerAuth(stranger.accessToken)
        }

        assertEquals(
            foreign.status,
            absent.status,
            "Различие ответов позволяло бы перебором выяснять, какие чаты существуют",
        )
    }

    // ──────────────────── изменение и удаление ────────────────────

    @Test
    fun `автор правит своё сообщение, остальные - нет`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        val message: MessageDto = client.send(anna, conversation.id, "op-1", "Было").body()

        val byAuthor = client.patch("/v1/messages/${message.id}") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(EditMessageRequest("Стало"))
        }
        assertEquals(HttpStatusCode.OK, byAuthor.status)
        val edited: MessageDto = byAuthor.body()
        assertEquals("Стало", edited.body)
        assertNotNull(edited.editedAt)

        val byOther = client.patch("/v1/messages/${message.id}") {
            bearerAuth(boris.accessToken)
            contentType(ContentType.Application.Json)
            setBody(EditMessageRequest("Подмена"))
        }
        assertEquals(
            HttpStatusCode.Forbidden,
            byOther.status,
            "Подмена чужих слов — не модерация",
        )
    }

    @Test
    fun `удаление стирает текст, но сохраняет место в нумерации`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        val first: MessageDto = client.send(anna, conversation.id, "op-1", "Первое").body()
        client.send(anna, conversation.id, "op-2", "Второе")

        val deleted = client.delete("/v1/messages/${first.id}") { bearerAuth(anna.accessToken) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        val history: MessagesResponse = client.history(boris, conversation.id).body()
        val stored = history.items.single { it.id == first.id }

        assertNull(stored.body, "Текст должен быть стёрт")
        assertNotNull(stored.deletedAt)
        assertEquals(
            1L,
            stored.seq,
            "Номер обязан остаться занятым, иначе клиент решит, что пропустил сообщение",
        )
        assertEquals(listOf(2L, 1L), history.items.map { it.seq })
    }

    // ──────────────────── прочтение и список ────────────────────

    @Test
    fun `непрочитанные считаются и обнуляются отметкой`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        client.send(anna, conversation.id, "op-1", "Раз")
        client.send(anna, conversation.id, "op-2", "Два")

        val before: ConversationsResponse = client.conversations(boris).body()
        assertEquals(2, before.items.single().unreadCount)
        assertEquals("Два", before.items.single().lastMessage?.body)

        // Свои сообщения непрочитанными не считаются.
        val forAuthor: ConversationsResponse = client.conversations(anna).body()
        assertEquals(0, forAuthor.items.single().unreadCount)

        client.put("/v1/conversations/${conversation.id}/read") {
            bearerAuth(boris.accessToken)
            contentType(ContentType.Application.Json)
            setBody(MarkReadRequest(seq = 2))
        }

        val after: ConversationsResponse = client.conversations(boris).body()
        assertEquals(0, after.items.single().unreadCount)
    }

    @Test
    fun `отметка о прочтении не откатывается назад`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        repeat(3) { client.send(anna, conversation.id, "op-$it", "Сообщение $it") }

        client.markRead(boris, conversation.id, 3)
        // Запоздавший запрос с меньшим номером не должен возвращать
        // непрочитанные обратно.
        client.markRead(boris, conversation.id, 1)

        val list: ConversationsResponse = client.conversations(boris).body()
        assertEquals(0, list.items.single().unreadCount)
        assertEquals(3L, list.items.single().lastReadSeq)
    }

    @Test
    fun `история листается по номерам назад`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        repeat(10) { index -> client.send(anna, conversation.id, "op-$index", "№$index") }

        val firstPage: MessagesResponse = client.history(anna, conversation.id, limit = 4).body()
        assertEquals(listOf(10L, 9L, 8L, 7L), firstPage.items.map { it.seq })

        val secondPage: MessagesResponse =
            client.history(anna, conversation.id, limit = 4, beforeSeq = 7).body()
        assertEquals(listOf(6L, 5L, 4L, 3L), secondPage.items.map { it.seq })

        // Догрузка вперёд — заполнение разрыва в истории клиента.
        val gap: MessagesResponse =
            client.history(anna, conversation.id, limit = 3, afterSeq = 2).body()
        assertEquals(listOf(3L, 4L, 5L), gap.items.map { it.seq })
    }

    // ──────────────────────── журнал событий ────────────────────────

    @Test
    fun `каждое изменение попадает в журнал получателя`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()

        client.send(anna, conversation.id, "op-1", "Привет")

        val events: SyncResponse = client.get("/v1/sync?since=0") {
            bearerAuth(boris.accessToken)
        }.body()

        val types = events.events.map { it.type }
        assertTrue("CONVERSATION_CREATED" in types, "получено: $types")
        assertTrue("MESSAGE_CREATED" in types, "получено: $types")
        assertTrue(events.lastEventId > 0)
        assertEquals(
            events.events.map { it.id }.sorted(),
            events.events.map { it.id },
            "События обязаны идти по возрастанию идентификатора",
        )
    }

    @Test
    fun `курсор журнала из списка чатов согласован с догонкой`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()
        client.send(anna, conversation.id, "op-1", "Первое")

        val bootstrap: ConversationsResponse = client.conversations(boris).body()

        // После первичной загрузки догонка с выданным курсором обязана
        // быть пустой: иначе между загрузкой и подпиской терялись бы события.
        val immediately: SyncResponse =
            client.get("/v1/sync?since=${bootstrap.lastEventId}") {
                bearerAuth(boris.accessToken)
            }.body()
        assertTrue(immediately.events.isEmpty(), "получено: ${immediately.events.map { it.type }}")

        client.send(anna, conversation.id, "op-2", "Второе")

        val afterNew: SyncResponse =
            client.get("/v1/sync?since=${bootstrap.lastEventId}") {
                bearerAuth(boris.accessToken)
            }.body()
        assertEquals(listOf("MESSAGE_CREATED"), afterNew.events.map { it.type })
    }

    @Test
    fun `посторонний не видит чужих событий`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val stranger = client.newUser("stranger@example.com", "stranger")

        val conversation: ConversationDto =
            client.createDirect(anna, client.me(boris).id).body()
        client.send(anna, conversation.id, "op-1", "Приватное")

        val events: SyncResponse = client.get("/v1/sync?since=0") {
            bearerAuth(stranger.accessToken)
        }.body()

        assertTrue(events.events.isEmpty(), "получено: ${events.events.map { it.type }}")
    }

    // ──────────────────────── поиск людей ────────────────────────

    @Test
    fun `поиск находит по имени и не показывает самого себя`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna", displayName = "Анна Петрова")
        client.newUser("boris@example.com", "boris", displayName = "Борис Петров")

        val found: UserSearchResponse = client.get("/v1/users/search?q=Петров") {
            bearerAuth(anna.accessToken)
        }.body()

        assertEquals(listOf("boris"), found.items.map { it.username })
    }

    @Test
    fun `слишком короткий запрос поиска отклоняется`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")

        val response = client.get("/v1/users/search?q=a") { bearerAuth(anna.accessToken) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──────────────────────── вспомогательное ────────────────────────

    private suspend fun HttpClient.me(tokens: TokenPairResponse) =
        get("/v1/users/me") { bearerAuth(tokens.accessToken) }
            .body<com.mango.backend.auth.CurrentUserResponse>()

    private suspend fun HttpClient.createDirect(
        tokens: TokenPairResponse,
        otherId: UUID,
    ): HttpResponse = post("/v1/conversations") {
        bearerAuth(tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(otherId)))
    }

    private suspend fun HttpClient.conversations(tokens: TokenPairResponse): HttpResponse =
        get("/v1/conversations") { bearerAuth(tokens.accessToken) }

    private suspend fun HttpClient.send(
        tokens: TokenPairResponse,
        conversationId: UUID,
        clientMsgId: String,
        body: String,
    ): HttpResponse = post("/v1/conversations/$conversationId/messages") {
        bearerAuth(tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(SendMessageRequest(clientMsgId = clientMsgId, body = body))
    }

    private suspend fun HttpClient.history(
        tokens: TokenPairResponse,
        conversationId: UUID,
        limit: Int = 50,
        beforeSeq: Long? = null,
        afterSeq: Long? = null,
    ): HttpResponse {
        val params = buildString {
            append("?limit=").append(limit)
            if (beforeSeq != null) append("&beforeSeq=").append(beforeSeq)
            if (afterSeq != null) append("&afterSeq=").append(afterSeq)
        }
        return get("/v1/conversations/$conversationId/messages$params") {
            bearerAuth(tokens.accessToken)
        }
    }

    private suspend fun HttpClient.markRead(
        tokens: TokenPairResponse,
        conversationId: UUID,
        seq: Long,
    ) = put("/v1/conversations/$conversationId/read") {
        bearerAuth(tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(MarkReadRequest(seq))
    }
}
