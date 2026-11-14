package com.mango.messenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.mango.core.data.repository.AuthRepository
import com.mango.core.realtime.RealtimeClient
import com.mango.core.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Что показывать на самом верхнем уровне. */
sealed interface AppState {
    /** Сессия ещё восстанавливается — экран не должен мигать входом. */
    data object Loading : AppState

    data object SignedOut : AppState

    data object SignedIn : AppState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val realtimeClient: RealtimeClient,
    private val workManager: WorkManager,
    private val pushRegistrar: com.mango.messenger.push.PushRegistrar,
    private val syncStatusRepository: com.mango.core.data.repository.SyncStatusRepository,
) : ViewModel() {

    private val sessionRestored = MutableStateFlow(false)

    val appState: StateFlow<AppState> = combine(
        authRepository.isAuthenticated,
        sessionRestored,
    ) { authenticated, restored ->
        when {
            // До завершения восстановления нельзя утверждать, что
            // пользователь не вошёл: иначе при каждом запуске мелькал бы
            // экран входа, даже когда сессия есть.
            !restored -> AppState.Loading
            authenticated -> AppState.SignedIn
            else -> AppState.SignedOut
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppState.Loading,
    )

    init {
        viewModelScope.launch {
            combine(
                authRepository.isAuthenticated,
                syncStatusRepository.observeSnapshot().map { it.pendingOperations }.distinctUntilChanged(),
            ) { authenticated, pending -> authenticated to pending }
                .distinctUntilChanged()
                .collect { (authenticated, pending) ->
                    if (authenticated && pending > 0) SyncWorker.enqueueAfterCurrent(workManager)
                }
        }
        viewModelScope.launch {
            authRepository.restoreSession()
            sessionRestored.value = true
        }

        viewModelScope.launch {
            authRepository.isAuthenticated.collect { authenticated ->
                if (authenticated) {
                    realtimeClient.start()
                    SyncWorker.enqueue(workManager)
                    // Токен мог смениться, пока приложение не работало.
                    launch { pushRegistrar.registerCurrentToken() }
                } else {
                    realtimeClient.stop()
                }
            }
        }
    }

    /** Приложение вернулось на передний план: сверяемся с журналом. */
    fun onResumed() {
        realtimeClient.requestCatchUp()
        SyncWorker.enqueue(workManager)
    }

    fun onSignedIn() {
        realtimeClient.start()
        SyncWorker.enqueueNow(workManager)
    }

    fun signOut() {
        viewModelScope.launch {
            realtimeClient.stop()
            authRepository.logout()
        }
    }
}
