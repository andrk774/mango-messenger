package com.mango.backend.realtime

import com.mango.backend.common.ApiJson
import com.mango.backend.common.Redact
import com.mango.backend.sync.EventBus
import com.mango.backend.sync.StoredEvent
import com.mango.backend.sync.SyncEventDto
import com.mango.backend.sync.toDto
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Рассылка событий только внутри этого процесса.
 *
 * Корректна для установки из одного экземпляра — локальной разработки
 * и небольшого развёртывания. Для нескольких реплик нужен [RedisEventBus]:
 * иначе клиент, подключённый к другому экземпляру, не получит событие
 * в реальном времени (но всё равно догонит его по курсору).
 */
class LocalEventBus(private val hub: WebSocketHub) : EventBus {
    override suspend fun publish(events: List<StoredEvent>) {
        events.forEach { hub.deliver(it.userId, it.toDto()) }
    }
}

@Serializable
private data class EventEnvelope(
    /** Экземпляр-отправитель: по нему отбрасывается эхо собственных событий. */
    val origin: String,
    val userId: String,
    val event: SyncEventDto,
)

/**
 * Рассылка событий между экземплярами через Redis pub/sub.
 *
 * ## Что здесь гарантируется, а что нет
 *
 * Pub/sub в Redis — доставка «максимум один раз» и без сохранения:
 * если подписчика нет в момент публикации, сообщение теряется.
 * Это сознательно принято: источник истины — журнал событий в
 * PostgreSQL, а сокет лишь ускоряет доставку. Потеря рассылки
 * означает задержку до следующей сверки по курсору, а не потерю данных.
 *
 * Поэтому здесь нет ни подтверждений, ни повторов: их отсутствие —
 * не упущение, а следствие того, что надёжность обеспечена в другом месте.
 *
 * ## Подписка по требованию
 *
 * Экземпляр подписывается на канал пользователя, только когда у него
 * появляется соединение этого пользователя, и отписывается, когда
 * последнее уходит. Альтернатива — подписка по образцу на всех
 * пользователей — заставляла бы каждый экземпляр принимать события
 * всех остальных.
 */
class RedisEventBus(
    redisUrl: String,
    private val hub: WebSocketHub,
) : EventBus, AutoCloseable {

    private val log = LoggerFactory.getLogger(RedisEventBus::class.java)

    /** Идентификатор процесса: отличает свои события от чужих. */
    private val instanceId = UUID.randomUUID().toString()

    private val client: RedisClient = RedisClient.create(redisUrl)
    private val publisher: StatefulRedisPubSubConnection<String, String> = client.connectPubSub()
    private val subscriber: StatefulRedisPubSubConnection<String, String> = client.connectPubSub()

    init {
        subscriber.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                handleIncoming(channel, message)
            }
        })

        hub.onUserConnected = { userId ->
            runCatching { subscriber.async().subscribe(channelFor(userId)) }
                .onFailure { log.warn("не удалось подписаться на канал: {}", it.message) }
        }
        hub.onUserDisconnected = { userId ->
            runCatching { subscriber.async().unsubscribe(channelFor(userId)) }
                .onFailure { log.warn("не удалось отписаться от канала: {}", it.message) }
        }

        log.info("рассылка событий через Redis включена, экземпляр {}", Redact.id(instanceId))
    }

    override suspend fun publish(events: List<StoredEvent>) {
        events.forEach { stored ->
            val dto = stored.toDto()

            // Сначала локальная доставка: она мгновенна и не зависит
            // от доступности Redis.
            hub.deliver(stored.userId, dto)

            val envelope = EventEnvelope(
                origin = instanceId,
                userId = stored.userId.toString(),
                event = dto,
            )

            runCatching {
                publisher.async().publish(
                    channelFor(stored.userId),
                    ApiJson.encodeToString(envelope),
                )
            }.onFailure {
                // Недоступность Redis не должна валить отправку сообщения:
                // событие уже записано в журнал, получатели догонят его.
                log.warn("не удалось разослать событие {}: {}", stored.id, it.message)
            }
        }
    }

    private fun handleIncoming(channel: String, message: String) {
        runCatching {
            val envelope = ApiJson.decodeFromString<EventEnvelope>(message)

            // Собственные события уже доставлены локально.
            if (envelope.origin == instanceId) return

            hub.deliver(UUID.fromString(envelope.userId), envelope.event)
        }.onFailure {
            log.warn("не удалось разобрать событие из канала {}: {}", channel, it.message)
        }
    }

    override fun close() {
        runCatching { subscriber.close() }
        runCatching { publisher.close() }
        runCatching { client.shutdown() }
    }

    private companion object {
        fun channelFor(userId: UUID): String = "mango:events:$userId"
    }
}
