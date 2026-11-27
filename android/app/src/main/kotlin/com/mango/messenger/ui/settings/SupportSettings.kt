package com.mango.messenger.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.common.Outcome
import com.mango.core.network.*
import com.mango.messenger.ui.tasks.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel class SupportViewModel @Inject constructor(private val remote:AccountFeaturesRemote):ViewModel() {
    var tickets by mutableStateOf<List<SupportTicketDto>>(emptyList());private set
    var messages by mutableStateOf<List<SupportMessageDto>>(emptyList());private set
    var selected by mutableStateOf<SupportTicketDto?>(null);private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var offset by androidx.compose.runtime.mutableIntStateOf(0);private set
    fun load(next:Int=offset){if(busy)return;viewModelScope.launch{busy=true;try{when(val r=remote.support(next)){is Outcome.Success->{tickets=r.value;offset=next};is Outcome.Failure->error=r.error.toUserMessage()}}finally{busy=false}}}
    fun open(ticket:SupportTicketDto?,older:Boolean=false){if(busy)return;selected=ticket;if(ticket==null){messages=emptyList();load();return};viewModelScope.launch{busy=true;error=null;try{when(val r=remote.supportMessages(ticket.id,if(older)messages.firstOrNull()?.seq else null)){is Outcome.Success->messages=if(older)(r.value+messages).distinctBy{it.id} else r.value;is Outcome.Failure->error=r.error.toUserMessage()}}finally{busy=false}}}
    fun create(id:String,subject:String,body:String,done:()->Unit){if(busy)return;viewModelScope.launch{busy=true;error=null;try{when(val r=remote.createSupport(SupportCreateDto(id,subject,body))){is Outcome.Success->{done();selected=null};is Outcome.Failure->error=r.error.toUserMessage()}}finally{busy=false};load()}}
    fun reply(id:String,text:String,done:()->Unit){val ticket=selected?:return;if(busy)return;viewModelScope.launch{busy=true;error=null;try{when(val r=remote.replySupport(ticket.id,SupportReplyDto(id,text))){is Outcome.Success->done();is Outcome.Failure->error=r.error.toUserMessage()}}finally{busy=false};open(ticket)}}
}

@Composable internal fun SupportSettings(vm:SupportViewModel=hiltViewModel()) {
    var creating by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    LaunchedEffect(Unit){vm.load()}
    val statuses=mapOf("OPEN" to "Новое","IN_PROGRESS" to "В работе","WAITING_USER" to "Ожидает ответа","CLOSED" to "Закрыто")
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Служба поддержки",style=MaterialTheme.typography.titleLarge)
        Text("Ваше обращение увидят сотрудники поддержки. Не отправляйте пароли и коды восстановления.",style=MaterialTheme.typography.bodySmall)
        vm.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(creating){
            OutlinedTextField(subject,{subject=it.take(160)},label={Text("Тема")},modifier=Modifier.fillMaxWidth(),enabled=!vm.busy)
            OutlinedTextField(draft,{draft=it.take(5000)},label={Text("Опишите проблему")},modifier=Modifier.fillMaxWidth(),minLines=3,enabled=!vm.busy)
            Button(enabled=!vm.busy&&subject.isNotBlank()&&draft.isNotBlank(),onClick={vm.create(requestId,subject,draft){creating=false;subject="";draft="";requestId=java.util.UUID.randomUUID().toString()}}){Text("Отправить обращение")}
            TextButton(onClick={creating=false},enabled=!vm.busy){Text("Назад")}
        }else if(vm.selected!=null){
            Text(vm.selected!!.subject,style=MaterialTheme.typography.titleMedium)
            Text(statuses[vm.selected!!.status].orEmpty())
            Row{TextButton(onClick={vm.open(null);draft="";requestId=java.util.UUID.randomUUID().toString()},enabled=!vm.busy){Text("К обращениям")};TextButton(onClick={vm.open(vm.selected)},enabled=!vm.busy){Text("Обновить")}}
            if(vm.messages.size>=100)TextButton(onClick={vm.open(vm.selected,true)},enabled=!vm.busy){Text("Ранние сообщения")}
            vm.messages.forEach{m->Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(m.staff)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)){
                Column(Modifier.padding(12.dp)){Text(if(m.staff)"Поддержка" else "Вы",style=MaterialTheme.typography.labelLarge);Text(m.body);Text(m.createdAt,style=MaterialTheme.typography.labelSmall)}
            }}
            if(vm.selected!!.status!="CLOSED"){
                OutlinedTextField(draft,{draft=it.take(5000)},label={Text("Ваш ответ")},modifier=Modifier.fillMaxWidth(),enabled=!vm.busy)
                Button(enabled=!vm.busy&&draft.isNotBlank(),onClick={vm.reply(requestId,draft){draft="";requestId=java.util.UUID.randomUUID().toString()}}){Text("Отправить")}
            }
        }else{
            Button(onClick={creating=true;requestId=java.util.UUID.randomUUID().toString()},enabled=!vm.busy){Text("Новое обращение")}
            TextButton(onClick={vm.load()},enabled=!vm.busy){Text("Обновить")}
            if(vm.tickets.isEmpty()&&!vm.busy)Text("Обращений пока нет")
            vm.tickets.forEach{t->OutlinedCard(onClick={vm.open(t)},modifier=Modifier.fillMaxWidth(),enabled=!vm.busy){Column(Modifier.padding(16.dp)){Text(t.subject);Text(statuses[t.status].orEmpty(),style=MaterialTheme.typography.labelMedium)}}}
            Row{TextButton(onClick={vm.load((vm.offset-50).coerceAtLeast(0))},enabled=vm.offset>0&&!vm.busy){Text("Назад")};TextButton(onClick={vm.load(vm.offset+50)},enabled=vm.tickets.size==50&&!vm.busy){Text("Далее")}}
        }
    }
}
