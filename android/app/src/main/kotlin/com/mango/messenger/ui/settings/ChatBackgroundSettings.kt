package com.mango.messenger.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mango.core.datastore.ChatBackground

@Composable
internal fun chatBackgroundColor(value: ChatBackground): Color {
    val dark=MaterialTheme.colorScheme.background.red<0.5f
    return when(value) {
        ChatBackground.DEFAULT -> if(dark) Color(0xFF172B25) else Color(0xFFDCE9D5)
        ChatBackground.BLUE -> if(dark) Color(0xFF152B40) else Color(0xFFE5EFF9)
        ChatBackground.MINT -> if(dark) Color(0xFF18352F) else Color(0xFFE0F1E8)
        ChatBackground.LILAC -> if(dark) Color(0xFF30243E) else Color(0xFFEDE5F5)
        ChatBackground.SAND -> if(dark) Color(0xFF352F24) else Color(0xFFF4ECD9)
    }
}

@Composable
internal fun ChatBackgroundSettings(selected:ChatBackground,onSelect:(ChatBackground)->Unit) {
    Text("Фон применяется ко всем чатам на этом устройстве.",Modifier.padding(16.dp))
    for(value in ChatBackground.entries) {
        ListItem(modifier=Modifier.clickable {onSelect(value)},headlineContent={Text(when(value) {
            ChatBackground.DEFAULT -> "По теме приложения"
            ChatBackground.BLUE -> "Небесный"
            ChatBackground.MINT -> "Мятный"
            ChatBackground.LILAC -> "Сиреневый"
            ChatBackground.SAND -> "Песочный"
        })},leadingContent={RadioButton(selected==value,onClick={onSelect(value)})},
            trailingContent={Surface(color=chatBackgroundColor(value),shape=MaterialTheme.shapes.small) {Box(Modifier.size(44.dp))}})
    }
    Surface(color=chatBackgroundColor(selected),modifier=Modifier.fillMaxWidth().padding(16.dp),shape=MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp)) {
            Surface(shape=MaterialTheme.shapes.medium) {Text("Так будет выглядеть чат",Modifier.padding(12.dp))}
            Spacer(Modifier.height(12.dp))
            Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {Text("Фон сохранится после перезапуска",Modifier.padding(12.dp))}
        }
    }
}
