package com.mango.messenger.ui.conversation

import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
internal fun ImageStudio(generate:suspend (String,String)->String, onSend:suspend (Uri)->Boolean,onClose:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var prompt by rememberSaveable {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var image by remember {mutableStateOf<File?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    Dialog(onDismissRequest={if(!busy)onClose()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                TextButton(enabled=!busy,onClick=onClose){Text("‹  Назад к стикерам")}
                Text("Создать с ИИ",style=MaterialTheme.typography.headlineLarge)
                Text("Опишите персонажа, эмоцию и стиль. Например: тигр в короне радостно машет лапой.")
                OutlinedTextField(value=prompt,onValueChange={if(it.length<=1500)prompt=it},enabled=!busy,
                    label={Text("Описание изображения")},minLines=3,modifier=Modifier.fillMaxWidth())
                Text("Описание будет отправлено внешнему сервису ИИ. Переписка не передаётся.",style=MaterialTheme.typography.bodySmall)
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Подождите, обрабатываем запрос…")}
                image?.let {AsyncImage(model=it,contentDescription="Созданное изображение",modifier=Modifier.fillMaxWidth().height(300.dp))}
                Button(enabled=!busy && prompt.trim().length>=3,onClick={
                    busy=true;error=null
                    scope.launch {
                        try {
                            val encoded=generate(UUID.randomUUID().toString(),prompt.trim())
                            image=withContext(Dispatchers.IO){
                                require(encoded.length<=12_000_000)
                                val bytes=Base64.decode(encoded,Base64.DEFAULT)
                                val dir=File(context.cacheDir,"attachments/stickers").apply{mkdirs()}
                                File(dir,"ai-${UUID.randomUUID()}.png").apply{writeBytes(bytes)}
                            }
                        } catch(e:kotlinx.coroutines.CancellationException){throw e}
                        catch(e:Exception){error=e.message?:"Не удалось создать изображение"}
                        finally{busy=false}
                    }
                },modifier=Modifier.fillMaxWidth()){Text(if(image==null)"Сгенерировать" else "Создать другой вариант")}
                image?.let {file ->
                    Button(enabled=!busy,onClick={busy=true;error=null;scope.launch {
                        try {if(onSend(FileProvider.getUriForFile(context,"${context.packageName}.files",file)))onClose() else error="Не удалось отправить. Попробуйте снова."}
                        catch(e:kotlinx.coroutines.CancellationException){throw e}
                        catch(_:Exception){error="Не удалось отправить изображение"}
                        finally{busy=false}
                    }},modifier=Modifier.fillMaxWidth()){Text("Отправить в чат")}
                }
            }
        }
    }
}
