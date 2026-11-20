package com.mango.messenger.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.data.repository.SyncSnapshot
import com.mango.core.data.repository.SyncStatusRepository
import com.mango.core.data.repository.UserRepository
import com.mango.core.datastore.SettingsStore
import com.mango.core.datastore.ThemeMode
import com.mango.core.datastore.UserSettings
import com.mango.core.designsystem.component.MangoAvatar
import com.mango.core.designsystem.component.MangoTopAppBar
import com.mango.core.designsystem.component.SectionHeader
import com.mango.core.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mango.messenger.ui.tasks.toUserMessage

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val currentUser: User? = null,
    val sync: SyncSnapshot = SyncSnapshot(0, 0, null),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val database: com.mango.core.database.MangoDatabase,
    private val accountRemote: com.mango.core.network.AccountFeaturesRemote,
    private val settingsStore: SettingsStore,
    private val authRemote: com.mango.core.network.AuthRemoteDataSource,
    userRepository: UserRepository,
    syncStatusRepository: SyncStatusRepository,
) : ViewModel() {
    val adminAccess = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var adminCheck: kotlinx.coroutines.Job? = null
    fun refreshAdminAccess() {
        adminCheck?.cancel()
        adminAccess.value=false
        adminCheck=viewModelScope.launch {
            val owner=database.userDao().currentUserId() ?: return@launch
            val result=accountRemote.adminAccess()
            if(database.userDao().currentUserId()==owner && result is com.mango.core.common.Outcome.Success) {
                adminAccess.value=result.value.role in setOf("OWNER","ADMIN","MODERATOR")
            }
        }
    }
    val profile = kotlinx.coroutines.flow.MutableStateFlow<com.mango.core.network.dto.CurrentUserDto?>(null)
    val referral = kotlinx.coroutines.flow.MutableStateFlow<com.mango.core.network.ReferralDto?>(null)
    val referralBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val referralError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun editReferral(code:String,onSuccess:()->Unit) {
        if(referralBusy.value)return
        viewModelScope.launch {
            referralBusy.value=true;referralError.value=null
            try {
                when(val result=accountRemote.editReferral(code)) {
                    is com.mango.core.common.Outcome.Success -> {
                        referral.value=referral.value?.copy(code=result.value.code)
                        onSuccess()
                    }
                    is com.mango.core.common.Outcome.Failure -> referralError.value=result.error.toUserMessage()
                }
            } finally {referralBusy.value=false}
        }
    }
    fun loadReferral(code:String?=null) {
        if(referralBusy.value)return
        viewModelScope.launch {
            referralBusy.value=true;referralError.value=null
            try {
                if(code!=null) when(val result=accountRemote.applyReferral(code)) {
                    is com.mango.core.common.Outcome.Success -> refreshAccount()
                    is com.mango.core.common.Outcome.Failure -> {referralError.value=result.error.toUserMessage();return@launch}
                }
                when(val result=accountRemote.referral()) {
                    is com.mango.core.common.Outcome.Success -> referral.value=result.value
                    is com.mango.core.common.Outcome.Failure -> referralError.value=result.error.toUserMessage()
                }
            } finally {referralBusy.value=false}
        }
    }
    val profileBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val profileError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    init { refreshProfile() }
    fun refreshProfile() { viewModelScope.launch {
        profileError.value=null
        when(val result=accountRemote.profile()) {
            is com.mango.core.common.Outcome.Success -> profile.value=result.value
            is com.mango.core.common.Outcome.Failure -> profileError.value=result.error.toUserMessage()
        }
    } }
    fun saveProfile(name:String,username:String,bio:String,onSuccess:()->Unit) {
        if(profileBusy.value)return
        viewModelScope.launch {
            profileBusy.value=true;profileError.value=null
            try {
                val owner=database.userDao().currentUserId() ?: return@launch
                when(val result=accountRemote.profile(name,username,bio)) {
                    is com.mango.core.common.Outcome.Success -> {
                        if(database.userDao().currentUserId()==owner) {
                            profile.value=result.value
                            database.userDao().findById(owner)?.let { database.userDao().upsert(it.copy(displayName=result.value.displayName,username=result.value.username,bio=result.value.bio)) }
                            onSuccess()
                        }
                    }
                    is com.mango.core.common.Outcome.Failure -> profileError.value=result.error.toUserMessage()
                }
            } finally {profileBusy.value=false}
        }
    }
    fun password(token:String?,password:String,onSuccess:()->Unit) {
        val email=profile.value?.email ?: return
        if(profileBusy.value)return
        viewModelScope.launch {
            profileBusy.value=true;profileError.value=null
            try {
                val result=if(token==null) authRemote.forgotPassword(email)
                    else authRemote.resetPassword(email,token,password)
                when(result) {
                    is com.mango.core.common.Outcome.Success -> onSuccess()
                    is com.mango.core.common.Outcome.Failure -> profileError.value=result.error.toUserMessage()
                }
            } finally {profileBusy.value=false}
        }
    }
    val avatarBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val avatarMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun uploadAvatar(context:android.content.Context,uri:android.net.Uri) {
        if(avatarBusy.value)return
        viewModelScope.launch {
            avatarBusy.value=true
            try {
                val owner=database.userDao().currentUserId() ?: error("Войдите в аккаунт")
                val image=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes=context.contentResolver.openInputStream(uri)?.use {it.readBytesLimited()} ?: error("Не удалось открыть фото")
                    val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0) {"Не удалось прочитать фото"}
                    val options=android.graphics.BitmapFactory.Options().apply {inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/512).coerceAtLeast(1)}
                    val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("Не удалось прочитать фото")
                    val ratio=512.0/maxOf(bitmap.width,bitmap.height).coerceAtLeast(512)
                    val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,(bitmap.width*ratio).toInt().coerceAtLeast(1),(bitmap.height*ratio).toInt().coerceAtLeast(1),true)
                    val out=java.io.ByteArrayOutputStream()
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,out)
                    if(scaled!==bitmap)scaled.recycle()
                    bitmap.recycle()
                    android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP)
                }
                when(val result=accountRemote.avatar(image)) {
                    is com.mango.core.common.Outcome.Success -> {
                        val user=database.userDao().findById(owner)
                        if(user!=null && database.userDao().currentUserId()==owner) database.userDao().upsert(user.copy(avatarUrl=result.value.avatarUrl))
                        avatarMessage.value="Фото профиля сохранено"
                    }
                    is com.mango.core.common.Outcome.Failure -> avatarMessage.value=result.error.toUserMessage()
                }
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){avatarMessage.value=e.message ?: "Не удалось сохранить фото"}
            finally {avatarBusy.value=false}
        }
    }

    val account = kotlinx.coroutines.flow.MutableStateFlow(AccountUiState())
    fun refreshAccount(more:Boolean=false) {
        if(account.value.busy)return
        viewModelScope.launch {
            val old=account.value
            account.value=old.copy(busy=true,error=null)
            try {
                val balance=when(val result=accountRemote.account()) {
                    is com.mango.core.common.Outcome.Success -> result.value
                    is com.mango.core.common.Outcome.Failure -> throw IllegalStateException(result.error.toUserMessage())
                }
                val history=when(val result=accountRemote.history(if(more)old.history.size else 0)) {
                    is com.mango.core.common.Outcome.Success -> result.value
                    is com.mango.core.common.Outcome.Failure -> throw IllegalStateException(result.error.toUserMessage())
                }
                account.value=AccountUiState(balance,((if(more)old.history else emptyList())+history).distinctBy {it.id},hasMore=history.size==50)
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){account.value=old.copy(error=e.message,busy=false)}
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        userRepository.observeCurrentUser(),
        syncStatusRepository.observeSnapshot(),
    ) { settings, user, sync ->
        SettingsUiState(settings = settings, currentUser = user, sync = sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) = update { settingsStore.setThemeMode(mode) }
    fun setChatBackground(background:com.mango.core.datastore.ChatBackground)=update {settingsStore.setChatBackground(background)}
    fun setDynamicColor(enabled: Boolean) = update { settingsStore.setDynamicColorEnabled(enabled) }
    fun setMessageNotifications(enabled: Boolean) =
        update { settingsStore.setMessageNotificationsEnabled(enabled) }

    fun setTaskNotifications(enabled: Boolean) =
        update { settingsStore.setTaskNotificationsEnabled(enabled) }

    fun setMessagePreview(enabled: Boolean) =
        update { settingsStore.setShowMessagePreviewInNotification(enabled) }

    fun setReadReceipts(enabled: Boolean) = update { settingsStore.setSendReadReceipts(enabled) }
    fun setTypingIndicator(enabled: Boolean) =
        update { settingsStore.setSendTypingIndicator(enabled) }

    fun setWifiOnlyMedia(enabled: Boolean) =
        update { settingsStore.setAutoDownloadMediaOnWifiOnly(enabled) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
internal fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp, vertical=4.dp),
        shape=RoundedCornerShape(20.dp),
        color=MaterialTheme.colorScheme.surface,
    ) { Column { content() } }
}

