package com.mango.messenger.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mango.core.designsystem.component.MangoAvatar
import com.mango.core.designsystem.component.label
import com.mango.core.model.*
import com.mango.messenger.ui.format.TimeFormatters

@Composable
internal fun TaskInboxRow(task:Task,onClick:()->Unit,onStatus:(TaskStatus)->Unit) {
    var menu by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        MangoAvatar(initials=task.title.take(1).uppercase(),seed=task.id.value,size=56.dp)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(task.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(task.conversationTitle ?: task.description?.takeIf {it.isNotBlank()} ?: if(task.conversationId!=null) "Задача в чате" else "Личная задача",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(task.dueAt?.let {"Срок: ${TimeFormatters.dueDate(it)}"} ?: TimeFormatters.dueDate(task.createdAt),style=MaterialTheme.typography.bodySmall,color=if(task.isOverdue)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if(task.syncStatus!=SyncStatus.SYNCED)Text("Ожидает синхронизации",style=MaterialTheme.typography.labelSmall)
        }
        Box {
            TextButton(onClick={menu=true}){Text(task.status.label(),style=MaterialTheme.typography.labelMedium)}
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                TaskStatus.entries.forEach {status->DropdownMenuItem(text={Text(status.label())},onClick={onStatus(status);menu=false})}
            }
        }
    }
}
