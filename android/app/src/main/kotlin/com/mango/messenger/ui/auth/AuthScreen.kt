package com.mango.messenger.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.designsystem.component.MangoPasswordField
import com.mango.core.designsystem.component.MangoPrimaryButton
import com.mango.core.designsystem.component.MangoTextButton
import com.mango.core.designsystem.component.MangoTextField

/**
 * Экран входа и регистрации.
 *
 * Три шага в одном экране, а не три отдельных: между ними пользователь
 * переходит туда-обратно, и сохранение введённого адреса между шагами
 * избавляет от повторного набора.
 */
@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    var offline by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (offline) {
        com.mango.messenger.ui.nearby.MeshPanel(onBack = { offline = false })
        return
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthEvent.Message -> snackbarHostState.showSnackbar(event.text)
                AuthEvent.Authenticated -> onAuthenticated()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(28.dp))
            androidx.compose.material3.TextButton(onClick = { offline = true }) { Text("Общаться без интернета") }
            com.mango.core.designsystem.component.MangoArtwork()
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Mango",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (state.step) {
                    AuthStep.LOGIN -> "Вход"
                    AuthStep.REGISTER -> "Создание учётной записи"
                    AuthStep.VERIFY_EMAIL -> "Подтверждение адреса"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {

            when (state.step) {
                AuthStep.LOGIN -> LoginForm(state, viewModel)
                AuthStep.REGISTER -> RegisterForm(state, viewModel)
                AuthStep.VERIFY_EMAIL -> VerifyForm(state, viewModel)
            }

            } }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun LoginForm(state: AuthUiState, viewModel: AuthViewModel) {
    MangoTextField(
        value = state.email,
        onValueChange = viewModel::onEmailChange,
        label = "Электронная почта",
        keyboardType = KeyboardType.Email,
        errorMessage = state.fieldErrors["email"],
        enabled = !state.isBusy,
    )
    Spacer(Modifier.height(12.dp))

    MangoPasswordField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = "Пароль",
        errorMessage = state.fieldErrors["password"],
        enabled = !state.isBusy,
    )
    Spacer(Modifier.height(24.dp))

    MangoPrimaryButton(
        text = "Войти",
        onClick = viewModel::login,
        enabled = state.canSubmitLogin,
        loading = state.isBusy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    MangoTextButton(
        text = "Создать учётную запись",
        onClick = { viewModel.switchTo(AuthStep.REGISTER) },
        enabled = !state.isBusy,
    )
    MangoTextButton(
        text = "Забыли пароль?",
        onClick = viewModel::requestPasswordReset,
        enabled = !state.isBusy,
    )
    MangoTextButton(
        text = "У меня есть код подтверждения",
        onClick = { viewModel.switchTo(AuthStep.VERIFY_EMAIL) },
        enabled = !state.isBusy,
    )
}

@Composable
private fun RegisterForm(state: AuthUiState, viewModel: AuthViewModel) {
    MangoTextField(
        value = state.email,
        onValueChange = viewModel::onEmailChange,
        label = "Электронная почта",
        keyboardType = KeyboardType.Email,
        errorMessage = state.fieldErrors["email"],
        enabled = !state.isBusy,
    )
    Spacer(Modifier.height(12.dp))

    MangoTextField(
        value = state.displayName,
        onValueChange = viewModel::onDisplayNameChange,
        label = "Как вас зовут",
        errorMessage = state.fieldErrors["displayName"],
        enabled = !state.isBusy,
    )
    Spacer(Modifier.height(12.dp))

    MangoTextField(
        value = state.username,
        onValueChange = viewModel::onUsernameChange,
        label = "Имя пользователя",
        helperText = "Латиница, цифры и подчёркивание, от 3 до 32 символов",
        errorMessage = state.fieldErrors["username"],
        enabled = !state.isBusy,
    )
    Spacer(Modifier.height(12.dp))

    MangoPasswordField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = "Пароль",
        helperText = "Не короче 10 символов",
        errorMessage = state.fieldErrors["password"],
        enabled = !state.isBusy,
        imeAction = ImeAction.Done,
    )
    Spacer(Modifier.height(24.dp))

    MangoPrimaryButton(
        text = "Зарегистрироваться",
        onClick = viewModel::register,
        enabled = state.canSubmitRegister,
        loading = state.isBusy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    MangoTextButton(
        text = "У меня уже есть учётная запись",
        onClick = { viewModel.switchTo(AuthStep.LOGIN) },
        enabled = !state.isBusy,
    )
}

@Composable
private fun VerifyForm(state: AuthUiState, viewModel: AuthViewModel) {
    Text(
        text = "Введите адрес электронной почты и код из письма.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))

    // Почта подставляется из формы регистрации или входа. Поле видно,
    // чтобы её можно было поправить, если на экран пришли по ссылке
    // «У меня есть код подтверждения».
    MangoTextField(
        value = state.email,
        onValueChange = viewModel::onEmailChange,
        label = "Электронная почта",
        keyboardType = KeyboardType.Email,
        errorMessage = state.fieldErrors["email"],
        enabled = !state.isBusy,
    )
    Spacer(Modifier.height(12.dp))

    MangoTextField(
        value = state.verificationCode,
        onValueChange = viewModel::onCodeChange,
        label = "Код подтверждения",
        errorMessage = state.fieldErrors["token"],
        enabled = !state.isBusy,
        imeAction = ImeAction.Done,
    )
    Spacer(Modifier.height(24.dp))

    MangoPrimaryButton(
        text = "Подтвердить",
        onClick = viewModel::verifyEmail,
        enabled = state.canSubmitVerification,
        loading = state.isBusy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    MangoTextButton(
        text = "Назад ко входу",
        onClick = { viewModel.switchTo(AuthStep.LOGIN) },
        enabled = !state.isBusy,
    )
}
