package com.mango.messenger.ui.misc

import org.junit.Assert.*
import org.junit.Test

class PhoneContactTest {
    @Test fun repeatImportsUseSameIdAcrossFormattingAndNames() {
        assertEquals(phoneContact("One", "+1 (234) 567-89").id,phoneContact("Renamed", "123456789").id)
        assertEquals(phoneContact("One",email="FRIEND@example.test").id,phoneContact("Two",email="friend@example.test").id)
        assertNotEquals(phoneContact("One","123").id,phoneContact("One","456").id)
    }
    @Test fun noProviderIdentifiersOrUnboundedNamesAreUploaded() {
        val row=phoneContact("A".repeat(200),phone="+123")
        assertEquals(120,row.name.length)
        assertEquals("+123",row.phone)
        assertEquals("",row.email)
        assertNull(row.updatedAt)
    }
}
