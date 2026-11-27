package com.mango.messenger.ui.nearby

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A temporary shared-key room, not an MLS conversation or verified Mango identity. */
data class MeshPacket(val id:UUID,val issued:Long,val hops:Int,val nonce:ByteArray,val ciphertext:ByteArray) {
    fun write(out:DataOutputStream) {
        out.writeInt(0x4d4d5331);out.writeLong(id.mostSignificantBits);out.writeLong(id.leastSignificantBits)
        out.writeLong(issued);out.writeInt(hops);out.write(nonce);out.writeInt(ciphertext.size);out.write(ciphertext);out.flush()
    }
    companion object {
        fun read(input:DataInputStream):MeshPacket {
            require(input.readInt()==0x4d4d5331)
            val id=UUID(input.readLong(),input.readLong());val issued=input.readLong();val hops=input.readInt()
            require(hops in 0..4)
            val nonce=ByteArray(12);input.readFully(nonce)
            val size=input.readInt();require(size in 17..4608)
            val cipher=ByteArray(size);input.readFully(cipher)
            return MeshPacket(id,issued,hops,nonce,cipher)
        }
    }
}
data class MeshMessage(val id:UUID,val sender:String,val text:String,val mine:Boolean=false)
class MeshProtocol(code:String,private val now:()->Long={System.currentTimeMillis()}) {
    private val key=SecretKeySpec(Base64.getUrlDecoder().decode(code.trim()).also{require(it.size==32)},"AES")
    private val seen=LinkedHashMap<UUID,Long>()
    private fun aad(p:MeshPacket)=ByteBuffer.allocate(28).putInt(0x4d4d5331).putLong(p.id.mostSignificantBits).putLong(p.id.leastSignificantBits).putLong(p.issued).array()
    @Synchronized fun create(sender:String,text:String):MeshPacket {
        require(sender.isNotBlank()&&sender.length<=32&&text.isNotBlank()&&text.toByteArray().size<=4096)
        val plain=ByteArrayOutputStream().also{DataOutputStream(it).use{d->d.writeUTF(sender);d.writeUTF(text)}}.toByteArray()
        val packet=MeshPacket(UUID.randomUUID(),now(),4,ByteArray(12).also{SecureRandom().nextBytes(it)},byteArrayOf())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,packet.nonce));cipher.updateAAD(aad(packet))
        remember(packet);return packet.copy(ciphertext=cipher.doFinal(plain))
    }
    private fun remember(p:MeshPacket){seen.entries.removeAll{it.value<now()-120_000};require(seen.size<2048){"Room rate limit"};seen[p.id]=p.issued}
    @Synchronized fun accept(p:MeshPacket):MeshMessage? {
        require(p.hops in 0..4 && p.nonce.size==12 && p.ciphertext.size in 17..4608)
        require(p.issued>=now()-120_000&&p.issued<=now()+30_000){"Expired message or clock mismatch"}
        if(seen.containsKey(p.id))return null
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,p.nonce));cipher.updateAAD(aad(p))
        val data=DataInputStream(ByteArrayInputStream(cipher.doFinal(p.ciphertext)))
        val name=data.readUTF();val text=data.readUTF();require(data.available()==0&&name.isNotBlank()&&name.length<=32&&text.isNotBlank()&&text.toByteArray().size<=4096)
        remember(p);return MeshMessage(p.id,name,text)
    }
    companion object { fun newCode()=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also{SecureRandom().nextBytes(it)}) }
}