@Composable
internal fun SettingsLink(title: String, icon: ImageVector, enabled: Boolean=true, onClick: () -> Unit) {
    val accent=when(icon) {
        Icons.Default.Star -> Color(0xFFC99016)
        Icons.Default.WorkspacePremium -> Color(0xFF7956CD)
        Icons.Default.Palette -> Color(0xFF9867D7)
        Icons.Default.Storage -> Color(0xFFE99539)
        Icons.Default.Lock -> Color(0xFF39A8D5)
        else -> MaterialTheme.colorScheme.primary
    }
    ListItem(
        modifier=Modifier.clickable(enabled=enabled, onClick=onClick),
        headlineContent={Text(title, style=MaterialTheme.typography.bodyLarge)},
        leadingContent={
            Surface(shape=RoundedCornerShape(9.dp), color=accent) {
                Icon(icon, contentDescription=null, tint=Color.White,
                    modifier=Modifier.padding(7.dp).size(20.dp))
            }
        },
        trailingContent={Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription=null,
            tint=MaterialTheme.colorScheme.onSurfaceVariant)},
    )
}

private fun java.io.InputStream.readBytesLimited():ByteArray {
    val out=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true) {
        val n=read(buffer)
        if(n<0)break
        require(out.size()+n<=10*1024*1024) {"Выберите фото до 10 МБ"}
        out.write(buffer,0,n)
    }
    return out.toByteArray()
}

