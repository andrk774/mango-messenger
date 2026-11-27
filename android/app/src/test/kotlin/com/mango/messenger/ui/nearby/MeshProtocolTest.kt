package com.mango.messenger.ui.nearby

import java.io.*
import org.junit.Test
import org.junit.Assert.*

class MeshProtocolTest {
    private fun wire(p:MeshPacket):MeshPacket {
        val out=ByteArrayOutputStream();p.write(DataOutputStream(out))
        return MeshPacket.read(DataInputStream(ByteArrayInputStream(out.toByteArray())))
    }
    @Test fun threeNodesRelayAcrossLinksWithoutDuplicates() {
        val code=MeshProtocol.newCode();val a=MeshProtocol(code);val b=MeshProtocol(code);val c=MeshProtocol(code)
        val packet=a.create("Alice","Hello, without internet 👋")
        assertEquals("Alice",b.accept(wire(packet))!!.sender)
        val forwarded=packet.copy(hops=packet.hops-1)
        assertEquals("Hello, without internet 👋",c.accept(wire(forwarded))!!.text)
        assertNull(a.accept(wire(forwarded)));assertNull(b.accept(wire(forwarded)));assertNull(c.accept(wire(packet)))
    }
    @Test fun wrongCodeTamperingAndExpiredPacketsAreRejected() {
        var now=1000000L;val code=MeshProtocol.newCode();val a=MeshProtocol(code){now};val b=MeshProtocol(code){now}
        val packet=a.create("Alice","Secret")
        fun rejected(block:()->Unit){try{block();fail("Expected rejection")}catch(_:Exception){}}
        rejected{MeshProtocol(MeshProtocol.newCode()){now}.accept(packet)}
        rejected{b.accept(packet.copy(issued=packet.issued+1))}
        val broken=packet.ciphertext.clone();broken[0]=(broken[0].toInt() xor 1).toByte()
        rejected{b.accept(packet.copy(ciphertext=broken))}
        assertEquals("Secret",b.accept(packet)!!.text)
        now+=121000;rejected{MeshProtocol(code){now}.accept(packet)}
        rejected{wire(packet.copy(hops=5))}
    }
    @Test fun frameSizesAndRoomKeysAreBounded() {
        try{MeshProtocol("abc");fail()}catch(_:Exception){}
        val codec=MeshProtocol(MeshProtocol.newCode())
        try{codec.create("name","x".repeat(4097));fail()}catch(_:Exception){}
        val packet=codec.create("name","text")
        try{wire(packet.copy(ciphertext=ByteArray(5000)));fail()}catch(_:Exception){}
        assertEquals(0,wire(packet.copy(hops=0)).hops)
    }
}
