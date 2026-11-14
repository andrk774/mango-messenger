package com.mango.messenger.ui.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun MessageSearchDialog(viewModel: ConversationViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val busy by viewModel.searchBusy.collectAsStateWithLifecycle()
    val error by viewModel.searchError.collectAsStateWithLifecycle()
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(300)
        viewModel.searchMessages(query)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Поиск в переписке") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Не менее двух символов") })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!busy && error == null && query.length >= 2 && results.isEmpty()) Text("Ничего не найдено")
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(results, key = { it.id }) { message ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(message.senderName, style = MaterialTheme.typography.labelMedium)
                        Text(message.body.orEmpty())
                        Text(message.createdAt.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider()
                }
            }
            if (results.isNotEmpty()) TextButton(onClick = { viewModel.searchMessages(query, more = true) }, enabled = !busy) { Text("Ещё") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
