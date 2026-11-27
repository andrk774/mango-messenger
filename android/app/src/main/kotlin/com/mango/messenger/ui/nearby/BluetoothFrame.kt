package com.mango.messenger.ui.nearby

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Bounded frame; ACK confirms receipt in the peer's screen, not durable storage. */
data class BluetoothFrame(val id:UUID,val text:String?,val ack:Boolean=false) {
    fun write(out:DataOutputStream) {
        val bytes = text.orEmpty().toByteArray(Charsets.UTF_8)
        require((ack && bytes.isEmpty()) || (!ack && bytes.size in 1..4096))
        out.writeInt(0x4d414e31);out.writeBoolean(ack)
        out.writeLong(id.mostSignificantBits);out.writeLong(id.leastSignificantBits)
        out.writeInt(bytes.size);out.write(bytes);out.flush()
    }
    companion object {
        fun read(input:DataInputStream):BluetoothFrame {
            require(input.readInt()==0x4d414e31) { "Unsupported peer protocol" }
            val ack=input.readBoolean()
            val id=UUID(input.readLong(),input.readLong())
            val size=input.readInt()
            require(if(ack) size==0 else size in 1..4096) { "Invalid frame size" }
            val bytes=ByteArray(size);input.readFully(bytes)
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            return BluetoothFrame(id,if(ack)null else text,ack)
        }
    }
}
