package com.mango.messenger.ui.nearby

import androidx.core.content.edit
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.mango.core.common.Outcome
import com.mango.core.network.*
import com.mango.core.data.repository.ConversationRepository
import com.mango.core.model.UserId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import kotlin.math.round

@HiltViewModel class NearbyViewModel @Inject constructor(
    private val remote:AccountFeaturesRemote,private val conversations:ConversationRepository,
    private val database:com.mango.core.database.MangoDatabase
):ViewModel() {
    val accountId=MutableStateFlow<String?>(null)
    init {viewModelScope.launch {accountId.value=database.userDao().currentUserId()}}
    val people=MutableStateFlow<List<NearbyPersonDto>>(emptyList())
    val status=MutableStateFlow("Вы не видны другим пользователям")
    val visible=MutableStateFlow(false)
    private var work:Job?=null
    private var expiry:Job?=null
    fun publish(lat:Double,lon:Double) {
        work?.cancel()
        work=viewModelScope.launch {
            status.value="Включение видимости…"
            when(remote.nearbyPublish(round(lat*50)/50,round(lon*50)/50)) {
                is Outcome.Success -> {
                    visible.value=true;status.value="Вы видны до 2 минут. Область поиска — около 10 км"
                    refresh()
                    expiry?.cancel();expiry=viewModelScope.launch {delay(120_000);hide()}
                }
                is Outcome.Failure -> {visible.value=false;people.value=emptyList();status.value="Не удалось включить видимость. Проверьте сервер и повторите позже"}
            }
        }
    }
    fun refresh() { viewModelScope.launch {
        when(val result=remote.nearby()) {
            is Outcome.Success -> people.value=result.value
            is Outcome.Failure -> {people.value=emptyList();status.value="Поиск недоступен или видимость истекла. Включите её заново"}
        }
    } }
    fun hide() {
        work?.cancel();expiry?.cancel();visible.value=false;people.value=emptyList()
        status.value="Вы не видны на этом экране; серверная видимость истечёт не позднее чем через 2 минуты"
        viewModelScope.launch {
            if(remote.nearbyHide() is Outcome.Success) status.value="Видимость выключена"
        }
    }
    fun chat(id:String,open:(String)->Unit) {viewModelScope.launch {
        when(val result=conversations.openDirect(UserId(id))) {
            is Outcome.Success -> {hide();open(result.value.value)}
            is Outcome.Failure -> status.value="Не удалось открыть чат"
        }
    }}
}

