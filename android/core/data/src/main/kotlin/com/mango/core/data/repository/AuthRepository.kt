package com.mango.core.data.repository

import android.os.Build
import androidx.room.withTransaction
import com.mango.core.common.AppError
import com.mango.core.common.DispatcherProvider
import com.mango.core.common.Logger
import com.mango.core.common.Outcome
import com.mango.core.common.Redact
import com.mango.core.data.session.AccountGuard
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.PresenceStatus
import com.mango.core.network.AuthRemoteDataSource
import com.mango.core.network.auth.SessionManager
import com.mango.core.network.dto.CurrentUserDto
import com.mango.core.security.AuthTokens
import com.mango.core.security.JwtClaims
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /**
     * Есть ли действующая сессия, владелец которой совпадает с владельцем
     * локальной базы. Источник состояния для навигации: пока владелец
     * не подтверждён, интерфейс не показывает локальные данные.
     */
    val isAuthenticated: Flow<Boolean>

    suspend fun restoreSession(): Boolean

    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        username: String,
    ): Outcome<Unit>

    suspend fun verifyEmail(email: String, token: String): Outcome<Unit>

    suspend fun login(email: String, password: String): Outcome<LoginResult>

    suspend fun logout(): Outcome<Unit>

    /** Привязка push-токена к устройству текущей сессии. */
    suspend fun registerPushToken(token: String): Outcome<Unit>

    suspend fun requestPasswordReset(email: String): Outcome<Unit>
}

/** Исход успешной проверки учётных данных. */
sealed interface LoginResult {
    /** Сессия активирована: база принадлежит этому пользователю или была пустой. */
    data object SignedIn : LoginResult

    /**
     * В базе данные другого аккаунта. Сессия НЕ активирована: токены не
     * сохранены, база и её очередь не тронуты. Решение принимает пользователь.
     */
    data object AccountSwitchRequired : LoginResult
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val remote: AuthRemoteDataSource,
    private val sessionManager: SessionManager,
    private val database: MangoDatabase,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val accountGuard: AccountGuard,
) : AuthRepository {

    private val userDao = database.userDao()

    override val isAuthenticated: Flow<Boolean> =
        combine(sessionManager.sessionUserId, userDao.observeCurrentUser()) { sessionUser, owner ->
            sessionUser != null && sessionUser == owner?.id
        }.distinctUntilChanged()

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

    override suspend fun verifyEmail(email: String, token: String): Outcome<Unit> =
        withContext(dispatchers.io) { remote.verifyEmail(email.trim(), token) }

    /**
     * Вход.
     *
     * Порядок защищает данные прежнего аккаунта:
     *  1. токены кандидата живут только в памяти этого вызова;
     *  2. профиль запрашивается с явно переданным токеном кандидата;
     *  3. профиль сверяется с владельцем токена (`sub`);
     *  4. под эксклюзивным допуском сверяется владелец базы;
     *  5. только при совпадении (или пустой базе) записывается «это я»
     *     и сохраняются токены — с этого момента сессия активна.
     *
     * Любая ошибка до шага 5 не оставляет следов: токены не сохранены,
     * база и её очередь не тронуты.
     */
    override suspend fun login(email: String, password: String): Outcome<LoginResult> =
        withContext(dispatchers.io) {
            val pair = when (
                val response = remote.login(
                    email = email,
                    password = password,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    appVersion = APP_VERSION,
                )
            ) {
                is Outcome.Failure -> return@withContext response
                is Outcome.Success -> response.value
            }
            val candidate = AuthTokens(
                accessToken = pair.accessToken,
                refreshToken = pair.refreshToken,
                accessExpiresAtMillis = pair.accessExpiresAt.toEpochMilli(),
            )

            val subject = JwtClaims.subject(candidate.accessToken) ?: run {
                logger.w(TAG, "сервер выдал токен без владельца, вход отклонён")
                return@withContext Outcome.Failure(AppError.Unauthorized)
            }

            val profile = when (val me = remote.meAsCandidate(candidate.accessToken)) {
                is Outcome.Failure -> return@withContext me
                is Outcome.Success -> me.value
            }
            if (profile.id != subject) {
                logger.w(TAG, "профиль не совпадает с владельцем токена, вход отклонён")
                return@withContext Outcome.Failure(AppError.Unauthorized)
            }

            // Проверка владельца и активация — одним эксклюзивным шагом:
            // фоновый проход не начнётся между ними и не застанет
            // промежуточного состояния.
            accountGuard.exclusive {
                val owner = userDao.currentUserId()
                if (owner != null && owner != profile.id) {
                    logger.i(TAG, "на устройстве данные другого аккаунта, требуется решение пользователя")
                    return@exclusive Outcome.Success(LoginResult.AccountSwitchRequired)
                }
                adoptAsOwner(profile)
                sessionManager.save(candidate)
                Outcome.Success(LoginResult.SignedIn)
            }
        }

    private suspend fun adoptAsOwner(profile: CurrentUserDto) {
        database.withTransaction {
            // Признак «это я» должен быть ровно у одной записи. Сюда
            // попадает только владелец этой же базы или пустая база.
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
    override suspend fun registerPushToken(token: String): Outcome<Unit> =
        withContext(dispatchers.io) { remote.registerPush(token) }

    override suspend fun logout(): Outcome<Unit> = withContext(dispatchers.io) {
        // Отзыв сессии на сервере — попытка, а не условие: если сети нет,
        // выйти из приложения всё равно нужно.
        // Токен push снимается первым: после выхода телефон не должен
        // показывать уведомления о переписке этой учётной записи. Сервер
        // и так не шлёт push на устройства без живой сессии — это вторая
        // линия на случай, если отзыв сессии не дошёл.
        remote.unregisterPush()
        remote.logout().let { result ->
            if (result is Outcome.Failure) {
                logger.i(TAG, "сервер не подтвердил выход: ${result.error}")
            }
        }

        // Эксклюзивно: начатый фоновый проход завершается до очистки
        // и не пишет в базу после неё.
        accountGuard.exclusive {
            sessionManager.clear()
            runCatching { database.clearAllTables() }
                .onFailure { logger.e(TAG, "не удалось очистить локальные данные", it) }
        }

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