@Composable
private fun ProfileRow(user: User?) {
    ListItem(
        leadingContent = {
            MangoAvatar(
                initials = user?.initials ?: "?",
                imageUrl = user?.avatarUrl,
                seed = user?.id?.value ?: "anonymous",
                size = 72.dp,
            )
        },
        headlineContent = {
            Text(user?.displayName ?: "Вход не выполнен")
        },
        supportingContent = {
            Text(
                text = user?.username?.let { "@$it" }
                    ?: "Профиль загружается",
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}

@Composable
internal fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor=MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor=MaterialTheme.colorScheme.primary,
                    inactiveContainerColor=MaterialTheme.colorScheme.surface,
                    inactiveContentColor=MaterialTheme.colorScheme.onSurfaceVariant,
                    activeBorderColor=MaterialTheme.colorScheme.outlineVariant,
                    inactiveBorderColor=MaterialTheme.colorScheme.outlineVariant,
                ),
                label = {
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "Системная"
                            ThemeMode.LIGHT -> "Светлая"
                            ThemeMode.DARK -> "Тёмная"
                        },
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    ListItem(
        modifier = Modifier.semantics {
            contentDescription = buildString {
                append(title)
                if (subtitle != null) append(". $subtitle")
                append(if (checked) ". Включено" else ". Выключено")
            }
        },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
internal fun SyncStatusRow(sync: SyncSnapshot) {
    ListItem(
        headlineContent = { Text("Состояние синхронизации") },
        supportingContent = {
            Text(
                text = when {
                    sync.hasProblems ->
                        "${sync.failedOperations} операций не удалось отправить"
                    sync.pendingOperations > 0 ->
                        "${sync.pendingOperations} операций в очереди отправки"
                    else -> "Все локальные изменения записаны"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}