@SuppressLint("MissingPermission")
@Composable fun NearbyScreen(onBack:()->Unit,onConversation:(String)->Unit,
    modifier:Modifier=Modifier,model:NearbyViewModel=hiltViewModel()) {
    val context=LocalContext.current
    val accountId by model.accountId.collectAsState()
    val preferences=remember {context.getSharedPreferences("nearby-consent",Context.MODE_PRIVATE)}
    var consentDialog by remember {mutableStateOf(false)}
    var automatic by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val bluetooth=remember {BluetoothChat(context.applicationContext,scope)}
    val people by model.people.collectAsState()
    val status by model.status.collectAsState()
    val visible by model.visible.collectAsState()
    val btStatus by bluetooth.status.collectAsState()
    val peers by bluetooth.peers.collectAsState()
    val connected by bluetooth.connected.collectAsState()
    val lines by bluetooth.lines.collectAsState()
    var selected by remember {mutableStateOf<BluetoothDevice?>(null)}
    var text by remember {mutableStateOf("")}
    var locating by remember {mutableStateOf(false)}
    var locationError by remember {mutableStateOf<String?>(null)}
    var signal by remember {mutableStateOf<CancellationSignal?>(null)}
    var bluetoothMode by remember {mutableStateOf(false)}
    var meshMode by remember {mutableStateOf(false)}
    fun locate() {
        signal?.cancel();signal=CancellationSignal();locating=true;locationError=null
        val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if(!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locating=false;locationError="Включите геолокацию и сетевое определение местоположения";return
            }
            LocationManagerCompat.getCurrentLocation(manager,LocationManager.NETWORK_PROVIDER,signal,
                ContextCompat.getMainExecutor(context)) { location ->
                locating=false
                if(location==null || android.os.SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos>120_000_000_000L)
                    locationError="Не удалось получить свежее местоположение"
                else model.publish(location.latitude,location.longitude)
            }
        } catch(_:Exception) {locating=false;locationError="Нет доступа к местоположению"}
    }
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if(it) locate() else locationError="Без разрешения поиск по местоположению недоступен"
    }
    val bluetoothPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if(it) bluetooth.paired() else bluetooth.status.value="Доступ к Bluetooth не разрешён"
    }
    fun requestLocation() {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) locate()
        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    LaunchedEffect(accountId) {
        accountId?.let { id ->
            val key="automatic:$id"
            automatic=preferences.getBoolean(key,false)
            if(automatic) requestLocation()
            else if(!preferences.contains(key)) consentDialog=true
        }
    }
    if(consentDialog) AlertDialog(onDismissRequest={consentDialog=false},
        title={Text("Показывать вас людям рядом?")},
        text={Text("Ваше имя и username будут видны пользователям примерно в радиусе 10 км. Mango отправляет серверу округлённое местоположение. Видимость будет включаться при открытии этого экрана и выключаться при выходе; при потере сети — в течение 2 минут.")},
        confirmButton={TextButton(enabled=accountId!=null,onClick={
            accountId?.let {preferences.edit { putBoolean("automatic:$it",true) }}
            automatic=true;consentDialog=false;requestLocation()
        }) {Text("Согласен, включить")}},
        dismissButton={TextButton(onClick={
            accountId?.let {preferences.edit { putBoolean("automatic:$it",false) }}
            automatic=false;consentDialog=false
        }) {Text("Не включать")}})
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {signal?.cancel();locating=false;model.hide();bluetooth.close()}
    DisposableEffect(Unit) {onDispose {signal?.cancel();model.hide();bluetooth.close()}}
    if(meshMode){MeshPanel(onBack={meshMode=false});return}
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TextButton(onClick=onBack) {Text("Назад")}
        Text("Люди рядом",style=MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick={signal?.cancel();model.hide();bluetooth.close();meshMode=true}){Text("Без интернета · Bluetooth и Wi-Fi")}
        Row {
            TextButton(onClick={bluetooth.close();bluetoothMode=false}) {Text("По местоположению")}
            TextButton(onClick={signal?.cancel();locating=false;model.hide();bluetoothMode=true}) {Text("Bluetooth")}
        }
        if(!bluetoothMode) {
            Text("Включая видимость, вы разрешаете показывать ваше имя и username людям поблизости. Сервер получает округлённое местоположение. Координаты и расстояние другим не показываются.")
            Text(status)
            if(automatic) TextButton(onClick={
                accountId?.let {preferences.edit { putBoolean("automatic:$it",false) }}
                automatic=false;signal?.cancel();locating=false;model.hide()
            }) {Text("Отключить автоматическую видимость")}
            locationError?.let {Text(it)}
            Button(enabled=accountId!=null&&!locating&&!visible,onClick={
                if(automatic) requestLocation() else consentDialog=true
            }) {Text(if(locating) "Определение местоположения…" else "Стать видимым на 2 минуты")}
            if(visible) {
                Row {TextButton(onClick=model::refresh) {Text("Обновить")};TextButton(onClick=model::hide) {Text("Скрыться")}}
                if(people.isEmpty()) Text("Поблизости пока нет видимых пользователей")
                people.forEach { person ->
                    OutlinedButton(onClick={model.chat(person.id,onConversation)}) {Text("${person.displayName} · @${person.username}")}
                }
            }
        } else {
            Text("Личный текстовый чат без интернета. Сначала сопрягите телефоны в настройках Android. На обоих выберите друг друга; на одном нажмите «Ожидать», на другом — «Подключиться».")
            Text("Имя Bluetooth не подтверждает аккаунт Mango. Сверьте устройство с собеседником. Используется защищённое сопряжение Bluetooth, не MLS. История временная, без синхронизации с сервером.")
            TextButton(onClick={context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}) {Text("Настройки Bluetooth")}
            Button(onClick={if(Build.VERSION.SDK_INT>=31) bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) else bluetooth.paired()}) {Text("Сопряжённые устройства")}
            peers.forEach { peer -> TextButton(onClick={selected=peer}) {
                Text((if(selected==peer) "✓ " else "")+ runCatching {peer.name ?: peer.address}.getOrDefault("Устройство"))
            } }
            selected?.let { peer -> Row {
                Button(onClick={bluetooth.start(peer,true)},enabled=!connected) {Text("Ожидать")}
                TextButton(onClick={bluetooth.start(peer,false)},enabled=!connected) {Text("Подключиться")}
            } }
            Text(btStatus)
            TextButton(onClick=bluetooth::close) {Text("Отключиться")}
            lines.forEach { line -> Text((if(line.mine) "Вы: " else "Собеседник: ")+line.text+
                if(line.mine) (if(line.received) " · получено" else " · без подтверждения") else "") }
            OutlinedTextField(value=text,onValueChange={if(it.toByteArray().size<=4096)text=it},label={Text("Сообщение")},modifier=Modifier.fillMaxWidth())
            Button(onClick={bluetooth.send(text);text=""},enabled=connected&&text.isNotBlank()) {Text("Отправить по Bluetooth")}
        }
    }
}
