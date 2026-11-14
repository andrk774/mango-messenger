package com.mango.messenger.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun VoiceComposer(onSend: suspend (android.net.Uri) -> Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var draft by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    fun finish() {
        val current = recorder ?: return
        recorder = null
        try { current.stop(); draft = output } catch (_: RuntimeException) { error = "Запись слишком короткая" }
        finally { current.release() }
    }
    fun start() {
        if (recorder != null || draft != null) return
        error = null
        val directory = File(context.cacheDir, "attachments/voice").apply { mkdirs() }
        val file = File(directory, "voice-${java.util.UUID.randomUUID()}.m4a")
        @Suppress("DEPRECATION") val current = MediaRecorder()
        try {
            current.setAudioSource(MediaRecorder.AudioSource.MIC)
            current.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            current.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            current.setAudioEncodingBitRate(96000)
            current.setAudioSamplingRate(44100)
            current.setOutputFile(file.absolutePath)
            current.setMaxDuration(120000)
            current.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish() }
            current.prepare()
            current.start()
            output = file
            recorder = current
        } catch (_: Exception) { current.release(); error = "Не удалось включить микрофон" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else error = "Для записи нужно разрешение на микрофон"
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) finish() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); recorder?.run { runCatching { stop() }; release() }; recorder = null }
    }
    Column {
        error?.let { Text(it) }
        if (recorder != null) Text("Идёт запись · максимум 2 минуты")
        Row {
            if (draft == null) TextButton(onClick = {
                if (recorder != null) finish()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (recorder == null) "Записать голосовое" else "Остановить") }
            draft?.let { file ->
                VoicePlayback(file.name, load = { file })
                TextButton(enabled = !sending, onClick = {
                    scope.launch {
                        sending = true
                        try { if (onSend(FileProvider.getUriForFile(context, "${context.packageName}.files", file))) draft = null }
                        finally { sending = false }
                    }
                }) { Text("Отправить") }
                TextButton(enabled = !sending, onClick = { draft = null }) { Text("Отмена") }
            }
        }
    }
}

@Composable
internal fun VoicePlayback(key: String, load: suspend () -> File?) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember(key) { mutableStateOf<MediaPlayer?>(null) }
    var loading by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf(false) }
    var alive by remember(key) { mutableStateOf(true) }
    fun stop() { player?.release(); player = null }
    DisposableEffect(key, lifecycle) {
        alive = true
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { alive = false; lifecycle.removeObserver(observer); stop() }
    }
    TextButton(enabled = !loading, onClick = {
        if (player != null) stop() else scope.launch {
            loading = true
            error = false
            try {
                val file = load()
                if (!alive) return@launch
                if (file == null) { error = true; return@launch }
                val current = MediaPlayer()
                player = current
                current.setDataSource(file.absolutePath)
                current.setOnCompletionListener { stop() }
                current.setOnErrorListener { _, _, _ -> error = true; stop(); true }
                current.setOnPreparedListener { if (alive && player === it) it.start() }
                current.prepareAsync()
            } catch (_: Exception) { error = true; stop() }
            finally { loading = false }
        }
    }) { Text(when { loading -> "Загрузка…"; player != null -> "Стоп"; error -> "Повторить аудио"; else -> "▶ Слушать" }) }
}
