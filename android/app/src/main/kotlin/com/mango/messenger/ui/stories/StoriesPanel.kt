package com.mango.messenger.ui.stories

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.common.Outcome
import com.mango.messenger.ui.tasks.toUserMessage
import com.mango.core.network.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

@HiltViewModel class StoriesViewModel @Inject constructor(private val remote:AccountFeaturesRemote):ViewModel() {
    var stories by mutableStateOf<List<StoryDto>>(emptyList());private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var selected by mutableStateOf<StoryDto?>(null);private set
    var image by mutableStateOf<Bitmap?>(null);private set
    var more by mutableStateOf(false);private set
    var audience by mutableStateOf<List<CloudContactDto>>(emptyList());private set
    fun loadContacts(){viewModelScope.launch{when(val r=remote.cloudContacts()){is Outcome.Success->audience=r.value.filter{it.email.isNotBlank()};is Outcome.Failure->error=r.error.toUserMessage()}}}
    fun removeContact(id:String){if(busy)return;viewModelScope.launch{busy=true;try{when(val r=remote.removeCloudContact(id)){is Outcome.Success->loadContacts();is Outcome.Failure->error=r.error.toUserMessage()}}finally{busy=false}}}
    fun load(append:Boolean=false){if(busy)return;viewModelScope.launch{busy=true;error=null;try{
        when(val r=remote.stories(if(append)stories.size else 0)){
            is Outcome.Success->{stories=if(append)(stories+r.value).distinctBy{it.id}else r.value;more=r.value.size==50}
            is Outcome.Failure->error=r.error.toUserMessage()
        }
    }finally{busy=false}}}
    fun open(story:StoryDto){if(busy)return;viewModelScope.launch{busy=true;error=null;try{
        when(val r=remote.storyImage(story.id)){
            is Outcome.Success->{val decoded=withContext(Dispatchers.Default){val bytes=Base64.decode(r.value.image,Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)};close();image=decoded;selected=story}
            is Outcome.Failure->error=r.error.toUserMessage()
        }
    }finally{busy=false}}}
    fun close(){image?.recycle();image=null;selected=null}
    fun publish(id:String,bitmap:Bitmap,done:()->Unit){if(busy){bitmap.recycle();return};viewModelScope.launch{busy=true;error=null;try{
        val encoded=withContext(Dispatchers.Default){try{ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,85,it);Base64.encodeToString(it.toByteArray(),Base64.NO_WRAP)}}finally{bitmap.recycle()}}
        when(val r=remote.publishStory(id,encoded)){is Outcome.Success->{done()};is Outcome.Failure->error=r.error.toUserMessage()}
    }finally{busy=false};load()}}
    fun remove(){val story=selected?:return;if(busy)return;viewModelScope.launch{busy=true;try{
        when(val r=remote.deleteStory(story.id)){is Outcome.Success->close();is Outcome.Failure->error=r.error.toUserMessage()}
    }finally{busy=false};load()}}
    fun contact(email:String,done:()->Unit){if(busy)return;viewModelScope.launch{busy=true;try{
        when(val r=remote.syncContacts(listOf(CloudContactDto(UUID.randomUUID().toString(),email,email=email)))){is Outcome.Success->done();is Outcome.Failure->error=r.error.toUserMessage()}
    }finally{busy=false}}}
    override fun onCleared(){close()}
}

