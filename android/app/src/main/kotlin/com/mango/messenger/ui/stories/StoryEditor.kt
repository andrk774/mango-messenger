package com.mango.messenger.ui.stories

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.media.FaceDetector
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StorySticker(val text:String,val x:Float=.5f,val y:Float=.5f,val size:Float=.12f,val rotation:Float=0f)

/** All positions use image coordinates; preview and exported pixels share this renderer. */
internal fun renderStory(source:Bitmap,stickers:List<StorySticker>):Bitmap {
    val result=source.copy(Bitmap.Config.ARGB_8888,true)
    val canvas=Canvas(result)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {textAlign=Paint.Align.CENTER;color=android.graphics.Color.WHITE}
    for(s in stickers){
        paint.textSize=source.width*s.size
        paint.setShadowLayer(3f,0f,1f,android.graphics.Color.BLACK)
        canvas.withRotation(s.rotation,source.width*s.x,source.height*s.y) {
            drawText(s.text,source.width*s.x,source.height*s.y-(paint.ascent()+paint.descent())/2,paint)
        }
    }
    return result
}

/** On-device detection. The photo and face geometry are never sent to a recognition service. */
private fun faceStickers(source:Bitmap,mask:String):List<StorySticker> {
    val width=source.width-source.width%2
    if(width<2)return emptyList()
    val rgb=createBitmap(width,source.height,Bitmap.Config.RGB_565)
    try {
        Canvas(rgb).drawBitmap(source,0f,0f,null)
        val faces=arrayOfNulls<FaceDetector.Face>(8)
        val count=FaceDetector(width,source.height,faces.size).findFaces(rgb,faces)
        return faces.take(count).mapNotNull {face ->
            face?:return@mapNotNull null
            val mid=PointF();face.getMidPoint(mid)
            val eyeDistance=face.eyesDistance()
            StorySticker(mask,mid.x/source.width,((mid.y+if(mask=="👑")-eyeDistance*1.4f else 0f)/source.height).coerceIn(0f,1f),(eyeDistance*2.8f/source.width).coerceIn(.08f,.5f))
        }
    } finally {rgb.recycle()}
}

@Composable internal fun StoryEditor(source:Bitmap,busy:Boolean,onPublish:(Bitmap)->Unit,onCancel:()->Unit) {
    var stickers by remember(source){mutableStateOf(emptyList<StorySticker>())}
    var selected by remember(source){mutableIntStateOf(-1)}
    var mask by remember(source){mutableStateOf<String?>(null)}
    var hint by remember(source){mutableStateOf("")}
    var text by remember(source){mutableStateOf("")}
    val preview=remember(source,stickers){renderStory(source,stickers)}
    DisposableEffect(preview){onDispose{preview.recycle()}}
    LaunchedEffect(mask){val value=mask?:return@LaunchedEffect
        val detected=withContext(Dispatchers.Default){try{faceStickers(source,value)}catch(e:RuntimeException){emptyList()}catch(e:UnsatisfiedLinkError){emptyList()}}
        if(detected.isEmpty())hint="Лицо не найдено. Можно добавить маску как наклейку и передвинуть вручную."
        else {stickers=(stickers+detected).takeLast(30);selected=stickers.lastIndex;hint="Маска добавлена. Перетащите её для точной подгонки."}
        mask=null
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Редактор фото",style=MaterialTheme.typography.titleLarge)
        Text("Маски накладываются на готовое фото. Наклейки можно двигать пальцем.",style=MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max=380.dp)) {
            val density=LocalDensity.current
            val displayWidth=minOf(maxWidth,380.dp*source.width.toFloat()/source.height)
            val width=with(density){displayWidth.toPx()}
            val height=width*source.height/source.width
            Image(preview.asImageBitmap(),"Предпросмотр истории",contentScale=ContentScale.Fit,
                modifier=Modifier.width(displayWidth).align(Alignment.Center).aspectRatio(source.width.toFloat()/source.height).clip(RoundedCornerShape(22.dp))
                    .pointerInput(selected,source){detectDragGestures {change,delta ->
                        change.consume()
                        if(selected in stickers.indices)stickers=stickers.mapIndexed {i,s->if(i==selected)s.copy(x=(s.x+delta.x/width).coerceIn(0f,1f),y=(s.y+delta.y/height).coerceIn(0f,1f))else s}
                    }})
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("❤️","🔥","🎉","😊","😎","👑","🐱","✨").forEach {emoji->
                TextButton(enabled=!busy&&stickers.size<30,onClick={stickers=stickers+StorySticker(emoji);selected=stickers.lastIndex}){Text(emoji)}
            }
        }
        Row {
            TextButton(enabled=!busy&&mask==null,onClick={mask="🕶️"}){Text("Очки на лицо")}
            TextButton(enabled=!busy&&mask==null,onClick={mask="👑"}){Text("Корона")}
        }
        if(hint.isNotEmpty())Text(hint,style=MaterialTheme.typography.bodySmall)
        Row {
            OutlinedTextField(text,{text=it.take(60)},label={Text("Подпись-наклейка")},modifier=Modifier.weight(1f),singleLine=true)
            TextButton(enabled=text.isNotBlank()&&!busy&&stickers.size<30,onClick={stickers=stickers+StorySticker(text,size=.06f);selected=stickers.lastIndex;text=""}){Text("＋")}
        }
        if(stickers.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState())){stickers.forEachIndexed {i,s->FilterChip(selected==i,{selected=i},label={Text(s.text.take(10))})}}
            if(selected in stickers.indices) {
                Slider(stickers[selected].size,{size->stickers=stickers.mapIndexed{i,s->if(i==selected)s.copy(size=size)else s}},valueRange=.03f.. .5f,enabled=!busy)
                Text("Поворот",style=MaterialTheme.typography.labelSmall)
                Slider(stickers[selected].rotation,{angle->stickers=stickers.mapIndexed{i,s->if(i==selected)s.copy(rotation=angle)else s}},valueRange=-180f..180f,enabled=!busy)
                TextButton(onClick={stickers=stickers.filterIndexed{i,_->i!=selected};selected=stickers.lastIndex},enabled=!busy){Text("Убрать наклейку")}
            }
        }
        Row {
            TextButton(onClick=onCancel,enabled=!busy){Text("Отмена")}
            Button(onClick={onPublish(renderStory(source,stickers))},enabled=!busy&&mask==null){Text("Опубликовать на 24 часа")}
        }
    }
}
