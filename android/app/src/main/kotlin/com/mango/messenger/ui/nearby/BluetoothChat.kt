package com.mango.messenger.ui.nearby

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class BluetoothLine(val id:UUID,val text:String,val mine:Boolean,val received:Boolean=false)

/** Foreground-only, one paired peer. Secure RFCOMM only; no cloud or MLS identity claim. */
@SuppressLint("MissingPermission")
class BluetoothChat(context:Context,private val scope:CoroutineScope) {
    private val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
    val status=MutableStateFlow("Не подключено")
    val connected=MutableStateFlow(false)
    val lines=MutableStateFlow<List<BluetoothLine>>(emptyList())
    val peers=MutableStateFlow<List<BluetoothDevice>>(emptyList())
    @Volatile private var socket:BluetoothSocket?=null
    @Volatile private var server:BluetoothServerSocket?=null
    private var job:Job?=null
    private var timeout:Job?=null
    private val lifecycleLock=Any()
    private var generation=0L
    private val writeLock=Any()
    private val service=UUID.fromString("eec642e9-91df-4ddf-a2ab-c43ef0088842")
    fun paired() {
        runCatching {
            check(adapter?.isEnabled==true)
            peers.value=adapter!!.bondedDevices.sortedBy { it.address }
            status.value=if(peers.value.isEmpty()) "Сначала выполните сопряжение в настройках Bluetooth" else "Выберите сопряжённый телефон"
        }.onFailure { status.value="Включите Bluetooth и разрешите доступ к устройствам" }
    }
    fun close() {
        synchronized(lifecycleLock) {
        generation++
        timeout?.cancel();job?.cancel()
        runCatching {server?.close()};runCatching {socket?.close()}
        server=null;socket=null;connected.value=false
        status.value="Отключено. Переписка хранится только на этом экране"
        }
    }
    fun start(peer:BluetoothDevice,listen:Boolean) {
        close();lines.value=emptyList()
        val ticket=synchronized(lifecycleLock) { generation }
        status.value=if(listen)"Ожидание выбранного телефона…" else "Подключение…"
        job=scope.launch(Dispatchers.IO) {
            var active:BluetoothSocket?=null
            try {
                check(adapter?.isEnabled==true && peer.bondState==BluetoothDevice.BOND_BONDED)
                if(listen) {
                    val listener=adapter!!.listenUsingRfcommWithServiceRecord("Mango offline",service)
                    synchronized(lifecycleLock) {
                        if(generation!=ticket) {listener.close();throw CancellationException()}
                        server=listener
                    }
                    try {
                        active=listener.accept(30_000)
                        check(active.remoteDevice.address==peer.address) { "Unexpected peer" }
                    } finally {listener.close();synchronized(lifecycleLock) {if(server===listener)server=null}}
                } else {
                    active=peer.createRfcommSocketToServiceRecord(service)
                }
                synchronized(lifecycleLock) {
                    if(generation!=ticket) {active.close();throw CancellationException()}
                    socket=active
                }
                if(!listen) {
                    active.connect()
                }
                ensureActive()
                synchronized(lifecycleLock) {
                    if(generation!=ticket) throw CancellationException()
                    timeout?.cancel()
                    connected.value=true;status.value="Подключено: ${peer.name ?: peer.address}"
                }
                val input=DataInputStream(active.inputStream)
                val seen=LinkedHashSet<UUID>()
                while(isActive) {
                    val frame=BluetoothFrame.read(input)
                    ensureActive()
                    synchronized(lifecycleLock) {
                    if(generation!=ticket) throw CancellationException()
                    if(frame.ack) lines.update { list -> list.map { if(it.mine&&it.id==frame.id) it.copy(received=true) else it } }
                    else {
                        if(seen.add(frame.id)) lines.update { (it+BluetoothLine(frame.id,frame.text!!,false,true)).takeLast(100) }
                        if(seen.size>1000) seen.remove(seen.first())
                    }
                    }
                    if(!frame.ack) synchronized(writeLock) { BluetoothFrame(frame.id,null,true).write(DataOutputStream(active.outputStream)) }
                }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {synchronized(lifecycleLock) {if(generation==ticket)status.value="Связь прервана или телефон не ответил. Повторите подключение"}}
            finally {runCatching {active?.close()};synchronized(lifecycleLock) {if(generation==ticket)connected.value=false}}
        }
        timeout=scope.launch {delay(30_000); synchronized(lifecycleLock) {if(generation==ticket&&!connected.value) close()}}
    }
    fun send(text:String) {
        if(text.isBlank() || text.toByteArray().size>4096 || !connected.value) return
        val frame=BluetoothFrame(UUID.randomUUID(),text)
        val active=socket ?: return
        lines.update { (it+BluetoothLine(frame.id,text,true)).takeLast(100) }
        scope.launch(Dispatchers.IO) {
            try { synchronized(writeLock) {frame.write(DataOutputStream(active.outputStream))} }
            catch(_:Exception) {synchronized(lifecycleLock) {if(socket===active) {close();status.value="Не удалось отправить. Сообщение не доставлено"}}}
        }
    }
}
