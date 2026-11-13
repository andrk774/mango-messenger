package com.mango.messenger.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.common.Outcome
import com.mango.core.data.repository.AuthRepository
import com.mango.core.sync.SyncEngine
import com.mango.messenger.ui.tasks.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Какой шаг показывает экран. */
enum class AuthStep { LOGIN, REGISTER, VERIFY_EMAIL }

data class AuthUiState(
    val step: AuthStep = AuthStep.LOGIN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val username: String = "",
    val verificationCode: String = "",
    val isBusy: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
) {
    val canSubmitLogin: Boolean
        get() = email.isNotBlank() && password.isNotBlank() && !isBusy

    val canSubmitRegister: Boolean
        get() = email.isNotBlank() && password.isNotBlank() &&
            displayName.isNotBlank() && username.isNotBlank() && !isBusy

    val canSubmitVerification: Boolean
        get() = email.isNotBlank() && verificationCode.isNotBlank() && !isBusy
}

sealed interface AuthEvent {
    data class Message(val text: String) : AuthEvent
    data object Authenticated : AuthEvent
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncEngine: SyncEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<AuthEvent> = _events.asSharedFlow()

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, fieldErrors = emptyMap()) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, fieldErrors = emptyMap()) }
    fun onDisplayNameChange(value: String) = _uiState.update { it.copy(displayName = value, fieldErrors = emptyMap()) }
    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value, fieldErrors = emptyMap()) }
    fun onCodeChange(value: String) = _uiState.update { it.copy(verificationCode = value, fieldErrors = emptyMap()) }

    fun switchTo(step: AuthStep) = _uiState.update { it.copy(step = step, fieldErrors = emptyMap()) }

    fun register() {
        val state = _uiState.value
        busy {
            when (val result = authRepository.register(
                email = state.email.trim(),
                password = state.password,
                displayName = state.displayName.trim(),
                username = state.username.trim(),
            )) {
                is Outcome.Success -> {
                    // Ответ сервера одинаков и для свободного, и для занятого
                    // адреса, поэтому экран не может утверждать «вы
                    // зарегистрированы» — только «проверьте почту».
                    _uiState.update { it.copy(step = AuthStep.VERIFY_EMAIL) }
                    _events.emit(
                        AuthEvent.Message(
                            "Если адрес свободен, на него отправлено письмо с кодом",
                        ),
                    )
                }

                is Outcome.Failure -> showError(result)
            }
        }
    }

    fun verifyEmail() {
        val code = _uiState.value.verificationCode.trim()
        val email = _uiState.value.email.trim()
        busy {
            when (val result = authRepository.verifyEmail(email, code)) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(step = AuthStep.LOGIN, verificationCode = "") }
                    _events.emit(AuthEvent.Message("Адрес подтверждён, теперь войдите"))
                }

                is Outcome.Failure -> showError(result)
            }
        }
    }

    fun login() {
        val state = _uiState.value
        busy {
            when (val result = authRepository.login(state.email.trim(), state.password)) {
                is Outcome.Success -> {
                    // Первичная загрузка до перехода на список чатов:
                    // иначе пользователь увидит пустой экран и решит,
                    // что переписок нет.
                    syncEngine.bootstrap()
                    _events.emit(AuthEvent.Authenticated)
                }

                is Outcome.Failure -> showError(result)
            }
        }
    }

    fun requestPasswordReset() {
        val email = _uiState.value.email.trim()
        if (email.isBlank()) {
            _uiState.update { it.copy(fieldErrors = mapOf("email" to "Укажите адрес")) }
            return
        }
        busy {
            authRepository.requestPasswordReset(email)
            _events.emit(
                AuthEvent.Message("Если адрес зарегистрирован, на него отправлено письмо"),
            )
        }
    }

    private suspend fun showError(failure: Outcome.Failure) {
        val error = failure.error
        if (error is com.mango.core.common.AppError.Validation) {
            _uiState.update { it.copy(fieldErrors = error.fieldErrors) }
        }
        _events.emit(AuthEvent.Message(error.toUserMessage()))
    }

    private fun busy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                block()
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }
}
