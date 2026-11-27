package com.mango.messenger.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.designsystem.component.MangoAvatar
import com.mango.core.designsystem.component.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSignOut:()->Unit,onContacts:()->Unit,onNotifications:()->Unit,
    onNearby:()->Unit,
    modifier:Modifier=Modifier,
    openRootRequest:Int=0,
    viewModel:SettingsViewModel=hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val busy by viewModel.profileBusy.collectAsStateWithLifecycle()
    val error by viewModel.profileError.collectAsStateWithLifecycle()
    val avatarBusy by viewModel.avatarBusy.collectAsStateWithLifecycle()
    val avatarMessage by viewModel.avatarMessage.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val adminAccess by viewModel.adminAccess.collectAsStateWithLifecycle()
    var page by rememberSaveable {mutableStateOf<String?>(null)}
    var edit by remember {mutableStateOf(false)}
    var password by remember {mutableStateOf(false)}
    var menu by remember {mutableStateOf(false)}
    val scroll=rememberScrollState()
    val showProfileTitle by remember { derivedStateOf { scroll.value > 240 } }
    var handledRootRequest by rememberSaveable { mutableIntStateOf(openRootRequest) }
    LaunchedEffect(openRootRequest) {
        if (handledRootRequest != openRootRequest) {
            page=null
            edit=false
            password=false
            menu=false
            scroll.scrollTo(0)
            handledRootRequest=openRootRequest
        }
    }
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri->uri?.let {viewModel.uploadAvatar(context.applicationContext,it)}}
    val user=state.currentUser
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshAdminAccess()
    }
    LaunchedEffect(user?.id) {viewModel.refreshAdminAccess()}
    LaunchedEffect(Unit) {viewModel.refreshAccount()}
    BackHandler(page!=null) {page=null}
    Scaffold(modifier=modifier,containerColor=MaterialTheme.colorScheme.surfaceContainer,
        topBar={TopAppBar(title={if(page!=null || showProfileTitle) Text(page ?: user?.displayName ?: "Профиль")},
            navigationIcon={if(page!=null) IconButton(onClick={page=null}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}},
            actions={if(page==null) Box {
                IconButton(onClick={menu=true}) {Icon(Icons.Default.MoreHoriz,"Меню профиля")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("Редактировать профиль")},onClick={menu=false;viewModel.profileError.value=null;edit=true})
                    DropdownMenuItem(text={Text("Изменить пароль")},enabled=profile!=null,onClick={menu=false;viewModel.profileError.value=null;password=true})
                }
            }},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surfaceContainer))}) {padding->
        Column(Modifier.padding(padding).verticalScroll(if(page==null)scroll else rememberScrollState())) {
            if(page==null) {
                Column(Modifier.fillMaxWidth().padding(bottom=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Box {
                        MangoAvatar(user?.initials ?: "?", imageUrl=user?.avatarUrl,seed=user?.id?.value ?: "me",size=80.dp)
                        FilledIconButton(onClick={picker.launch("image/*")},enabled=!avatarBusy,modifier=Modifier.align(Alignment.BottomEnd)) {Icon(Icons.Default.PhotoCamera,"Изменить фото")}
                    }
                    Text(user?.displayName ?: "Профиль",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,
                        modifier=Modifier.padding(top=12.dp).clickable {edit=true})
                    Text("Мой профиль",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    avatarMessage?.let {Text(it,Modifier.padding(12.dp))}
                }
                Surface {
                    Column {
                        SectionHeader("Аккаунт")
                        ListItem(headlineContent={Text(profile?.email ?: if(error!=null) "Не удалось загрузить email" else "Загрузка…")},supportingContent={Text("Электронная почта")})
                        if(profile==null&&error!=null) TextButton(onClick=viewModel::refreshProfile) {Text("Повторить загрузку")}
                        ListItem(modifier=Modifier.clickable {edit=true},headlineContent={Text("@${user?.username.orEmpty()}")},supportingContent={Text("Имя пользователя")})
                        ListItem(modifier=Modifier.clickable {edit=true},headlineContent={Text(user?.bio?.takeIf {it.isNotBlank()} ?: "О себе")},supportingContent={Text("Нажмите, чтобы изменить профиль")})
                    }
                }
                Spacer(Modifier.height(12.dp))
                SettingsLink("Mango Premium",Icons.Default.WorkspacePremium) {page="Mango Premium"}
                SettingsLink("Баллы Mango · ${account.balance?.points ?: "…"}",Icons.Default.Star) {page="Баллы и подписка"}
                Spacer(Modifier.height(12.dp))
                SettingsLink("Реферальная программа",Icons.Default.PersonAdd) {page="Реферальная программа"}
                SettingsLink("Промокоды",Icons.Default.Redeem) {page="Промокоды"}
                SettingsLink(androidx.compose.ui.res.stringResource(com.mango.messenger.R.string.nav_contacts),Icons.Default.Person,onClick=onContacts)
                SettingsLink("Люди рядом",Icons.Default.NearMe,onClick=onNearby)
                SettingsLink("Центр уведомлений",Icons.Default.Notifications,onClick=onNotifications)
                Spacer(Modifier.height(12.dp))
                SettingsLink("Внешний вид",Icons.Default.Palette) {page="Внешний вид"}
                SettingsLink("Иконка приложения",Icons.Default.Image) {page="Иконка приложения"}
                SettingsLink("Фон чата",Icons.Default.Wallpaper) {page="Фон чата"}
                SettingsLink(androidx.compose.ui.res.stringResource(com.mango.messenger.R.string.nav_notifications),Icons.Default.Notifications) {page="Уведомления"}
                SettingsLink("Данные и синхронизация",Icons.Default.Storage) {page="Данные и синхронизация"}
                SettingsLink("Конфиденциальность",Icons.Default.Lock) {page="Конфиденциальность"}
                SettingsLink("Служба поддержки",Icons.AutoMirrored.Filled.HelpOutline) {page="Служба поддержки"}
                if(adminAccess) SettingsLink("Админ-панель",Icons.Default.AdminPanelSettings) {
                    context.startActivity(android.content.Intent(context,AdminPanelActivity::class.java))
                }
                TextButton(onClick=onSignOut,modifier=Modifier.fillMaxWidth().padding(16.dp)) {Text("Выйти из аккаунта",color=MaterialTheme.colorScheme.error)}
                Text("Mango ${com.mango.messenger.BuildConfig.VERSION_NAME}",Modifier.align(Alignment.CenterHorizontally).padding(bottom=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else when(page) {
                "Служба поддержки" -> SupportSettings()
                "Иконка приложения" -> AppIconSettings()
                "Промокоды" -> PromoSettings(onSuccess={viewModel.refreshAccount()})
                "Mango Premium" -> {
                    Text("Поддержите развитие Mango",Modifier.padding(20.dp),style=MaterialTheme.typography.headlineSmall)
                    Text("Все функции Mango бесплатны. Premium — добровольная поддержка проекта, а не доступ к закрытым функциям. Получите 7 дней Premium по приглашению друга.",Modifier.padding(horizontal=20.dp))
                    SubscriptionCard(viewModel)
                    Text("Оплата поддержки пока не подключена. Списание денег не производится.",Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick={page="Реферальная программа"},modifier=Modifier.fillMaxWidth()){Text("Пригласить друзей")}
                }
                "Реферальная программа" -> ReferralSettings(viewModel)
                "Политика конфиденциальности" -> LegalDocument(privacyText)
                "Пользовательское соглашение" -> LegalDocument(termsText)
                "Фон чата" -> ChatBackgroundSettings(state.settings.chatBackground,viewModel::setChatBackground)
                "Баллы и подписка" -> SubscriptionCard(viewModel)
                "Внешний вид" -> SettingsGroup {
                    ThemeModeSelector(state.settings.themeMode,viewModel::setThemeMode)
                    SwitchRow("Динамические цвета",state.settings.dynamicColorEnabled,viewModel::setDynamicColor,"Подстраивать палитру под обои")
                }
                "Уведомления" -> SettingsGroup {
                    SwitchRow("Новые сообщения",state.settings.messageNotificationsEnabled,viewModel::setMessageNotifications)
                    SwitchRow("Задачи и напоминания",state.settings.taskNotificationsEnabled,viewModel::setTaskNotifications)
                    SwitchRow("Показывать текст сообщения",state.settings.showMessagePreviewInNotification,viewModel::setMessagePreview,"Содержимое в уведомлениях; на экране блокировки скрыто")
                }
                "Конфиденциальность" -> SettingsGroup {
                    SettingsLink("Политика конфиденциальности",Icons.Default.PrivacyTip) {page="Политика конфиденциальности"}
                    SettingsLink("Пользовательское соглашение",Icons.Default.Description) {page="Пользовательское соглашение"}
                    SwitchRow("Отправлять отчёты о прочтении",state.settings.sendReadReceipts,viewModel::setReadReceipts)
                    SwitchRow("Показывать, что я печатаю",state.settings.sendTypingIndicator,viewModel::setTypingIndicator)
                }
                "Данные и синхронизация" -> SettingsGroup {
                    SwitchRow("Загружать медиа только по Wi-Fi",state.settings.autoDownloadMediaOnWifiOnly,viewModel::setWifiOnlyMedia)
                    SyncStatusRow(state.sync)
                }
            }
        }
    }
    if(edit) {
        var name by remember {mutableStateOf(user?.displayName.orEmpty())}
        var username by remember {mutableStateOf(user?.username.orEmpty())}
        var bio by remember {mutableStateOf(user?.bio.orEmpty())}
        AlertDialog(onDismissRequest={if(!busy)edit=false},title={Text("Редактировать профиль")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name,{name=it.take(80)},label={Text("Имя")},singleLine=true)
            OutlinedTextField(username,{username=it.take(32)},label={Text("Имя пользователя")},singleLine=true)
            OutlinedTextField(bio,{bio=it.take(280)},label={Text("О себе")},supportingText={Text("${bio.length}/280")})
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(enabled=!busy&&name.isNotBlank()&&username.matches(Regex("[A-Za-z0-9_]{3,32}")),onClick={viewModel.saveProfile(name,username,bio){edit=false}}){Text(if(busy)"Сохранение…" else "Сохранить")}},dismissButton={TextButton(enabled=!busy,onClick={edit=false}){Text("Отмена")}})
    }
    if(password) {
        var sent by remember {mutableStateOf(false)}
        var token by remember {mutableStateOf("")}
        var newPassword by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={if(!busy)password=false},title={Text("Изменить пароль")},text={Column {
            Text(if(sent) "Введите код из письма и новый пароль. После сохранения потребуется войти снова." else "Отправим код подтверждения на вашу электронную почту.")
            if(sent) {
                OutlinedTextField(token,{token=it},label={Text("Код из письма")},singleLine=true)
                OutlinedTextField(newPassword,{newPassword=it},label={Text("Новый пароль")},singleLine=true,visualTransformation=PasswordVisualTransformation())
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(enabled=!busy&&(!sent||(token.isNotBlank()&&newPassword.length in 12..128)),onClick={viewModel.password(if(sent)token.trim() else null,newPassword){if(sent){password=false;onSignOut()}else sent=true}}){Text(if(sent)"Сохранить пароль" else "Получить код")}},dismissButton={TextButton(enabled=!busy,onClick={password=false}){Text("Отмена")}})
    }
}
