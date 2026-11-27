package com.mango.messenger.ui.conversation

import androidx.core.graphics.createBitmap
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class BuiltInSticker(val emoji: String, val caption: String)
internal val builtInStickers = listOf(
    BuiltInSticker("🥭", "Привет!"), BuiltInSticker("👍", "Отлично!"),
    BuiltInSticker("❤️", "Спасибо!"), BuiltInSticker("😂", "Ахаха!"),
    BuiltInSticker("☕", "Перерыв"), BuiltInSticker("🎉", "Ура!"),
    BuiltInSticker("🤗", "Обнимаю"), BuiltInSticker("😴", "Спокойной ночи"),
    BuiltInSticker("💪", "Справимся!"), BuiltInSticker("👀", "Смотрю"),
    BuiltInSticker("🔥", "Огонь!"), BuiltInSticker("✅", "Готово!"),
)

/** PNG attachments keep stickers readable by older clients and in the offline outbox. */
internal suspend fun createSticker(context: Context, sticker: BuiltInSticker): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", createStickerFile(context, sticker))

internal suspend fun createStickerFile(context: Context, sticker: BuiltInSticker): File = withContext(Dispatchers.IO) {
    val bitmap = createBitmap(512, 512)
    try {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        paint.textSize = 280f
        canvas.drawText(sticker.emoji, 256f, 320f, paint)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 40f
        paint.color = Color.WHITE
        canvas.drawRoundRect(12f, 365f, 500f, 460f, 36f, 36f, paint)
        paint.color = Color.rgb(43, 53, 46)
        canvas.drawText(sticker.caption, 256f, 426f, paint)
        val directory = File(context.cacheDir, "attachments/stickers").apply { mkdirs() }
        val file = File(directory, "mango-sticker-${UUID.randomUUID()}.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        file
    } finally { bitmap.recycle() }
}

@Composable
internal fun StickerPicker(onSend: suspend (Uri) -> Boolean, onOwnPicture: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!sending) onDismiss() }, title = { Text("Стикеры Mango") }, text = {
        Column {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (sending) LinearProgressIndicator()
            LazyVerticalGrid(GridCells.Fixed(2), modifier = Modifier.heightIn(max = 360.dp)) {
                items(builtInStickers) { sticker ->
                    TextButton(enabled = !sending, onClick = {
                        sending = true
                        scope.launch {
                            try {
                                if (onSend(createSticker(context, sticker))) onDismiss()
                                else error = "Не удалось отправить стикер. Попробуйте ещё раз."
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "Не удалось подготовить стикер"
                            } finally { sending = false }
                        }
                    }) { Column { Text(sticker.emoji, fontSize = 42.sp); Text(sticker.caption) } }
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !sending, onClick = onOwnPicture) { Text("Своя картинка") }
    }, dismissButton = {
        TextButton(enabled = !sending, onClick = onDismiss) { Text("Закрыть") }
    })
}
