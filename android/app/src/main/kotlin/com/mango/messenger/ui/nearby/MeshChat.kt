package com.mango.messenger.ui.nearby

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Foreground shared room. Same packets bridge RFCOMM and private-LAN TCP links. */
@SuppressLint("MissingPermission")
class MeshChat(context:Context) {
    private val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
    private val service=UUID.fromString("0629c221-1169-4702-a449-b87d268d2c12")
    private var scope:CoroutineScope?=null
    private var protocol:MeshProtocol?=null
    private val resources=ConcurrentHashMap.newKeySet<Closeable>()
    private val links=ConcurrentHashMap<UUID,Channel<MeshPacket>>()
    val running=MutableStateFlow(false)
    val status=MutableStateFlow("Комната выключена")
    val addresses=MutableStateFlow("")
    val count=MutableStateFlow(0)
    val lines=MutableStateFlow<List<MeshMessage>>(emptyList())
    val peers=MutableStateFlow<List<BluetoothDevice>>(emptyList())
    private var name="Участник"
    fun start(code:String,alias:String) {
        stop()
        try {protocol=MeshProtocol(code);require(alias.isNotBlank()&&alias.length<=32)}
        catch(_:Exception){status.value="Нужен код комнаты (43 символа) и имя до 32 символов";return}
        name=alias;lines.value=emptyList();running.value=true
        val session=CoroutineScope(SupervisorJob()+Dispatchers.IO);scope=session
        status.value="Комната включена. Подключите соседние устройства"
        session.launch {
            try {
                val server=ServerSocket(0);resources.add(server)
                if(!isActive){server.close();return@launch}
                addresses.value=NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&!it.isLoopback}.flatMap{it.inetAddresses.toList()}
                    .filter{it is Inet4Address&&it.isSiteLocalAddress}.joinToString("\n"){"${it.hostAddress}:${server.localPort}"}
                if(addresses.value.isEmpty())addresses.value="Подключите Wi-Fi или включите точку доступа и перезапустите комнату"
                while(isActive){val socket=server.accept();if(!socket.inetAddress.isSiteLocalAddress||links.size>=8)socket.close() else attach(session,socket,socket.getInputStream(),socket.getOutputStream())}
            }catch(_:Exception){if(session.isActive)status.value="Wi-Fi недоступен. Можно использовать Bluetooth"}
        }
    }
    fun paired() {runCatching{check(adapter?.isEnabled==true);peers.value=adapter.bondedDevices.sortedBy{it.address}}.onFailure{status.value="Включите Bluetooth и разрешите доступ"}}
    fun listenBluetooth() {
        val session=scope?:return
        session.launch {
            try {
                check(adapter?.isEnabled==true)
                val server=adapter.listenUsingRfcommWithServiceRecord("Mango mesh",service);resources.add(server)
                if(!isActive){server.close();return@launch}
                status.value="Ожидаю сопряжённые телефоны по Bluetooth"
                while(isActive){val socket=server.accept();if(socket.remoteDevice.bondState!=BluetoothDevice.BOND_BONDED||links.size>=8)socket.close() else attach(session,socket,socket.inputStream,socket.outputStream)}
            }catch(_:Exception){if(session.isActive)status.value="Не удалось включить ожидание Bluetooth"}
        }
    }
    fun connectBluetooth(peer:BluetoothDevice) {
        val session=scope?:return
        if(links.size>=8)return
        session.launch {
            var socket:BluetoothSocket?=null
            try {
                require(peer.bondState==BluetoothDevice.BOND_BONDED)
                socket=peer.createRfcommSocketToServiceRecord(service);resources.add(socket)
                val pending=socket;val timeout=launch{delay(15_000);runCatching{pending.close()}}
                socket.connect();timeout.cancel()
                attach(session,socket,socket.inputStream,socket.outputStream)
            }catch(_:Exception){runCatching{socket?.close()};socket?.let{resources.remove(it)};if(session.isActive)status.value="Bluetooth не подключён. На соседнем телефоне включите ожидание"}
        }
    }
    fun connectWifi(address:String) {
        val session=scope?:return
        if(links.size>=8)return
        session.launch {
            var socket:Socket?=null
            try {
                val parts=address.trim().split(':');require(parts.size==2&&parts[0].matches(Regex("[0-9.]{7,15}")))
                val host=InetAddress.getByName(parts[0]);require(host is Inet4Address&&host.isSiteLocalAddress)
                val port=parts[1].toInt();require(port in 1..65535)
                socket=Socket();resources.add(socket);socket.connect(InetSocketAddress(host,port),5000)
                attach(session,socket,socket.getInputStream(),socket.getOutputStream())
            }catch(_:Exception){runCatching{socket?.close()};socket?.let{resources.remove(it)};if(session.isActive)status.value="Проверьте локальный IP:порт и общую Wi-Fi сеть"}
        }
    }
    private fun attach(session:CoroutineScope,resource:Closeable,input:InputStream,output:OutputStream) {
        resources.add(resource)
        if(!session.isActive||links.size>=8){resource.close();resources.remove(resource);return}
        val id=UUID.randomUUID();val queue=Channel<MeshPacket>(16);links[id]=queue;count.value=links.size
        val codec=protocol?:return
        session.launch {
            val writer=launch {try{for(packet in queue)packet.write(DataOutputStream(output))}finally{runCatching{resource.close()}}}
            try {
                val stream=DataInputStream(input);var window=System.currentTimeMillis();var received=0
                while(isActive){
                    val packet=MeshPacket.read(stream)
                    val now=System.currentTimeMillis();if(now-window>=60_000){window=now;received=0};require(++received<=120)
                    val message=codec.accept(packet)?:continue
                    if(!isActive)break
                    lines.update{(it+message).takeLast(100)}
                    if(packet.hops>0)for((peer,q) in links)if(peer!=id)q.trySend(packet.copy(hops=packet.hops-1))
                }
            }catch(_:Exception){if(session.isActive)status.value="Связь закрыта: проверьте код комнаты, часы и соединение"}
            finally {writer.cancel();queue.close();links.remove(id);runCatching{resource.close()};resources.remove(resource);count.value=links.size}
        }
    }
    fun send(text:String):Boolean {
        val codec=protocol?:return false
        if(links.isEmpty()){status.value="Нет соседей: сообщение не отправлено";return false}
        return try {
            val packet=codec.create(name,text)
            val sent=links.values.count{it.trySend(packet).isSuccess}
            if(sent==0){status.value="Очередь занята. Повторите отправку";false}
            else {lines.update{(it+MeshMessage(packet.id,name,text,true)).takeLast(100)};status.value="Передано в очередь $sent соединений; получение адресатом не подтверждено";true}
        }catch(_:Exception){status.value="Сообщение слишком длинное или превышен лимит комнаты";false}
    }
    fun stop() {
        scope?.cancel();scope=null
        resources.toList().forEach{runCatching{it.close()}};resources.clear();links.values.forEach{it.close()};links.clear()
        protocol=null;running.value=false;count.value=0;addresses.value="";status.value="Комната выключена";lines.value=emptyList()
    }
}
