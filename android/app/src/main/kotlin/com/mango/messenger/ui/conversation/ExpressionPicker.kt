package com.mango.messenger.ui.conversation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val reactionEmojis = listOf("👍", "👎", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🎉", "👏", "😍", "🥰", "😊", "😁", "😎", "🤔", "😅", "😭", "😡", "🤯", "💯", "✅", "💪", "🤝", "👀", "🚀", "⭐", "💔", "🤗", "🫡")
internal val composerEmojis = reactionEmojis + listOf("😀", "😃", "😄", "😉", "😘", "😋", "😴", "😇", "🥳", "😱", "😔", "🤦", "🤷", "👋", "✌️", "👌", "🌸", "🌞", "🌈", "🍀", "☕", "🍕", "🎂", "🎁")

@Composable
internal fun EmojiPicker(title: String, emojis: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        LazyVerticalGrid(columns = GridCells.Fixed(5), modifier = Modifier.heightIn(max = 340.dp)) {
            items(emojis) { emoji -> TextButton(onClick = { onPick(emoji) }) { Text(emoji, fontSize = 24.sp) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
