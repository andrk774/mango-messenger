package com.mango.backend.app

import com.mango.backend.auth.CurrentUserResponse
import com.mango.backend.auth.TokenPairResponse
import com.mango.backend.common.ApiJson
import com.mango.backend.messaging.AddMembersRequest
import com.mango.backend.messaging.ChangeRoleRequest
import com.mango.backend.messaging.ConversationCreatedPayload
import com.mango.backend.messaging.ConversationDto
import com.mango.backend.messaging.ConversationsResponse
import com.mango.backend.messaging.CreateConversationRequest
import com.mango.backend.messaging.MemberDto
import com.mango.backend.messaging.MembersResponse
import com.mango.backend.messaging.MessagesResponse
import com.mango.backend.messaging.SendMessageRequest
import com.mango.backend.messaging.UpdateConversationRequest
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
import kotlin.test.assertTrue

/**
 * Группы: состав, роли, права и то, как изменения доходят до участников.
 */
class GroupsIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun cleanup() = resetDatabase()

    private class Person(val tokens: TokenPairResponse, val id: UUID)

    // ─────────────────────── карточка чата ───────────────────────

    @Test
    fun `каждый получает карточку нового чата со своей точки зрения`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")

        client.post("/v1/conversations") {
            bearerAuth(anna.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(boris.id)))
        }

        val view = client.conversationEvents(boris, "CONVERSATION_CREATED").single()
        // Раньше Борис получал карточку, собранную для Анны, — с собственным
        // именем в заголовке личного чата.
        assertEquals("anna", view.title, "Заголовок личного чата — имя собеседника")
    }

    @Test
    fun `участник группы не получает роль владельца из чужой карточки`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")

        client.group(anna, "Команда", boris)

        assertEquals("MEMBER", client.conversationEvents(boris, "CONVERSATION_CREATED").single().myRole)
        assertEquals("OWNER", client.conversationEvents(anna, "CONVERSATION_CREATED").single().myRole)
    }

    // ─────────────────────────── состав ───────────────────────────

    @Test
    fun `новый участник получает чат и видит системное сообщение`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val vera = client.person("vera")
        val group = client.group(anna, "Команда", boris)

        val response = client.addMembers(anna, group.id, vera.id)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(3, response.body<MembersResponse>().items.size)

        val veraChats: ConversationsResponse = client.get("/v1/conversations") {
            bearerAuth(vera.tokens.accessToken)
        }.body()
        assertEquals(listOf(group.id), veraChats.items.map { it.id })

        val history: MessagesResponse = client.get("/v1/conversations/${group.id}/messages") {
            bearerAuth(vera.tokens.accessToken)
        }.body()
        val system = history.items.single { it.type == "SYSTEM" }
        assertEquals("MEMBERS_ADDED", system.system?.kind)
        assertEquals(listOf(vera.id), system.system?.targets?.map { it.userId })

        assertTrue(client.eventTypes(vera).contains("CONVERSATION_CREATED"))
        assertTrue(client.eventTypes(boris).contains("CONVERSATION_MEMBER_ADDED"))
    }

    @Test
    fun `повторное добавление не создаёт второго системного сообщения`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val vera = client.person("vera")
        val group = client.group(anna, "Команда", boris)

        client.addMembers(anna, group.id, vera.id)
        client.addMembers(anna, group.id, vera.id)

        val history: MessagesResponse = client.get("/v1/conversations/${group.id}/messages") {
            bearerAuth(anna.tokens.accessToken)
        }.body()
        assertEquals(1, history.items.count { it.type == "SYSTEM" })
    }

    @Test
    fun `рядовой участник не может добавлять и удалять`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val vera = client.person("vera")
        val group = client.group(anna, "Команда", boris, vera)

        assertEquals(HttpStatusCode.Forbidden, client.addMembers(boris, group.id, client.person("gleb").id).status)
        assertEquals(HttpStatusCode.Forbidden, client.removeMember(boris, group.id, vera.id).status)
    }

    @Test
    fun `администратор удаляет участника, но не владельца и не другого администратора`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val vera = client.person("vera")
        val gleb = client.person("gleb")
        val group = client.group(anna, "Команда", boris, vera, gleb)

        client.changeRole(anna, group.id, boris.id, "ADMIN")
        client.changeRole(anna, group.id, vera.id, "ADMIN")

        assertEquals(HttpStatusCode.Forbidden, client.removeMember(boris, group.id, anna.id).status)
        assertEquals(HttpStatusCode.Forbidden, client.removeMember(boris, group.id, vera.id).status)
        assertEquals(HttpStatusCode.NoContent, client.removeMember(boris, group.id, gleb.id).status)
    }

    @Test
    fun `удалённый участник теряет доступ и узнаёт об удалении`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val group = client.group(anna, "Команда", boris)

        assertEquals(HttpStatusCode.NoContent, client.removeMember(anna, group.id, boris.id).status)

        val history = client.get("/v1/conversations/${group.id}/messages") { bearerAuth(boris.tokens.accessToken) }
        assertEquals(HttpStatusCode.Forbidden, history.status)

        val send = client.post("/v1/conversations/${group.id}/messages") {
            bearerAuth(boris.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = "after-removal", body = "Я ещё здесь?"))
        }
        assertEquals(HttpStatusCode.Forbidden, send.status)

        // Без этого события чат навсегда остался бы в его списке.
        assertTrue(client.eventTypes(boris).contains("CONVERSATION_MEMBER_REMOVED"))

        // Повтор удаления — успех без последствий.
        assertEquals(HttpStatusCode.NoContent, client.removeMember(anna, group.id, boris.id).status)
    }

    @Test
    fun `уход владельца передаёт владение давнему администратору`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val vera = client.person("vera")
        val group = client.group(anna, "Команда", boris, vera)
        client.changeRole(anna, group.id, vera.id, "ADMIN")

        assertEquals(HttpStatusCode.NoContent, client.removeMember(anna, group.id, anna.id).status)

        val roles = client.members(boris, group.id).associate { it.userId to it.role }
        assertEquals(mapOf(boris.id to "MEMBER", vera.id to "OWNER"), roles)

        val last = client.get("/v1/conversations/${group.id}/messages?limit=1") {
            bearerAuth(boris.tokens.accessToken)
        }.body<MessagesResponse>().items.single()
        assertEquals("MEMBER_LEFT", last.system?.kind)
        assertEquals("OWNER", last.system?.role)
        assertEquals(listOf(vera.id), last.system?.targets?.map { it.userId })
    }

    @Test
    fun `последний ушедший удаляет группу`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val group = client.group(anna, "Команда", boris)

        client.removeMember(anna, group.id, anna.id)
        client.removeMember(boris, group.id, boris.id)

        val chats: ConversationsResponse = client.get("/v1/conversations") {
            bearerAuth(boris.tokens.accessToken)
        }.body()
        assertTrue(chats.items.isEmpty())
    }

    @Test
    fun `одновременное добавление не превышает предел участников`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val group = client.group(anna, "Команда", boris)
        val candidates = (1..4).map { client.person("cand$it") }

        // Все запросы идут параллельно; каждый добавляет одного и того же
        // набора людей. Блокировка чата обязана упорядочить их так,
        // чтобы итог был одинаков и без дубликатов.
        coroutineScope {
            candidates.map { candidate ->
                async { client.addMembers(anna, group.id, *candidates.map { it.id }.toTypedArray()) }
            }.awaitAll()
        }

        val members = client.members(anna, group.id)
        assertEquals(6, members.size)
        assertEquals(members.size, members.map { it.userId }.toSet().size)
        val history: MessagesResponse = client.get("/v1/conversations/${group.id}/messages") {
            bearerAuth(anna.tokens.accessToken)
        }.body()
        assertEquals(1, history.items.count { it.system?.kind == "MEMBERS_ADDED" })
    }

    @Test
    fun `состав личного чата не меняется`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val direct: ConversationDto = client.post("/v1/conversations") {
            bearerAuth(anna.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(boris.id)))
        }.body()

        assertEquals(HttpStatusCode.BadRequest, client.addMembers(anna, direct.id, client.person("vera").id).status)
        assertEquals(HttpStatusCode.BadRequest, client.removeMember(anna, direct.id, anna.id).status)
    }

    @Test
    fun `посторонний не может управлять чужой группой`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val stranger = client.person("stranger")
        val group = client.group(anna, "Команда", boris)

        assertEquals(HttpStatusCode.Forbidden, client.addMembers(stranger, group.id, client.person("vera").id).status)
        assertEquals(HttpStatusCode.Forbidden, client.rename(stranger, group.id, "Захвачено").status)
    }

    // ─────────────────────── роли и название ───────────────────────

    @Test
    fun `передача владения делает прежнего владельца администратором`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val group = client.group(anna, "Команда", boris)

        assertEquals(HttpStatusCode.OK, client.changeRole(anna, group.id, boris.id, "OWNER").status)

        val roles = client.members(anna, group.id).associate { it.userId to it.role }
        assertEquals(mapOf(anna.id to "ADMIN", boris.id to "OWNER"), roles)
        // Бывший владелец больше не назначает роли.
        assertEquals(HttpStatusCode.Forbidden, client.changeRole(anna, group.id, boris.id, "MEMBER").status)
    }

    @Test
    fun `роли назначает только владелец`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val vera = client.person("vera")
        val group = client.group(anna, "Команда", boris, vera)
        client.changeRole(anna, group.id, boris.id, "ADMIN")

        assertEquals(HttpStatusCode.Forbidden, client.changeRole(boris, group.id, vera.id, "ADMIN").status)
    }

    @Test
    fun `переименование видно всем и попадает в историю`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val group = client.group(anna, "Команда", boris)

        assertEquals(HttpStatusCode.OK, client.rename(anna, group.id, "  Новая команда  ").status)

        val updated = client.conversationEvents(boris, "CONVERSATION_UPDATED").last()
        assertEquals("Новая команда", updated.title)
        assertEquals("MEMBER", updated.myRole, "Карточка Бориса — с его ролью")

        val last = client.get("/v1/conversations/${group.id}/messages?limit=1") {
            bearerAuth(boris.tokens.accessToken)
        }.body<MessagesResponse>().items.single()
        assertEquals("TITLE_CHANGED", last.system?.kind)
        assertEquals("Новая команда", last.system?.title)
    }

    @Test
    fun `системные сообщения не считаются непрочитанными`() = withApp { client ->
        val anna = client.person("anna")
        val boris = client.person("boris")
        val group = client.group(anna, "Команда", boris)
        client.rename(anna, group.id, "Другое название")

        val chats: ConversationsResponse = client.get("/v1/conversations") {
            bearerAuth(boris.tokens.accessToken)
        }.body()
        assertEquals(0, chats.items.single().unreadCount)
        assertNotNull(chats.items.single().lastMessage?.system, "Превью показывает системное событие")
    }

    // ──────────────────────── вспомогательное ────────────────────────

    private suspend fun HttpClient.person(name: String): Person {
        val tokens = newUser("$name@example.com", name)
        val me: CurrentUserResponse = get("/v1/users/me") { bearerAuth(tokens.accessToken) }.body()
        return Person(tokens, me.id)
    }

    private suspend fun HttpClient.group(owner: Person, title: String, vararg members: Person): ConversationDto =
        post("/v1/conversations") {
            bearerAuth(owner.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(type = "GROUP", memberIds = members.map { it.id }, title = title))
        }.body()

    private suspend fun HttpClient.addMembers(actor: Person, conversationId: UUID, vararg ids: UUID): HttpResponse =
        post("/v1/conversations/$conversationId/members") {
            bearerAuth(actor.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(AddMembersRequest(ids.toList()))
        }

    private suspend fun HttpClient.removeMember(actor: Person, conversationId: UUID, userId: UUID): HttpResponse =
        delete("/v1/conversations/$conversationId/members/$userId") { bearerAuth(actor.tokens.accessToken) }

    private suspend fun HttpClient.changeRole(
        actor: Person,
        conversationId: UUID,
        userId: UUID,
        role: String,
    ): HttpResponse = put("/v1/conversations/$conversationId/members/$userId/role") {
        bearerAuth(actor.tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(ChangeRoleRequest(role))
    }

    private suspend fun HttpClient.rename(actor: Person, conversationId: UUID, title: String): HttpResponse =
        patch("/v1/conversations/$conversationId") {
            bearerAuth(actor.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateConversationRequest(title))
        }

    private suspend fun HttpClient.members(actor: Person, conversationId: UUID): List<MemberDto> =
        get("/v1/conversations/$conversationId/members") { bearerAuth(actor.tokens.accessToken) }
            .body<MembersResponse>().items

    private suspend fun HttpClient.events(actor: Person): SyncResponse =
        get("/v1/sync?since=0") { bearerAuth(actor.tokens.accessToken) }.body()

    private suspend fun HttpClient.eventTypes(actor: Person): List<String> = events(actor).events.map { it.type }

    private suspend fun HttpClient.conversationEvents(actor: Person, type: String): List<ConversationDto> =
        events(actor).events.filter { it.type == type }.map {
            ApiJson.decodeFromJsonElement(ConversationCreatedPayload.serializer(), it.payload).conversation
        }
}