@Composable fun StoriesEntry(vm:StoriesViewModel=hiltViewModel()) {
    var opened by remember {mutableStateOf(false)}
    Surface(onClick={opened=true;vm.load()},shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) {
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            StoryRing("＋")
            Column(Modifier.weight(1f)){Text("Истории",style=MaterialTheme.typography.titleMedium);Text("Моменты для своих · 24 часа",style=MaterialTheme.typography.bodySmall)}
            Text("Открыть ›",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        }
    }
    if(!opened)return
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var draft by remember{mutableStateOf<Bitmap?>(null)}
    var draftId by remember{mutableStateOf(UUID.randomUUID().toString())}
    var localError by remember{mutableStateOf<String?>(null)}
    var decoding by remember{mutableStateOf(false)}
    var contactEmail by remember{mutableStateOf("")}
    var contacts by remember{mutableStateOf(false)}
    var removingContact by remember{mutableStateOf<CloudContactDto?>(null)}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()){bitmap->if(bitmap!=null){draft?.recycle();draft=bitmap;draftId=UUID.randomUUID().toString()}}
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(allowed)camera.launch(null)else localError="Для съёмки разрешите доступ к камере"}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null)scope.launch{decoding=true;try{
        val bitmap=withContext(Dispatchers.IO){
            if(android.os.Build.VERSION.SDK_INT>=28)ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)){decoder,info,_->
                val scale=minOf(1f,1080f/info.size.width,1440f/info.size.height)
                decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }else {
                val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,options)}
                require(options.outWidth>0&&options.outHeight>0)
                options.inSampleSize=1;while(options.outWidth/options.inSampleSize>1080||options.outHeight/options.inSampleSize>1440)options.inSampleSize*=2
                options.inJustDecodeBounds=false
                context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,options)}?:error("Фото недоступно")
            }
        };draft?.recycle();draft=bitmap;draftId=UUID.randomUUID().toString();localError=null
    }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){localError="Не удалось открыть фото"}finally{decoding=false}}}
    var now by remember{mutableStateOf(Instant.now())}
    LaunchedEffect(Unit){while(true){delay(1000);now=Instant.now();if(vm.selected?.let{!Instant.parse(it.expiresAt).isAfter(now)}==true)vm.close()}}
    DisposableEffect(Unit){onDispose{vm.close();draft?.recycle()}}
    Dialog(onDismissRequest={if(!vm.busy&&!decoding)opened=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)){Text("Истории",style=MaterialTheme.typography.headlineMedium);Text("Ваш день в одном кадре",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick={opened=false},enabled=!vm.busy&&!decoding){Text("Закрыть")}}}
                item {Text("Видны 24 часа контактам из вашей облачной книги с подтверждённым email. Сохранённые получателем копии отозвать нельзя.",style=MaterialTheme.typography.bodySmall)}
                if(vm.busy||decoding)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
                (localError?:vm.error)?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error)}}
                if(draft!=null) {
                    item {StoryEditor(draft!!,vm.busy,{bitmap->vm.publish(draftId,bitmap){draft?.recycle();draft=null}},{draft?.recycle();draft=null})}
                }else if(vm.selected!=null){
                    item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){StoryRing(vm.selected!!.displayName.take(1).uppercase());Column{Text(vm.selected!!.displayName,style=MaterialTheme.typography.titleMedium);Text("До "+java.time.format.DateTimeFormatter.ofPattern("dd MMM, HH:mm",androidx.compose.ui.platform.LocalLocale.current.platformLocale).withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(vm.selected!!.expiresAt)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                    vm.image?.let{bitmap->item{Image(bitmap.asImageBitmap(),"История",modifier=Modifier.fillMaxWidth().heightIn(max=520.dp).clip(RoundedCornerShape(24.dp)))}}
                    item{Row{TextButton(onClick=vm::close){Text("Назад")};if(vm.selected?.mine==true)TextButton(onClick=vm::remove,enabled=!vm.busy){Text("Скрыть мою историю")}}}
                }else {
                    item{Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF204F40),Color(0xFF3C775C)))).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Поделитесь моментом",style=MaterialTheme.typography.titleLarge,color=Color.White)
                        Text("Фото, эмоции и немного магии — только для ваших контактов.",style=MaterialTheme.typography.bodyMedium,color=Color(0xFFDCEBDD))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=!vm.busy&&!decoding,colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFFFC66D),contentColor=Color(0xFF203C30))){Text("＋ Добавить фото")};TextButton(onClick={cameraPermission.launch(android.Manifest.permission.CAMERA)},enabled=!vm.busy&&!decoding,colors=ButtonDefaults.textButtonColors(contentColor=Color.White)){Text("Камера")}}
                    }}
                    item{Row{TextButton(onClick={contacts=!contacts;if(contacts)vm.loadContacts()}){Text("Кому видно")};TextButton(onClick={vm.load()},enabled=!vm.busy){Text("Обновить")}}}
                    if(contacts)item{Column{Text("Добавьте email контакта, которому разрешаете смотреть ваши истории.");OutlinedTextField(contactEmail,{contactEmail=it.take(254)},label={Text("Email контакта")},singleLine=true);Button(onClick={vm.contact(contactEmail.trim()){contactEmail="";contacts=false}},enabled=!vm.busy&&android.util.Patterns.EMAIL_ADDRESS.matcher(contactEmail.trim()).matches()){Text("Добавить контакт")}}}
                    if(contacts)items(vm.audience,key={"contact-"+it.id}){contact->Column{Text(contact.email);TextButton(onClick={removingContact=contact},enabled=!vm.busy){Text("Удалить из облачных контактов")}}}
                    val active=vm.stories.filter{Instant.parse(it.expiresAt).isAfter(now)}
                    if(active.isEmpty()&&!vm.busy)item{Column(Modifier.fillMaxWidth().padding(vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){StoryRing("✦");Text("Здесь появятся ваши моменты",style=MaterialTheme.typography.titleMedium);Text("Добавьте первую историю или загляните позже",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                    items(active,key={it.id}){story->OutlinedCard(onClick={vm.open(story)},enabled=!vm.busy,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){StoryRing(story.displayName.take(1).uppercase());Column(Modifier.weight(1f)){Text(if(story.mine)"Моя история"else story.displayName,style=MaterialTheme.typography.titleMedium);Text("Осталось ${java.time.Duration.between(now,Instant.parse(story.expiresAt)).toHours().coerceAtLeast(0)} ч",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("›",style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.primary)}}}
                    if(vm.more)item{TextButton(onClick={vm.load(true)},enabled=!vm.busy){Text("Ещё истории")}}
                }
            }
        }
    }
    removingContact?.let {contact->AlertDialog(onDismissRequest={removingContact=null},title={Text("Удалить контакт?")},text={Text("${contact.email} будет удалён из облачной книги. Доступ к вашим историям будет отозван, если нет другой записи с тем же email.")},confirmButton={TextButton(onClick={vm.removeContact(contact.id);removingContact=null}){Text("Удалить")}},dismissButton={TextButton(onClick={removingContact=null}){Text("Отмена")}})}
}

@Composable private fun StoryRing(label:String) {
    Box(Modifier.size(58.dp).border(2.dp,Brush.linearGradient(listOf(Color(0xFFFFBB54),Color(0xFF459577))),CircleShape).padding(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),contentAlignment=Alignment.Center){Text(label,style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)}
}
