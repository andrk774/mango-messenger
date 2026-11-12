package com.mango.core.realtime

import com.mango.core.common.AppError
import com.mango.core.common.DispatcherProvider
import com.mango.core.common.Logger
import com.mango.core.common.Redact
import com.mango.core.common.RetryPolicy
import com.mango.core.network.auth.SessionManager
import com.mango.core.network.di.PlainClient
import com.mango.core.network.di.ServerConfig
import com.mango.core.sync.EventApplier
import com.mango.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Соединение реального времени.
 *
 * ## Что этот класс гарантирует
 *
 * Он НЕ гарантирует доставку. Источник истины — журнал событий на сервере,
 * и любое пропущенное событие приходит при сверке по курсору. Поэтому
 * здесь нет подтверждений доставки и очередей повторной отправки:
 * надёжность обеспечена в другом месте, а дублирование её здесь только
 * усложнило бы код и создало второй источник истины.
 *
 * Что он действительно делает:
 *  * держит соединение живым и переподключается с нарастающей задержкой;
 *  * после каждого подключения сверяется с журналом;
 *  * замечает разрыв в нумерации событий и инициирует сверку, не дожидаясь
 *    переподключения.
 */
@Singleton
class RealtimeClient @Inject constructor(
    @PlainClient private val okHttpClient: OkHttpClient,
    private val serverConfig: ServerConfig,
    private val sessionManager: SessionManager,
    private val syncEngine: SyncEngine,
    private val applier: EventApplier,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val connectMutex = Mutex()
    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null

    /** Идентификатор последнего применённого события — для поиска разрывов. */
    private val lastEventId = AtomicLong(0)

    private var attempt = 0

    /**
     * Запускает поддержание соединения.
     *
     * Повторный вызов безопасен: если цикл уже работает, второй не создаётся.
     */
    fun start() {
        scope.launch {
            connectMutex.withLock {
                if (connectionJob?.isActive == true) return@withLock
                connectionJob = scope.launch { connectionLoop() }
            }
        }
    }

    fun stop() {
        scope.launch {
            connectMutex.withLock {
                connectionJob?.cancel()
                connectionJob = null
                webSocket?.close(NORMAL_CLOSURE, "stop")
                webSocket = null
                _state.value = ConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * Цикл подключения.
     *
     * Задержка между попытками растёт с джиттером: без него все устройства,
     * потерявшие связь одновременно, вернутся одной волной ровно в момент,
     * когда сервер поднимается.
     */
    private suspend fun connectionLoop() {
        while (scope.isActive) {
            val token = sessionManager.currentAccessToken()
            if (token == null) {
                _state.value = ConnectionState.UNAUTHENTICATED
                logger.d(TAG, "нет токена, соединение не открывается")
                return
            }

            _state.value = ConnectionState.CONNECTING
            val closedReason = openAndAwaitClose(token)

            if (closedReason == CloseOutcome.UNAUTHENTICATED) {
                // Токен мог просто истечь: одна попытка обновления, и если
                // она не удалась — ждём действий пользователя.
                if (!sessionManager.refresh()) {
                    _state.value = ConnectionState.UNAUTHENTICATED
                    return
                }
                attempt = 0
                continue
            }

            _state.value = ConnectionState.DISCONNECTED

            val backoff = RetryPolicy.Default.delayMillis(attempt)
            attempt = (attempt + 1).coerceAtMost(MAX_ATTEMPT)
            logger.d(TAG, "переподключение через $backoff мс")
            delay(backoff)
        }
    }

    private enum class CloseOutcome { NORMAL, FAILURE, UNAUTHENTICATED }

    private suspend fun openAndAwaitClose(token: String): CloseOutcome {
        val completion = kotlinx.coroutines.CompletableDeferred<CloseOutcome>()

        val request = Request.Builder()
            .url(serverConfig.baseUrl.toWebSocketUrl())
            .build()

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Токен уходит первым кадром, а не в адресе соединения:
                // адрес попадает в журналы прокси, и убрать его оттуда
                // уже невозможно.
                webSocket.send(json.encodeToString(ClientFrame.serializer(), ClientFrame.Auth(token)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleFrame(text, completion) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
                completion.complete(CloseOutcome.NORMAL)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.d(TAG, "соединение прервано: ${Redact.text(t.message)}")
                completion.complete(
                    if (response?.code == 401) CloseOutcome.UNAUTHENTICATED else CloseOutcome.FAILURE,
                )
            }
        }

        webSocket = okHttpClient.newWebSocket(request, listener)
        val outcome = completion.await()
        webSocket?.cancel()
        webSocket = null
        return outcome
    }

    private suspend fun handleFrame(
        text: String,
        completion: kotlinx.coroutines.CompletableDeferred<CloseOutcome>,
    ) {
        val frame = runCatching { json.decodeFromString<ServerFrame>(text) }.getOrNull()
            ?: return

        when (frame) {
            is ServerFrame.ServerHello -> {
                attempt = 0
                _state.value = ConnectionState.CONNECTED

                val localCursor = syncEngine.currentCursor()
                lastEventId.set(localCursor)

                // Всё, что произошло до подключения, в сокет не придёт.
                // Поэтому сверка обязательна, а не «на всякий случай».
                if (frame.lastEventId > localCursor) {
                    logger.i(
                        TAG,
                        "журнал сервера ${frame.lastEventId} > локального $localCursor, догоняем",
                    )
                    syncEngine.catchUp()
                    lastEventId.set(syncEngine.currentCursor())
                }
            }

            is ServerFrame.Event -> {
                val expected = lastEventId.get() + 1
                if (frame.event.id > expected && lastEventId.get() > 0) {
                    // Разрыв в нумерации: часть событий не дошла. Ждать
                    // переподключения незачем — сверяемся сразу.
                    logger.w(
                        TAG,
                        "пропуск событий: ожидалось $expected, пришло ${frame.event.id}",
                    )
                    syncEngine.catchUp()
                    lastEventId.set(syncEngine.currentCursor())
                } else {
                    applier.applyRealtime(frame.event)
                    lastEventId.set(maxOf(lastEventId.get(), frame.event.id))
                }

                webSocket?.send(
                    json.encodeToString(
                        ClientFrame.serializer(),
                        ClientFrame.Ack(frame.event.id),
                    ),
                )
            }

            is ServerFrame.Error -> {
                logger.w(TAG, "сервер отклонил соединение: ${frame.code}")
                completion.complete(
                    if (frame.code == "UNAUTHENTICATED") {
                        CloseOutcome.UNAUTHENTICATED
                    } else {
                        CloseOutcome.FAILURE
                    },
                )
            }

            ServerFrame.Pong -> Unit
        }
    }

    /** Повторная сверка по запросу: приложение вернулось на передний план. */
    fun requestCatchUp() {
        scope.launch {
            when (val result = syncEngine.catchUp()) {
                is com.mango.core.common.Outcome.Success -> {
                    lastEventId.set(syncEngine.currentCursor())
                    if (result.value.resetRequired) {
                        logger.w(TAG, "курсор устарел, требуется перезагрузка")
                    }
                }

                is com.mango.core.common.Outcome.Failure -> {
                    if (result.error !is AppError.Network) {
                        logger.w(TAG, "сверка не удалась: ${result.error}")
                    }
                }
            }
        }
    }

    private fun String.toWebSocketUrl(): String =
        trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/" + ServerConfig.WEBSOCKET_PATH

    private companion object {
        const val TAG = "RealtimeClient"
        const val NORMAL_CLOSURE = 1000
        const val MAX_ATTEMPT = 10
    }
}
