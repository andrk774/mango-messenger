package com.mango.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mango.core.common.Logger
import com.mango.core.common.Outcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Фоновая отправка очереди и догонка событий.
 *
 * Запускается системой при появлении сети, поэтому сообщение, написанное
 * в самолёте, уходит само — без участия пользователя и без открытого
 * приложения.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val outboxProcessor: OutboxProcessor,
    private val syncEngine: SyncEngine,
    private val logger: Logger,
    private val accountGuard: com.mango.core.data.session.AccountGuard,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        // Без подтверждённого владельца работы нет: повтор по расписанию
        // лишь копил бы попытки. Синхронизацию снова поставит вход.
        if (accountGuard.verifiedOwner() == null) {
            logger.i(TAG, "синхронизация пропущена: владелец сессии не подтверждён")
            return Result.success()
        }
        return runCatching {
            // Сначала отправка: изменения пользователя важнее догрузки
            // чужих. Кроме того, отправленное сразу вернётся событием,
            // и догонка его уже учтёт.
            val outbox = outboxProcessor.runUntilIdle()

            val catchUp = syncEngine.catchUp()
            if (catchUp is Outcome.Failure) {
                logger.w(TAG, "догонка не удалась: ${catchUp.error}")
                // Повтор назначает WorkManager — у него есть собственный
                // backoff и учёт состояния сети.
                return@runCatching Result.retry()
            }

            logger.i(
                TAG,
                "фоновая синхронизация: отправлено ${outbox.processed}, " +
                    "получено ${(catchUp as Outcome.Success).value.appliedEvents}",
            )
            if (outboxProcessor.hasPendingWork()) Result.retry() else Result.success()
        }.getOrElse { error ->
            logger.e(TAG, "фоновая синхронизация прервана", error)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "SyncWorker"
        private const val UNIQUE_WORK = "mango-sync"

        /** Queue changes must survive the race with an already finishing worker. */
        fun enqueueAfterCurrent(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build(),
            )
        }

        /**
         * Ставит синхронизацию в очередь задач системы.
         *
         * `KEEP` вместо `REPLACE`: если задача уже запланирована,
         * повторная постановка не должна сбрасывать её отсчёт — иначе
         * частые вызовы бесконечно откладывали бы отправку.
         */
        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS,
                    )
                    .addTag(TAG)
                    .build(),
            )
        }

        /** Немедленный запуск: пользователь нажал «отправить снова». */
        fun enqueueNow(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .addTag(TAG)
                    .build(),
            )
        }
    }
}
