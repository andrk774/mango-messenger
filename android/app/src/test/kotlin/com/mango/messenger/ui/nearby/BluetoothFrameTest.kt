package com.mango.messenger.ui.nearby

import java.io.*
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class BluetoothFrameTest {
    @Test fun roundTripAndAcknowledgement() {
        val frame=BluetoothFrame(UUID.randomUUID(),"Привет 👋")
        val bytes=ByteArrayOutputStream()
        frame.write(DataOutputStream(bytes))
        BluetoothFrame(frame.id,null,true).write(DataOutputStream(bytes))
        val input=DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(frame,BluetoothFrame.read(input))
        assertEquals(BluetoothFrame(frame.id,null,true),BluetoothFrame.read(input))
    }
    @Test fun rejectOversizedNegativeAndTruncatedFrames() {
        for(size in listOf(-1,4097,Int.MAX_VALUE,10)) {
            val bytes=ByteArrayOutputStream()
            DataOutputStream(bytes).apply {writeInt(0x4d414e31);writeBoolean(false);writeLong(1);writeLong(2);writeInt(size)}
            try {BluetoothFrame.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())));fail("must reject")}
            catch(_:IllegalArgumentException) {} catch(_:EOFException) {}
        }
    }
    @Test fun rejectWrongProtocol() {
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).writeInt(0)
        try {BluetoothFrame.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())));fail("must reject")}
        catch(_:IllegalArgumentException) {}
    }
}
