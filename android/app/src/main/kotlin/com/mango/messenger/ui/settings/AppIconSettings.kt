package com.mango.messenger.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mango.messenger.MainActivity
import com.mango.messenger.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val ICON_KEY = "custom_icon"
private const val SHORTCUT_ID = "mango-custom-icon"
private fun iconPreferences(context: Context) = context.getSharedPreferences("mango_appearance", Context.MODE_PRIVATE)

@Composable
private fun rememberIcon(): Bitmap? {
    val context = LocalContext.current
    val preferences = remember(context) { iconPreferences(context) }
    var encoded by remember(preferences) { mutableStateOf(preferences.getString(ICON_KEY, null)) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == ICON_KEY) encoded = prefs.getString(ICON_KEY, null)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(encoded) {
        runCatching { encoded?.let { Base64.decode(it, Base64.NO_WRAP) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }
}

@Composable
internal fun AppIconBadge(modifier: Modifier = Modifier) {
    val bitmap = rememberIcon()
    val shape = modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF24573D))
    if (bitmap != null) Image(bitmap.asImageBitmap(), "Иконка Mango", shape)
    else Image(painterResource(R.drawable.ic_launcher_foreground), "Иконка Mango", shape)
}

/** Decode a bounded input, then store only a small center-cropped PNG in private app storage. */
internal fun decodeAppIcon(context: Context, uri: Uri): Bitmap {
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = stream.read(chunk)
            if (count < 0) break
            require(out.size() + count <= 5 * 1024 * 1024) { "Выберите изображение до 5 МБ" }
            out.write(chunk, 0, count)
        }
        out.toByteArray()
    } ?: error("Не удалось открыть изображение")
    val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))
    val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
    val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    require(png || jpeg || webp) { "Выберите изображение PNG, JPEG или WebP" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение" }
    require(bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "Изображение слишком большое" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
    val source = if (android.os.Build.VERSION.SDK_INT >= 28) {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.setTargetSampleSize(sample)
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Не удалось прочитать изображение")
    }
    val output = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    val side = minOf(source.width, source.height)
    val left = (source.width - side) / 2
    val top = (source.height - side) / 2
    Canvas(output).apply {
        drawColor(android.graphics.Color.rgb(36, 87, 61))
        drawBitmap(source, Rect(left, top, left + side, top + side), Rect(0, 0, 256, 256), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
    source.recycle()
    return output
}

@Composable
internal fun AppIconSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = rememberIcon()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = null
            try {
                withContext(Dispatchers.IO) {
                    val image = decodeAppIcon(context, uri)
                    val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    image.recycle()
                    check(iconPreferences(context).edit().putString(ICON_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Не удалось сохранить иконку" }
                }
                message = "Иконка внутри Mango изменена. Можно добавить её на рабочий стол."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Не удалось загрузить изображение" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIconBadge(Modifier.size(96.dp))
        Text("Иконка Mango", style = MaterialTheme.typography.titleLarge)
        Text("Выберите PNG, JPEG или WebP до 5 МБ. Картинка обрезается по центру до квадрата и сохраняется только на этом телефоне. Фото профиля не меняется.")
        Button(onClick = { picker.launch("image/*") }, enabled = !busy) { Text(if (busy) "Загрузка…" else "Выбрать свою картинку") }
        OutlinedButton(onClick = {
            try {
                val manager = context.getSystemService(ShortcutManager::class.java)
                if (!manager.isRequestPinShortcutSupported) { message = "Рабочий стол не поддерживает добавление ярлыков"; return@OutlinedButton }
                val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
                    .setShortLabel("Mango")
                    .setIcon(bitmap?.let { Icon.createWithBitmap(it) } ?: Icon.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    .build()
                message = if (manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }) {
                    if (manager.updateShortcuts(listOf(shortcut))) "Иконка ярлыка обновлена" else "Не удалось обновить ярлык. Попробуйте позже."
                } else {
                    if (manager.requestPinShortcut(shortcut, null)) "Подтвердите добавление ярлыка в окне Android" else "Не удалось запросить добавление ярлыка"
                }
            } catch (_: Exception) { message = "Не удалось добавить ярлык. Попробуйте, когда телефон разблокирован." }
        }, enabled = !busy) { Text("Добавить / обновить ярлык") }
        Text("Ярлык открывает Mango. В списке приложений остаётся основная иконка с манго; Android может добавить к ярлыку маленький значок приложения.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { iconPreferences(context).edit().remove(ICON_KEY).apply(); message = "Восстановлена стандартная иконка. Для изменения ярлыка нажмите «Добавить / обновить ярлык»." }, enabled = !busy && bitmap != null) { Text("Вернуть стандартную") }
        message?.let { Text(it) }
    }
}
