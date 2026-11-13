package com.mango.messenger.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.mango.core.common.Logger
import com.mango.core.common.Outcome
import com.mango.core.data.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Регистрация устройства для push.
 *
 * Firebase может быть не настроен (нет google-services.json) — тогда
 * регистрация молча пропускается: приложение работает, просто без push.
 */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val logger: Logger,
) {
    val isAvailable: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    /** После входа и при каждом запуске: токен мог смениться, пока приложение не работало. */
    suspend fun registerCurrentToken() {
        if (!isAvailable) {
            logger.i(TAG, "Firebase не настроен — push выключены")
            return
        }
        currentToken()?.let { register(it) }
    }

    /** Новый токен от Firebase (FirebaseMessagingService.onNewToken). */
    suspend fun register(token: String) {
        when (val result = authRepository.registerPushToken(token)) {
            is Outcome.Success -> logger.i(TAG, "push-токен зарегистрирован")
            // Без сессии регистрировать некуда — повторим после входа.
            is Outcome.Failure -> logger.w(TAG, "push-токен не зарегистрирован: ${result.error}")
        }
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }

    private companion object {
        const val TAG = "Push"
    }
}
