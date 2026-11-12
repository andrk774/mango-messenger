package com.mango.core.data.repository

import android.os.Build
import androidx.room.withTransaction
import com.mango.core.common.AppError
import com.mango.core.common.DispatcherProvider
import com.mango.core.common.Logger
import com.mango.core.common.Outcome
import com.mango.core.common.Redact
import com.mango.core.common.map
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.PresenceStatus
import com.mango.core.network.AuthRemoteDataSource
import com.mango.core.network.auth.SessionManager
import com.mango.core.security.AuthTokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вход, регистрация и состояние сессии.
 *
 * Экраны обращаются только сюда: ни токены, ни HTTP за пределы этого
 * слоя не выходят.
 */
interface AuthRepository {

    /** Есть ли действующая сессия. Источник состояния для навигации. */
    val isAuthenticated: Flow<Boolean>

    suspend fun restoreSession(): Boolean

    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        username: String,
    ): Outcome<Unit>

    suspend fun verifyEmail(token: String): Outcome<Unit>

    suspend fun login(email: String, password: String): Outcome<Unit>

    suspend fun logout(): Outcome<Unit>

    suspend fun requestPasswordReset(email: String): Outcome<Unit>
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val remote: AuthRemoteDataSource,
    private val sessionManager: SessionManager,
    private val database: MangoDatabase,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : AuthRepository {

    private val userDao = database.userDao()

    override val isAuthenticated: Flow<Boolean> = sessionManager.isAuthenticated

    override suspend fun restoreSession(): Boolean = withContext(dispatchers.io) {
        sessionManager.restore()
    }

    override suspend fun register(
        email: String,
        password: String,
        displayName: String,
        username: String,
    ): Outcome<Unit> = withContext(dispatchers.io) {
        remote.register(email, password, displayName, username)
    }

    override suspend fun verifyEmail(token: String): Outcome<Unit> =
        withContext(dispatchers.io) { remote.verifyEmail(token) }

    /**
     * Вход.
     *
     * После успеха сохраняются токены и профиль. Профиль нужен сразу:
     * без записи «это я» список чатов не сможет отличить свои сообщения
     * от чужих и посчитает непрочитанными собственные.
     */
    override suspend fun login(email: String, password: String): Outcome<Unit> =
        withContext(dispatchers.io) {
            val tokens = remote.login(
                email = email,
                password = password,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                appVersion = APP_VERSION,
            )

            when (tokens) {
                is Outcome.Failure -> tokens
                is Outcome.Success -> {
                    sessionManager.save(
                        AuthTokens(
                            accessToken = tokens.value.accessToken,
                            refreshToken = tokens.value.refreshToken,
                            accessExpiresAtMillis = tokens.value.accessExpiresAt.toEpochMilli(),
                        ),
                    )
                    loadCurrentUser()
                }
            }
        }

    private suspend fun loadCurrentUser(): Outcome<Unit> =
        remote.me().map { profile ->
            database.withTransaction {
                // Признак «это я» должен быть ровно у одной записи:
                // при смене аккаунта старая отметка обязана сняться.
                userDao.clearCurrentUserFlag()
                userDao.upsert(
                    UserEntity(
                        id = profile.id,
                        username = profile.username,
                        displayName = profile.displayName,
                        avatarUrl = profile.avatarUrl,
                        bio = profile.bio,
                        presence = PresenceStatus.ONLINE,
                        lastSeenAt = System.currentTimeMillis(),
                        isMe = true,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

    /**
     * Выход.
     *
     * Локальные данные стираются полностью. Это не формальность:
     * на общем устройстве переписка предыдущего пользователя не должна
     * оставаться доступной следующему, а неотправленные операции чужого
     * аккаунта не должны уехать под новой сессией.
     */
    override suspend fun logout(): Outcome<Unit> = withContext(dispatchers.io) {
        // Отзыв сессии на сервере — попытка, а не условие: если сети нет,
        // выйти из приложения всё равно нужно.
        remote.logout().let { result ->
            if (result is Outcome.Failure) {
                logger.i(TAG, "сервер не подтвердил выход: ${result.error}")
            }
        }

        sessionManager.clear()
        runCatching { database.clearAllTables() }
            .onFailure { logger.e(TAG, "не удалось очистить локальные данные", it) }

        Outcome.Success(Unit)
    }

    override suspend fun requestPasswordReset(email: String): Outcome<Unit> =
        withContext(dispatchers.io) {
            logger.d(TAG, "запрос восстановления для ${Redact.email(email)}")
            remote.forgotPassword(email)
        }

    private companion object {
        const val TAG = "AuthRepo"

        /**
         * Версия приложения для журнала устройств на сервере.
         *
         * Константа, а не BuildConfig: генерация BuildConfig в проекте
         * отключена, а тянуть её ради одной строки незачем.
         */
        const val APP_VERSION = "0.1.0-alpha01"
    }
}

/** Ошибка, по которой экран показывает «требуется повторный вход». */
fun AppError.isSessionExpired(): Boolean = this == AppError.Unauthorized
