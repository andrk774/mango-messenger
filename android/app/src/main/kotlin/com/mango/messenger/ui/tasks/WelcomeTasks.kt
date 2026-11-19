package com.mango.messenger.ui.tasks

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.common.Outcome
import com.mango.core.network.AccountFeaturesRemote
import com.mango.core.network.WelcomeTaskDto
import com.mango.core.designsystem.component.MangoAvatar
import com.mango.messenger.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WelcomeTasksViewModel @Inject constructor(private val remote: AccountFeaturesRemote): ViewModel() {
    val tasks = MutableStateFlow<List<WelcomeTaskDto>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    fun refresh() {
        if(busy.value) return
        viewModelScope.launch {
            busy.value=true
            try {
                when(val result=remote.welcome()) {
                    is Outcome.Success -> { tasks.value=result.value; error.value=null }
                    is Outcome.Failure -> { error.value=result.error.toUserMessage() }
                }
            } finally { busy.value=false }
        }
    }
    fun finish(kind:String,status:String) {
        if(busy.value) return
        viewModelScope.launch {
            busy.value=true
            try {
                when(val result=remote.welcomeStatus(kind,status)) {
                    is Outcome.Success -> { tasks.value=tasks.value.map {if(it.kind==kind)result.value else it}; error.value=null }
                    is Outcome.Failure -> { error.value=result.error.toUserMessage() }
                }
            } finally { busy.value=false }
        }
    }
}

private data class WelcomeGuide(val kind:String,val title:String,val text:String,val action:String)
private val guides=listOf(
    WelcomeGuide("AVATAR","Установите аватарку","Откройте свой профиль в настройках Mango. Фотография помогает друзьям и коллегам узнавать вас. После настройки отметьте задание выполненным.","Открыть профиль"),
    WelcomeGuide("INVITE","Пригласите друзей и коллег","Поделитесь адресом вашего Mango. Для локального сервера получателю нужна та же сеть и доступ к серверу; ссылка не делает сервер доступным из интернета.","Поделиться приглашением"),
    WelcomeGuide("CHAT","Создайте первый чат","Найдите собеседника по имени или username и создайте личный чат. Для группы выберите нескольких участников и укажите название.","Выбрать собеседника"),
    WelcomeGuide("TASK","Создайте первую задачу","Введите название и выберите личную задачу или чат. Затем откройте карточку: назначьте исполнителей из участников чата, укажите срок и настройте напоминание. Статусы помогут следить за выполнением.","Создать задачу"),
)

@Composable
fun WelcomeTasks(onProfile:()->Unit,onChat:()->Unit,onTask:()->Unit,viewModel:WelcomeTasksViewModel=hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var opened by remember {mutableStateOf(false)}
    var selected by remember {mutableStateOf<String?>(null)}
    val context=LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {viewModel.refresh()}
    if(tasks.isNotEmpty() || error!=null) {
        com.mango.core.designsystem.component.MangoWelcomeBanner(
            "Первые шаги", "Завершено ${tasks.count {it.status!="PENDING"}} из 4 · продолжить знакомство", {opened=true},
        )
    }
    if(opened) AlertDialog(
        onDismissRequest={opened=false;selected=null},
        title={Text(selected?.let {kind->guides.first {it.kind==kind}.title} ?: "Первые шаги в Mango")},
        text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(error!=null) {Text(error!!,color=MaterialTheme.colorScheme.error);TextButton(onClick=viewModel::refresh,enabled=!busy){Text("Повторить")}}
            val guide=guides.find {it.kind==selected}
            if(guide==null) guides.forEach {g->
                tasks.find {it.kind==g.kind}?.let {task->
                    OutlinedCard(onClick={selected=g.kind},modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {Text(g.title);Text(when(task.status){"DONE"->"Выполнено";"SKIPPED"->"Пропущено";else->"Новое"},style=MaterialTheme.typography.labelMedium)}
                    }
                }
            } else {
                // Live Compose illustration: the same Material components and Mango avatar as the app.
                WelcomePreview(guide.kind)
                Text(guide.text)
                Button(onClick={
                    if(guide.kind=="INVITE") {
                        val text="Присоединяйтесь к Mango Messenger: ${BuildConfig.SERVER_URL}web/"
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Пригласить в Mango"))
                    } else {
                        opened=false;selected=null
                        when(guide.kind){"AVATAR"->onProfile();"CHAT"->onChat();"TASK"->onTask()}
                    }
                },modifier=Modifier.fillMaxWidth()){Text(guide.action)}
                Text("Открытие экрана или отправка приглашения не завершает задание автоматически.",style=MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled=!busy,onClick={viewModel.finish(guide.kind,"DONE")}){Text("Выполнено")}
                    TextButton(enabled=!busy,onClick={viewModel.finish(guide.kind,"SKIPPED")}){Text("Пропустить")}
                }
                tasks.find {it.kind==guide.kind}?.let {Text(when(it.status){"DONE"->"Сохранено: выполнено";"SKIPPED"->"Сохранено: пропущено";else->"Новое задание"})}
            }
        }},
        confirmButton={TextButton(onClick={if(selected!=null)selected=null else opened=false}){Text(if(selected!=null)"К списку" else "Закрыть")}},
    )
}

@Composable
private fun WelcomePreview(kind:String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Mango · пример экрана",style=MaterialTheme.typography.labelSmall)
            Text(when(kind){"AVATAR"->"Настройки";"TASK"->"Задачи";else->"Новый чат"},style=MaterialTheme.typography.titleLarge)
            when(kind) {
                "AVATAR" -> ListItem(headlineContent={Text("Ваш профиль")},supportingContent={Text("@username")},leadingContent={MangoAvatar(initials="Я",seed="welcome",size=48.dp)})
                "TASK" -> {Text("Название задачи",style=MaterialTheme.typography.titleMedium);HorizontalDivider();Text("Исполнители · Участники чата");Text("Срок · Напоминание");Text("Новая → В работе → Выполнена")}
                else -> {OutlinedTextField(value="Имя или username",onValueChange={},readOnly=true,label={Text("Поиск пользователей")},modifier=Modifier.fillMaxWidth());ListItem(headlineContent={Text("Собеседник")},supportingContent={Text("@username")},leadingContent={MangoAvatar(initials="М",seed="mango",size=40.dp)})}
            }
        }
    }
}
