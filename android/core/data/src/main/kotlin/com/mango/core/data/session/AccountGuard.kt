package com.mango.core.data.session

import com.mango.core.database.MangoDatabase
import com.mango.core.network.auth.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Допуск фоновой работы к локальной базе и сети.
 *
 * База одна на устройство, а сессия может смениться: истёк refresh,
 * вошёл другой пользователь. Очередь исходящих, догонка журнала и
 * сокет работают только когда владелец сессии (из выданного сервером
 * токена) совпадает с владельцем базы (пользователь с `is_me`).
 * Иначе операции одного аккаунта ушли бы под токеном другого.
 *
 * Фоновые проходы входят разделяемо и могут идти параллельно. Смена
 * аккаунта входит эксклюзивно: новые проходы не начинаются, начатые
 * дожидаются завершения — проверка владельца не устаревает посреди
 * прохода.
 */
@Singleton
class AccountGuard @Inject constructor(
    private val sessionManager: SessionManager,
    private val database: MangoDatabase,
) {
    private val gate = Mutex()
    private val switchMutex = Mutex()
    private var switching = false
    private val active = MutableStateFlow(0)

    /** Владелец, если сессия и база принадлежат одному пользователю; иначе `null`. */
    suspend fun verifiedOwner(): String? {
        val sessionUser = sessionManager.currentUserId() ?: return null
        val owner = database.userDao().currentUserId() ?: return null
        return sessionUser.takeIf { it == owner }
    }

    /**
     * Выполняет [block], только если владелец подтверждён.
     *
     * @return результат или `null`, если работа запрещена.
     */
    suspend fun <T> withVerifiedOwner(block: suspend (ownerId: String) -> T): T? {
        val owner = gate.withLock {
            if (switching) return null
            val owner = verifiedOwner() ?: return null
            active.update { it + 1 }
            owner
        }
        try {
            return block(owner)
        } finally {
            active.update { it - 1 }
        }
    }

    /**
     * Смена владельца сессии или базы.
     *
     * Пока [block] выполняется, фоновые проходы не допускаются.
     * Вызывать изнутри [withVerifiedOwner] нельзя: проход ждал бы сам себя.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = switchMutex.withLock {
        gate.withLock { switching = true }
        try {
            active.first { it == 0 }
            block()
        } finally {
            gate.withLock { switching = false }
        }
    }
}
