package com.mango.messenger.ui.misc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mango.core.common.Outcome
import com.mango.core.database.MangoDatabase
import com.mango.core.designsystem.theme.MangoTheme
import com.mango.core.network.AccountFeaturesRemote
import com.mango.core.network.CloudContactDto
import com.mango.messenger.ui.tasks.toUserMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

internal fun phoneContact(name:String,phone:String="",email:String=""):CloudContactDto {
    val normalized=if(phone.isNotBlank()) "phone:"+phone.filter{it.isDigit()} else "email:"+email.trim().lowercase(java.util.Locale.ROOT)
    return CloudContactDto(UUID.nameUUIDFromBytes(normalized.toByteArray(Charsets.UTF_8)).toString(),name.ifBlank{phone.ifBlank{email}}.take(120),phone.trim(),email.trim())
}

/** Reads only names and phone/email rows after explicit Android permission. */
internal fun readPhoneBook(context:Context):List<CloudContactDto> {
    val result=linkedMapOf<String,CloudContactDto>()
    fun read(uri:Uri,column:String,isPhone:Boolean){
        context.contentResolver.query(uri,arrayOf(Phone.DISPLAY_NAME,column),null,null,null)?.use{cursor->
            while(cursor.moveToNext()){
                val value=cursor.getString(1).orEmpty().trim()
                if(value.isBlank())continue
                require(value.length<=if(isPhone)64 else 254){"В телефонной книге слишком длинное поле. Исправьте его перед синхронизацией."}
                val row=if(isPhone)phoneContact(cursor.getString(0).orEmpty(),phone=value) else phoneContact(cursor.getString(0).orEmpty(),email=value)
                result[row.id]=row
                require(result.size<=10000){"Можно синхронизировать до 10000 номеров и email"}
            }
        } ?: error("Телефонная книга недоступна")
    }
    read(Phone.CONTENT_URI,Phone.NUMBER,true);read(Email.CONTENT_URI,Email.ADDRESS,false)
    return result.values.toList()
}

@AndroidEntryPoint
class PhoneContactsActivity:ComponentActivity(){
    @Inject lateinit var remote:AccountFeaturesRemote
    @Inject lateinit var database:MangoDatabase
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MangoTheme{PhoneContactsPage(this,remote,database,startPicker=intent.getBooleanExtra("pick",false)&&savedInstanceState==null,onClose={finish()})}}}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneContactsPage(context:Context,remote:AccountFeaturesRemote,database:MangoDatabase,startPicker:Boolean,onClose:()->Unit){
    val scope=rememberCoroutineScope()
    var cloud by remember{mutableStateOf<List<CloudContactDto>>(emptyList())}
    var pending by remember{mutableStateOf<List<CloudContactDto>?>(null)}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf<String?>(null)}
    var filter by remember{mutableStateOf("")}
    suspend fun owner()=database.userDao().currentUserId() ?: error("Войдите в Mango")
    suspend fun reload(){val user=owner();when(val result=remote.cloudContacts()){
        is Outcome.Success->{if(owner()==user)cloud=result.value}
        is Outcome.Failure->error(result.error.toUserMessage())
    }}
    fun work(action:suspend()->Unit){if(busy)return;busy=true;status=null;scope.launch{try{action()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){status=e.message ?: "Не удалось выполнить операцию"}finally{busy=false}}}
    fun prepareBook(){work{pending=withContext(Dispatchers.IO){readPhoneBook(context)};if(pending!!.isEmpty()){pending=null;status="В телефонной книге нет номеров или email"}}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)prepareBook() else status="Доступ к телефонной книге не разрешён. Ничего не отправлено."}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val uri=result.data?.data
        if(result.resultCode==android.app.Activity.RESULT_OK&&uri!=null)work{
            pending=withContext(Dispatchers.IO){context.contentResolver.query(uri,arrayOf(Phone.DISPLAY_NAME,Phone.NUMBER),null,null,null)?.use{c->if(c.moveToFirst())listOf(phoneContact(c.getString(0).orEmpty(),c.getString(1).orEmpty()))else emptyList()} ?: emptyList()}
        }
    }
    LaunchedEffect(Unit){if(startPicker){try{picker.launch(Intent(Intent.ACTION_PICK,Phone.CONTENT_URI))}catch(_:Exception){status="На телефоне нет приложения для выбора контакта"}}else work{reload()}}
    Scaffold(topBar={TopAppBar(title={Text("Контакты и синхронизация")},navigationIcon={TextButton(onClick=onClose){Text("Назад")}})}){padding->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal=16.dp)){
            Text("Контакты на телефоне",style=MaterialTheme.typography.titleMedium)
            TextButton(enabled=!busy,onClick={try{picker.launch(Intent(Intent.ACTION_PICK,Phone.CONTENT_URI))}catch(_:Exception){status="На телефоне нет приложения для выбора контакта"}}){Text("Выбрать контакт на телефоне")}
            Text("Облачная синхронизация",style=MaterialTheme.typography.titleMedium)
            Text("Имена, телефоны и email сохраняются в вашем аккаунте на сервере Mango. При входе на другом устройстве загрузите контакты из облака. Фото и заметки телефонной книги не отправляются.",style=MaterialTheme.typography.bodySmall)
            Button(enabled=!busy,onClick={if(ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED)prepareBook() else permission.launch(Manifest.permission.READ_CONTACTS)}){Text("Синхронизировать всю телефонную книгу")}
            TextButton(enabled=!busy,onClick={work{reload();status="Загружено из облака: ${cloud.size}"}}){Text("Обновить из облака (${cloud.size})")}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let{Text(it,Modifier.padding(vertical=8.dp))}
            OutlinedTextField(filter,{filter=it},label={Text("Найти контакт")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            LazyColumn(Modifier.weight(1f)){
                items(cloud.filter{(it.name+it.phone+it.email).contains(filter,ignoreCase=true)},key={it.id}){contact->
                    ListItem(headlineContent={Text(contact.name)},supportingContent={Text(listOf(contact.phone,contact.email).filter{it.isNotBlank()}.joinToString(" · "))},trailingContent={TextButton(onClick={
                        val invite=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Присоединяйтесь ко мне в Mango Messenger: "+com.mango.messenger.BuildConfig.SERVER_URL)
                        context.startActivity(Intent.createChooser(invite,"Пригласить контакт"))
                    }){Text("Пригласить")}})
                    HorizontalDivider()
                }
            }
        }
    }
    pending?.let{batch->AlertDialog(onDismissRequest={if(!busy)pending=null},title={Text("Сохранить в облаке?")},text={Text("Будет отправлено записей: ${batch.size}. Имена, номера и email будут храниться на вашем сервере Mango и доступны вашим устройствам. Сервер обрабатывает эти данные. Повторная синхронизация обновляет записи; удаления с телефона в облако не переносятся.")},confirmButton={TextButton(enabled=!busy&&batch.isNotEmpty(),onClick={work{
        val user=owner();var count=0
        for(chunk in batch.chunked(500)){
            check(owner()==user){"Аккаунт изменился. Синхронизация остановлена."}
            when(val result=remote.syncContacts(chunk)){is Outcome.Success->{count+=result.value.saved;status="Сохранено $count из ${batch.size}"};is Outcome.Failure->error(result.error.toUserMessage()+". Повторите синхронизацию: уже сохранённые записи не продублируются.")}
        }
        pending=null;reload();status="Синхронизация завершена: $count записей"
    }}){Text("Сохранить")}},dismissButton={TextButton(enabled=!busy,onClick={pending=null}){Text("Отмена")}})}
}
