package com.mango.messenger.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.network.AccountBalanceDto
import com.mango.core.network.AccountHistoryDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AccountUiState(val balance:AccountBalanceDto?=null,val history:List<AccountHistoryDto> = emptyList(),val busy:Boolean=false,val error:String?=null,val hasMore:Boolean=false)

private fun readableDate(raw:String?):String = raw?.let {runCatching {DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it))}.getOrDefault(it)}?:"Нет"

@Composable internal fun SubscriptionCard(viewModel:SettingsViewModel) {
    val state by viewModel.account.collectAsStateWithLifecycle()
    var showHistory by remember {mutableStateOf(false)}
    LaunchedEffect(Unit){viewModel.refreshAccount()}
    Card(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("Баллы и подписка",style=MaterialTheme.typography.titleMedium)
            state.balance?.let {balance ->
                Text("${balance.points} баллов",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
                val active=runCatching {Instant.parse(balance.subscriptionUntil).isAfter(Instant.now())}.getOrDefault(false)
                Text(if(active) "Доступ до ${readableDate(balance.subscriptionUntil)}" else "Подписка не активна")
                if(!active&&balance.subscriptionUntil!=null) Text("Срок закончился: ${readableDate(balance.subscriptionUntil)}")
            }
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            androidx.compose.foundation.layout.FlowRow {
                TextButton(enabled=!state.busy,onClick={viewModel.refreshAccount()}){Text("Обновить")}
                TextButton(onClick={showHistory=true}){Text("История изменений")}
            }
        }
    }
    if(showHistory) AlertDialog(onDismissRequest={showHistory=false},title={Text("История баланса и подписки")},
        text={LazyColumn(Modifier.heightIn(max=420.dp)) {
            if(state.history.isEmpty()) item {Text(if(state.busy) "Загрузка…" else "Изменений пока нет")}
            items(state.history,key={it.id}) {entry ->
                Column(Modifier.padding(vertical=8.dp)) {
                    Text(readableDate(entry.createdAt),style=MaterialTheme.typography.labelMedium)
                    Text("Баллы: ${entry.oldPoints} → ${entry.newPoints}")
                    if(entry.oldSubscription!=entry.newSubscription) Text("Подписка: ${readableDate(entry.oldSubscription)} → ${readableDate(entry.newSubscription)}")
                    Text(entry.reason);HorizontalDivider()
                }
            }
            if(state.hasMore)item {TextButton(enabled=!state.busy,onClick={viewModel.refreshAccount(true)}){Text("Ещё")}}
            state.error?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
        }},confirmButton={TextButton(onClick={showHistory=false}){Text("Закрыть")}})
}
