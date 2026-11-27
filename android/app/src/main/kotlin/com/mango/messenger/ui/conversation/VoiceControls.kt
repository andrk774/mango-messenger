package com.mango.messenger.ui.conversation

import android.Manifest
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

@Composable
internal fun VoiceComposer(onSend: suspend (android.net.Uri) -> Boolean, showRecordButton: Boolean = true, leading: @Composable RowScope.() -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var draft by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var videoMode by remember { mutableStateOf(false) }
    var pendingVideo by remember { mutableStateOf<File?>(null) }
    val videoCapture = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { saved ->
        if(saved)draft=pendingVideo
        pendingVideo=null
    }
    fun captureVideo() {
        try {
            val directory=File(context.cacheDir,"attachments/video").apply{mkdirs()}
            val file=File(directory,"video-${java.util.UUID.randomUUID()}.mp4")
            pendingVideo=file
            videoCapture.launch(FileProvider.getUriForFile(context,"${context.packageName}.files",file))
        } catch(_:Exception){error="Не удалось открыть камеру";pendingVideo=null}
    }
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted)captureVideo() else error="Для видеосообщения разрешите камеру"
    }
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            leading()
            if (draft == null && (showRecordButton || recorder != null)) androidx.compose.material3.Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primary,
                modifier=Modifier.size(48.dp).combinedClickable(onDoubleClick={if(recorder==null)videoMode=!videoMode},onClick = {
                if (recorder != null) finish()
                else if(videoMode){if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)captureVideo() else cameraPermission.launch(Manifest.permission.CAMERA)}
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            })) {Box(contentAlignment=androidx.compose.ui.Alignment.Center){androidx.compose.material3.Icon(if (recorder != null) androidx.compose.material.icons.Icons.Default.Stop else if(videoMode)androidx.compose.material.icons.Icons.Default.Videocam else androidx.compose.material.icons.Icons.Default.Mic,
                if(recorder!=null)"Остановить запись" else if(videoMode)"Записать видеосообщение. Двойное нажатие — микрофон" else "Записать голосовое. Двойное нажатие — камера")}}
        }
        Column {
            draft?.let { file ->
                if(file.extension=="mp4") {
                    Text("Предпросмотр видеосообщения")
                    androidx.compose.ui.viewinterop.AndroidView(factory={ctx->android.widget.VideoView(ctx).apply{
                        setVideoPath(file.absolutePath)
                        setMediaController(android.widget.MediaController(ctx).also{it.setAnchorView(this)})
                        setOnPreparedListener{seekTo(1)}
                    }},modifier=Modifier.fillMaxWidth().height(200.dp),onRelease={it.stopPlayback()})
                }else VoicePlayback(file.name, load = { file })
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
internal fun VoicePlayback(key:String,load:suspend()->File?,transcribe:(suspend()->String)?=null,durationMs:Long?=null) {
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var player by remember(key) {mutableStateOf<MediaPlayer?>(null)}
    var loading by remember(key) {mutableStateOf(false)}
    var playing by remember(key) {mutableStateOf(false)}
    var duration by remember(key) {mutableIntStateOf((durationMs?:0).coerceIn(0,Int.MAX_VALUE.toLong()).toInt())}
    var position by remember(key) {mutableIntStateOf(0)}
    var error by remember(key) {mutableStateOf<String?>(null)}
    var alive by remember(key) {mutableStateOf(true)}
    var text by remember(key) {mutableStateOf<String?>(null)}
    var recognizing by remember(key) {mutableStateOf(false)}
    fun release(){player?.release();player=null;playing=false;loading=false;position=0}
    DisposableEffect(key,lifecycle) {
        alive=true
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP){player?.let {if(playing)it.pause()};playing=false}}
        lifecycle.addObserver(observer)
        onDispose {alive=false;lifecycle.removeObserver(observer);release()}
    }
    LaunchedEffect(key,playing) {while(playing){position=player?.currentPosition?:0;delay(200)}}
    Column(Modifier.widthIn(min=230.dp,max=290.dp)) {
        Text("Голосовое сообщение",style=MaterialTheme.typography.labelLarge)
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(enabled=!loading,onClick={
                val current=player
                if(current!=null){if(playing)current.pause() else current.start();playing=!playing}
                else scope.launch {
                    loading=true;error=null
                    try {
                        val file=load() ?: throw IllegalStateException("Не удалось загрузить запись")
                        if(!alive)return@launch
                        val created=MediaPlayer();player=created
                        created.setDataSource(file.absolutePath)
                        created.setOnPreparedListener {if(alive&&player===it){duration=it.duration;loading=false;playing=true;it.start()}}
                        created.setOnCompletionListener {playing=false;position=0;it.seekTo(0)}
                        created.setOnErrorListener {_,_,_->error="Не удалось воспроизвести. Попробуйте снова";release();true}
                        created.prepareAsync()
                    }catch(e:CancellationException){release();throw e}
                    catch(_:Exception){release();error="Не удалось загрузить аудио"}
                }
            }) {Text(if(loading) "Загрузка…" else if(playing) "Пауза" else "Слушать")}
            Text("${voiceTime(position)} / ${if(duration>0)voiceTime(duration) else "—:—"}",style=MaterialTheme.typography.labelMedium)
        }
        Slider(value=position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),
            onValueChange={position=it.toInt();player?.seekTo(position)},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=player!=null&&!loading)
        if(transcribe!=null) TextButton(enabled=!recognizing,onClick={scope.launch {
            recognizing=true;error=null
            try {text=transcribe().ifBlank {"Речь не обнаружена"}}
            catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Расшифровка недоступна"}finally{recognizing=false}
        }}) {Text(if(recognizing) "Распознаём речь…" else if(text==null) "Расшифровать" else "Обновить расшифровку")}
        text?.let {SelectionContainer {Text(it,style=MaterialTheme.typography.bodyMedium)};Text("Автоматическая расшифровка может содержать ошибки",style=MaterialTheme.typography.labelSmall)}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }
}

internal fun voiceTime(milliseconds:Int):String {
    val seconds=milliseconds.coerceAtLeast(0)/1000
    return "%d:%02d".format(java.util.Locale.ROOT,seconds/60,seconds%60)
}

