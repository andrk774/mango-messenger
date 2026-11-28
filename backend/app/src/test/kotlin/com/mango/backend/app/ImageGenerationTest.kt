package com.mango.backend.app

import com.mango.backend.common.AppException
import org.junit.jupiter.api.Test
import kotlin.test.*

class ImageGenerationTest {
    @Test fun `prompt bounds and trimming`() {
        assertEquals("tiger",validateImagePrompt("  tiger  "))
        assertFailsWith<AppException.BadRequest>{validateImagePrompt("  ")}
        assertFailsWith<AppException.BadRequest>{validateImagePrompt("a".repeat(1501))}
    }
    @Test fun `only administrator and owner can configure paid provider`() {
        assertTrue(ServiceRoles.allows("ADMIN","manage"))
        assertTrue(ServiceRoles.allows("OWNER","manage"))
        for(role in listOf("USER","MODERATOR","ANALYST","BOARD","MANAGER"))assertFalse(ServiceRoles.allows(role,"manage"))
    }
}
