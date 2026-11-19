package com.mango.messenger.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable internal fun ReportDialog(onDismiss:()->Unit,onSend:suspend(String)->String) {
    var reason by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var sent by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(if(sent) "Жалоба отправлена" else "Жалоба на пользователя")},
        text={Column {
            if(sent) Text("Модератор рассмотрит обращение.") else {
                Text("Опишите нарушение. Жалобу увидит команда модерации сервера.")
                OutlinedTextField(value=reason,onValueChange={reason=it.take(2000)},enabled=!busy,label={Text("Причина")},minLines=3)
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            }
        }},confirmButton={TextButton(enabled=!busy&&(sent||reason.trim().length>=3),onClick={
            if(sent) onDismiss() else scope.launch {busy=true;error=null;try {onSend(reason.trim());sent=true}
                catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось отправить жалобу"}finally{busy=false}}
        }) {Text(if(sent) "Закрыть" else if(busy) "Отправка…" else "Отправить")}},
        dismissButton={if(!sent)TextButton(enabled=!busy,onClick=onDismiss){Text("Отмена")}})
}
