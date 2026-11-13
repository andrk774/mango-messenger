package com.mango.backend.notifications

import com.mango.backend.auth.AuthRepository
import com.mango.backend.common.AppException
import com.mango.backend.common.Database
import com.mango.backend.common.TimeSource
import com.mango.backend.common.query
import com.mango.backend.common.queryOne
import com.mango.backend.common.update
import com.mango.backend.common.uuid
import com.mango.backend.common.validate
import com.mango.backend.sync.EventBus
import com.mango.backend.sync.EventTypes
import com.mango.backend.sync.StoredEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.UUID

@Serializable
data class RegisterPushTokenRequest(val provider: String, val token: String)

data class PushTarget(val deviceId: UUID, val userId: UUID, val token: String)

data class DueReminder(val taskId: UUID, val recipients: List<UUID>)

class PushRepository {

    fun deviceOfSession(connection: Connection, sessionId: UUID): UUID? = connection.queryOne(
        "SELECT device_id FROM sessions WHERE id = ? AND revoked_at IS NULL AND device_id IS NOT NULL",
        sessionId,
    ) { it.uuid("device_id") }

    fun clearDeviceToken(connection: Connection, deviceId: UUID): Int =
        connection.update("UPDATE devices SET push_token = NULL WHERE id = ?", deviceId)

    /** Сбрасывает токен, только если он не сменился с момента отправки. */
    fun clearToken(connection: Connection, deviceId: UUID, token: String): Int =
        connection.update("UPDATE devices SET push_token = NULL WHERE id = ? AND push_token = ?", deviceId, token)

    /**
     * Куда отправлять push.
     *
     * Только устройства с живой сессией: после выхода, смены пароля или
     * отзыва сессии уведомления на устройство больше не идут — даже если
     * токен клиент не успел снять.
     */
    fun targets(connection: Connection, provider: String, userIds: Collection<UUID>, now: Instant): List<PushTarget> {
        if (userIds.isEmpty()) return emptyList()
        val placeholders = userIds.joinToString(",") { "?" }
        return connection.query(
            """
            SELECT d.id, d.user_id, d.push_token FROM devices d
            WHERE d.user_id IN ($placeholders)
              AND d.push_provider = ? AND d.push_token IS NOT NULL AND d.revoked_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM sessions s
                  WHERE s.device_id = d.id AND s.revoked_at IS NULL AND s.expires_at > ?
              )
            """.trimIndent(),
            *userIds.toTypedArray(), provider, now,
        ) { PushTarget(it.uuid("id"), it.uuid("user_id"), it.getString("push_token")) }
    }

    /**
     * Забирает наступившие напоминания, отмечая их отправленными.
     *
     * Отметка ставится той же операцией (`FOR UPDATE SKIP LOCKED`): два
     * экземпляра сервера не заберут одно напоминание. Цена — при сбое
     * отправки напоминание не повторится; потерянный push лучше двойного.
     */
    fun claimDueReminders(connection: Connection, now: Instant, limit: Int): List<DueReminder> {
        val claimed = connection.query(
            """
            UPDATE tasks SET reminded_at = ?
            WHERE id IN (
                SELECT id FROM tasks
                WHERE remind_at IS NOT NULL AND remind_at <= ? AND reminded_at IS NULL
                  AND deleted_at IS NULL AND status NOT IN ('DONE', 'CANCELLED')
                ORDER BY remind_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, created_by
            """.trimIndent(),
            now, now, limit,
        ) { it.uuid("id") to it.uuid("created_by") }
        if (claimed.isEmpty()) return emptyList()

        val placeholders = claimed.joinToString(",") { "?" }
        val assignees = connection.query(
            """
            SELECT task_id, user_id FROM task_assignees
            WHERE task_id IN ($placeholders) AND (removed_at IS NULL OR assigned_at > removed_at)
            """.trimIndent(),
            *claimed.map { it.first }.toTypedArray(),
        ) { it.uuid("task_id") to it.uuid("user_id") }.groupBy({ it.first }, { it.second })

        // Напоминание — исполнителям; нет исполнителей — автору.
        return claimed.map { (taskId, creator) -> DueReminder(taskId, assignees[taskId] ?: listOf(creator)) }
    }
}

/**
 * Push-уведомления.
 *
 * Отправка — после фиксации транзакции и не задерживая ответ API.
 * Push — подсказка «загляни на сервер», а не канал доставки: если он
 * потеряется, устройство всё равно получит изменения по журналу при
 * следующем открытии (ADR-0006).
 */
