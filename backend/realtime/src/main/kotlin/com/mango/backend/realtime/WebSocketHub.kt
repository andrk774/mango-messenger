package com.mango.backend.realtime

import com.mango.backend.common.Redact
import com.mango.backend.sync.SyncEventDto
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Подключение одного клиента.
 *
 * События складываются в канал, а не отправляются напрямую: отправка
 * блокируется медленным клиентом, и запись «изнутри» рассылки
 * задержала бы всех остальных получателей.
 */
class ClientSession(
    val sessionId: UUID,
    val userId: UUID,
) {
    /**
     * Очередь исходящих событий.
     *
     * Ограниченная: безразмерная очередь при медленном клиенте растёт,
     * пока не съест память сервера. При переполнении соединение
     * разрывается — клиент переподключится и догонит пропущенное по
     * курсору, что безопаснее исчерпания памяти.
     */
    val outgoing = Channel<SyncEventDto>(capacity = OUTGOING_CAPACITY)

    @Volatile
    var lastAckedEventId: Long = 0

    companion object {
        const val OUTGOING_CAPACITY = 256
    }
}

/**
 * Реестр подключений этого экземпляра приложения.
 *
 * Хаб знает только про сокеты, открытые к ЭТОМУ процессу. Доставка
 * между экземплярами — задача [RedisEventBus]; разделение намеренное,
 * чтобы хаб оставался проверяемым без внешних зависимостей.
 */
class WebSocketHub {

    private val log = LoggerFactory.getLogger(WebSocketHub::class.java)

    private val sessionsByUser = ConcurrentHashMap<UUID, MutableSet<ClientSession>>()

    /** Вызывается при появлении первого подключения пользователя на этом экземпляре. */
    var onUserConnected: ((UUID) -> Unit)? = null

    /** Вызывается, когда ушло последнее подключение пользователя. */
    var onUserDisconnected: ((UUID) -> Unit)? = null

    fun register(session: ClientSession) {
        val firstForUser = sessionsByUser.compute(session.userId) { _, existing ->
            val set = existing ?: ConcurrentHashMap.newKeySet()
            set.add(session)
            set
        }!!.size == 1

        if (firstForUser) onUserConnected?.invoke(session.userId)

        log.debug(
            "подключение {} пользователя {}",
            Redact.id(session.sessionId), Redact.id(session.userId),
        )
    }

    fun unregister(session: ClientSession) {
        var noneLeft = false
        sessionsByUser.compute(session.userId) { _, existing ->
            existing?.remove(session)
            if (existing == null || existing.isEmpty()) {
                noneLeft = true
                null
            } else {
                existing
            }
        }
        session.outgoing.close()
        if (noneLeft) onUserDisconnected?.invoke(session.userId)
    }

    /**
     * Доставка события всем подключениям пользователя на этом экземпляре.
     *
     * Один человек может быть подключён с нескольких устройств — событие
     * получают все, включая устройство-отправитель: иначе второй телефон
     * того же пользователя не узнал бы о сообщении, отправленном с первого.
     */
    fun deliver(userId: UUID, event: SyncEventDto) {
        val sessions = sessionsByUser[userId] ?: return
        sessions.forEach { session ->
            val result = session.outgoing.trySend(event)
            if (result.isFailure) {
                // Очередь переполнена: клиент не успевает читать.
                // Закрываем канал — цикл соединения завершится, клиент
                // переподключится и догонит пропущенное по курсору.
                log.warn(
                    "очередь подключения {} переполнена, соединение будет закрыто",
                    Redact.id(session.sessionId),
                )
                session.outgoing.close()
            }
        }
    }

    /** Число подключений — для проверок состояния и диагностики. */
    fun connectionCount(): Int = sessionsByUser.values.sumOf { it.size }

    fun connectedUsers(): Set<UUID> = sessionsByUser.keys.toSet()
}
