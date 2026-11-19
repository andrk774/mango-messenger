package com.mango.core.sync

import androidx.room.withTransaction
import com.mango.core.common.AppError
import com.mango.core.common.Logger
import com.mango.core.common.Outcome
import com.mango.core.data.mapper.toEntity
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.SyncStateEntity
import com.mango.core.network.CURSOR_TOO_OLD
import com.mango.core.network.SyncRemoteDataSource
import com.mango.core.network.TasksRemoteDataSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Итог догонки. */
data class CatchUpResult(
    val appliedEvents: Int,
    val lastEventId: Long,
    val resetRequired: Boolean = false,
)

/**
 * Догонка пропущенных событий.
 *
 * Вызывается после каждого подключения WebSocket, при обнаружении разрыва
 * в нумерации входящих событий и при возвращении приложения на передний
 * план. Это механизм, который делает потерю соединения безобидной:
 * сокет может пропасть, события — нет.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val database: MangoDatabase,
    private val remote: SyncRemoteDataSource,
    private val tasksRemote: TasksRemoteDataSource,
    private val applier: EventApplier,
    private val logger: Logger,
    private val accountGuard: com.mango.core.data.session.AccountGuard,
) {

    private val outboxDao = database.outboxDao()
    private val conversationDao = database.conversationDao()

    /** Догонка выполняется в одном экземпляре: параллельные проходы применяли бы одно дважды. */
    private val mutex = Mutex()

    suspend fun currentCursor(): Long =
        outboxDao.syncState(EventApplier.GLOBAL_SCOPE)?.lastEventId ?: 0L

    /**
     * Загружает и применяет всё, что произошло после курсора.
     *
     * Страницы применяются последовательно, курсор продвигается после
     * каждой: обрыв на середине не заставляет начинать заново.
     */
    suspend fun catchUp(): Outcome<CatchUpResult> =
        verified { mutex.withLock { catchUpLocked() } }

    /**
     * События пишутся в базу владельца. Без подтверждённой сессии того же
     * пользователя журнал не запрашивается и не применяется.
     */
    private suspend fun <T> verified(block: suspend () -> Outcome<T>): Outcome<T> =
        accountGuard.withVerifiedOwner { block() } ?: Outcome.Failure(AppError.Unauthorized)

    /**
     * Тело догонки вынесено в отдельную функцию.
     *
     * Внутри бесконечного цикла выход происходит только через return,
     * и в лямбде это выражение имело бы тип Unit. Обычная функция
     * выражает намерение точнее и не требует уловок.
     */
    private suspend fun catchUpLocked(): Outcome<CatchUpResult> {
        var cursor = currentCursor()
        var applied = 0

        while (true) {
            when (val page = remote.since(cursor)) {
                is Outcome.Failure -> {
                    // Устаревший курсор — не сбой связи, а требование
                    // перезагрузить области: часть истории журнала удалена.
                    val error = page.error
                    if (error is AppError.Conflict && CURSOR_TOO_OLD in error.conflictingFields) {
                        logger.w(TAG, "курсор устарел, требуется перезагрузка областей")
                        markResetRequired()
                        return Outcome.Success(
                            CatchUpResult(applied, cursor, resetRequired = true),
                        )
                    }
                    return if (applied > 0) {
                        // Часть событий применена — это успех, пусть и неполный.
                        Outcome.Success(CatchUpResult(applied, cursor))
                    } else {
                        page
                    }
                }

                is Outcome.Success -> {
                    val body = page.value
                    if (body.events.isEmpty()) {
                        return Outcome.Success(CatchUpResult(applied, cursor))
                    }

                    applier.applyBatch(body.events)
                    // Joining an existing chat grants access to older tasks, but
                    // their creation events predate this user's membership.
                    if (body.events.any { it.type in TASK_ACCESS_EVENTS }) refreshTasksVerified()
                    applied += body.events.size
                    cursor = body.lastEventId

                    if (!body.hasMore) {
                        return Outcome.Success(CatchUpResult(applied, cursor))
                    }
                }
            }
        }
    }

    /**
     * Первичная загрузка после входа.
     *
     * Возвращает чаты вместе с курсором журнала: между загрузкой списка
     * и подпиской на события не должно оставаться окна, в котором
     * изменения теряются.
     */
    suspend fun bootstrap(): Outcome<Int> = verified { bootstrapVerified() }

    private suspend fun bootstrapVerified(): Outcome<Int> {
        val response = remote.bootstrap()
        if (response is Outcome.Failure) return response

        val body = (response as Outcome.Success).value

        database.withTransaction {
            body.items.forEach { conversation ->
                conversationDao.upsertFromServer(conversation.toEntity())
                conversation.lastMessage?.let { message ->
                    applier.rememberUser(message.senderId, message.senderName, null)
                    database.messageDao().upsert(message.toEntity())
                }
            }

            outboxDao.upsertSyncState(
                SyncStateEntity(
                    scope = EventApplier.GLOBAL_SCOPE,
                    lastEventId = body.lastEventId,
                    lastSyncedAt = System.currentTimeMillis(),
                    needsReset = false,
                ),
            )
        }

        logger.i(TAG, "первичная загрузка: ${body.items.size} чатов, курсор ${body.lastEventId}")
        // Задачи загружаются вместе с чатами: иначе после входа на новом
        // устройстве раздел задач был бы пуст до первого события.
        refreshTasksVerified()

        return Outcome.Success(body.items.size)
    }

    /**
     * Загрузка задач с сервера.
     *
     * Применяется тем же кодом, что и события журнала, поэтому
     * неотправленные локальные правки не затираются серверной версией.
     *
     * Загружаются обе области — «мои» и «команда»: вместе они покрывают
     * всё, что пользователь вправе видеть. Завершённые включены, чтобы
     * локальная копия не расходилась с сервером по статусам.
     */
    suspend fun refreshTasks(): Outcome<Int> = verified { refreshTasksVerified() }

    private suspend fun refreshTasksVerified(): Outcome<Int> {
        var loaded = 0
        for (scope in listOf("mine", "team")) {
            when (val response = tasksRemote.tasks(scope = scope, includeCompleted = true)) {
                is Outcome.Failure -> return if (loaded > 0) Outcome.Success(loaded) else response
                is Outcome.Success -> {
                    response.value.items.forEach { applier.applyServerTask(it) }
                    loaded += response.value.items.size
                }
            }
        }
        logger.i(TAG, "загружено задач: $loaded")
        return Outcome.Success(loaded)
    }

    private suspend fun markResetRequired() {
        val state = outboxDao.syncState(EventApplier.GLOBAL_SCOPE)
        outboxDao.upsertSyncState(
            SyncStateEntity(
                scope = EventApplier.GLOBAL_SCOPE,
                lastEventId = state?.lastEventId ?: 0,
                lastSyncedAt = state?.lastSyncedAt,
                needsReset = true,
            ),
        )
    }

    companion object {
        val TASK_ACCESS_EVENTS = setOf("CONVERSATION_CREATED", "CONVERSATION_MEMBER_ADDED")
        const val TAG = "SyncEngine"
    }
}
