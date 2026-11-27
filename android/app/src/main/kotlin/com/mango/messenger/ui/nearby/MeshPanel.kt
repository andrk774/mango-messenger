package com.mango.messenger.ui.nearby

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable fun MeshPanel(onBack:()->Unit) {
    val context=LocalContext.current
    val mesh=remember{MeshChat(context.applicationContext)}
    var code by remember{mutableStateOf("")};var name by remember{mutableStateOf("Участник")}
    var address by remember{mutableStateOf("")};var text by remember{mutableStateOf("")}
    val running by mesh.running.collectAsState();val status by mesh.status.collectAsState()
    val addresses by mesh.addresses.collectAsState();val count by mesh.count.collectAsState()
    val lines by mesh.lines.collectAsState();val peers by mesh.peers.collectAsState()
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it){mesh.paired();mesh.listenBluetooth()}}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){mesh.stop()}
    DisposableEffect(mesh){onDispose{mesh.stop()}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        TextButton(onClick={mesh.stop();onBack()}){Text("Назад")}
        Text("Без интернета",style=MaterialTheme.typography.headlineMedium)
        Text("Временная общая комната · Bluetooth + Wi-Fi",style=MaterialTheme.typography.titleMedium)
        Text("Один код на всех участников. Сообщения шифруются кодом комнаты; все, кто его знает, могут читать и отправлять сообщения под любым именем. Это не проверенные аккаунты Mango.")
        if(!running){
            OutlinedTextField(name,{if(it.length<=32)name=it},label={Text("Имя в комнате")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(code,{if(it.length<=64)code=it},label={Text("Код комнаты")},modifier=Modifier.fillMaxWidth())
            TextButton(onClick={code=MeshProtocol.newCode()}){Text("Создать новый код")}
            Button(onClick={mesh.start(code,name)},enabled=code.isNotBlank()&&name.isNotBlank()){Text("Включить комнату")}
        }else{
            Text("Соединений: $count")
            Text("Wi-Fi: подключите телефоны к одной сети или точке доступа. Интернет не нужен.")
            Text(addresses.ifEmpty{"Запуск Wi-Fi…"})
            OutlinedTextField(address,{address=it.take(32)},label={Text("IP:порт соседнего телефона")},modifier=Modifier.fillMaxWidth())
            Button(onClick={mesh.connectWifi(address)},enabled=address.isNotBlank()){Text("Подключиться по Wi-Fi")}
            Button(onClick={if(Build.VERSION.SDK_INT>=31)permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else {mesh.paired();mesh.listenBluetooth()}}){Text("Включить Bluetooth и ожидать")}
            Text("Для Bluetooth сначала сопрягите телефоны в настройках Android. На втором выберите первый в списке ниже.")
            peers.forEach{peer->TextButton(onClick={mesh.connectBluetooth(peer)}){
                val label=try { peer.name ?: peer.address } catch (_: SecurityException) { "Сопряжённый телефон" }
                Text(label)
            }}
            TextButton(onClick={mesh.stop()}){Text("Выключить комнату")}
        }
        if(code.isNotBlank())TextButton(onClick={
            val share=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,"Код локальной комнаты Mango: $code")}
            context.startActivity(Intent.createChooser(share,"Передать код участнику"))
        }){Text("Поделиться кодом комнаты")}
        Text(status)
        Text("Телефон пересылает сообщения между соседями, пока этот экран открыт. При сворачивании связь отключается. История временная. Доставка до всех участников не гарантирована.",style=MaterialTheme.typography.bodySmall)
        lines.forEach{line->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(if(line.mine)"Вы" else line.sender,style=MaterialTheme.typography.labelMedium);Text(line.text)}}}
        if(running){
            OutlinedTextField(text,{if(it.toByteArray().size<=4096)text=it},label={Text("Сообщение в комнату")},modifier=Modifier.fillMaxWidth())
            Button(onClick={if(mesh.send(text))text=""},enabled=text.isNotBlank()&&count>0){Text("Отправить")}
        }
    }
}
