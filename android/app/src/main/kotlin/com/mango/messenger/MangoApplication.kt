package com.mango.messenger

import android.app.Application
import com.mango.core.common.DispatcherProvider
import com.mango.core.common.Logger
import com.mango.core.data.repository.SyncStatusRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MangoApplication : Application() {

    @Inject lateinit var syncStatusRepository: SyncStatusRepository

    @Inject lateinit var dispatchers: DispatcherProvider

    @Inject lateinit var logger: Logger

    override fun onCreate() {
        super.onCreate()
        recoverOutboxAfterRestart()
    }

    /**
     * Восстановление очереди исходящих операций при старте процесса.
     *
     * Приложение могло быть убито системой в момент отправки. Операции,
     * оставшиеся в состоянии IN_FLIGHT, возвращаются в PENDING: неизвестно,
     * дошёл ли запрос до сервера, а повтор безопасен благодаря ключу
     * идемпотентности. Без этого шага сообщение зависло бы навсегда.
     */
    private fun recoverOutboxAfterRestart() {
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            runCatching { syncStatusRepository.recoverAfterRestart() }
                .onSuccess { recovered ->
                    if (recovered > 0) {
                        logger.i(TAG, "recovered $recovered stuck outbox operations")
                    }
                }
                .onFailure { logger.e(TAG, "outbox recovery failed", it) }
        }
    }

    private companion object {
        const val TAG = "MangoApp"
    }
}