class PushService(
    private val database: Database,
    private val repository: PushRepository,
    private val authRepository: AuthRepository,
    private val provider: PushProvider,
    private val time: TimeSource,
) {
    private val log = LoggerFactory.getLogger(PushService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun register(sessionId: UUID, request: RegisterPushTokenRequest) {
        val provider = request.provider.uppercase()
        validate {
            check(provider in PROVIDERS, "provider", "Допустимо: ${PROVIDERS.joinToString()}")
            requireLength(request.token, "token", max = 4096, min = 1)
        }
        database.transaction { connection ->
            val device = repository.deviceOfSession(connection, sessionId)
                ?: throw AppException.Conflict("Сессия не связана с устройством — выполните вход заново")
            authRepository.setPushToken(connection, device, provider, request.token, time.now())
        }
    }

    /** Выход: устройство перестаёт получать push сразу, а не после истечения сессии. */
    suspend fun unregister(sessionId: UUID) {
        database.transaction { connection ->
            repository.deviceOfSession(connection, sessionId)?.let { repository.clearDeviceToken(connection, it) }
        }
    }

    /**
     * Push по событиям журнала.
     *
     * Сейчас — только новые сообщения: отправителю не уходит, системные
     * сообщения о составе группы не будят телефон.
     */
    suspend fun onEvents(events: List<StoredEvent>) {
        val messages = events.filter { it.type == EventTypes.MESSAGE_CREATED }
        if (messages.isEmpty()) return

        messages.groupBy { it.payloadJson }.forEach { (payload, rows) ->
            val message = runCatching { json.parseToJsonElement(payload).jsonObject.getValue("message").jsonObject }
                .getOrNull() ?: return@forEach
            val senderId = message["senderId"]?.jsonPrimitive?.content
            if (message["type"]?.jsonPrimitive?.content == "SYSTEM") return@forEach

            val recipients = rows.map { it.userId }.filter { it.toString() != senderId }.distinct()
            sendTo(
                recipients,
                PushMessage(
                    mapOf(
                        "type" to "message",
                        "conversationId" to message.getValue("conversationId").jsonPrimitive.content,
                        "messageId" to message.getValue("id").jsonPrimitive.content,
                    ),
                ),
            )
        }
    }

    /** Один проход планировщика напоминаний. Возвращает число отправленных напоминаний. */
    suspend fun sendDueReminders(limit: Int = 100): Int {
        val due = database.transaction { repository.claimDueReminders(it, time.now(), limit) }
        due.forEach { reminder ->
            sendTo(reminder.recipients, PushMessage(mapOf("type" to "task_reminder", "taskId" to reminder.taskId.toString())))
        }
        return due.size
    }

    private suspend fun sendTo(userIds: List<UUID>, message: PushMessage) {
        if (userIds.isEmpty()) return
        val targets = database.readOnly { repository.targets(it, provider.name, userIds, time.now()) }
        targets.forEach { target ->
            when (val result = provider.send(target.token, message)) {
                PushResult.Delivered -> Unit
                // Приложение удалено или данные очищены: токен больше не
                // нужен, и повторные попытки только тратили бы квоту.
                PushResult.InvalidToken -> database.transaction { repository.clearToken(it, target.deviceId, target.token) }
                is PushResult.Failed -> log.warn("push не доставлен: {}", result.reason)
            }
        }
    }

    companion object {
        private val PROVIDERS = setOf("FCM")
    }
}

/**
 * Шина событий с отправкой push.
 *
 * Обёртка над основной шиной: публикация вызывается ровно на том
 * экземпляре сервера, где произошло изменение, — значит и push уходит
 * один раз, сколько бы экземпляров ни было.
 */
class PushingEventBus(
    private val delegate: EventBus,
    private val push: PushService,
    private val scope: CoroutineScope,
) : EventBus {
    private val log = LoggerFactory.getLogger(PushingEventBus::class.java)

    override suspend fun publish(events: List<StoredEvent>) {
        delegate.publish(events)
        scope.launch {
            runCatching { push.onEvents(events) }.onFailure { log.warn("ошибка отправки push: {}", it.message) }
        }
    }
}

/** Периодическая проверка наступивших напоминаний. */
class ReminderScheduler(
    private val push: PushService,
    private val intervalMillis: Long = 30_000,
) {
    private val log = LoggerFactory.getLogger(ReminderScheduler::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                runCatching { push.sendDueReminders() }
                    .onSuccess { if (it > 0) log.info("отправлено напоминаний: {}", it) }
                    .onFailure { log.warn("ошибка планировщика напоминаний: {}", it.message) }
                delay(intervalMillis)
            }
        }
    }
}
