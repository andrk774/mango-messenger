package com.mango.core.data.attachment

import org.junit.Test
import org.junit.Assert.assertEquals

class AttachmentMimeTest {
    @Test fun `m4a recording mislabeled by provider uses mp4 audio`() {
        assertEquals("audio/mp4", normalizeAttachmentMime("voice.m4a", "audio/mpeg"))
        assertEquals("audio/mp4", normalizeAttachmentMime("voice.M4A", "audio/x-m4a"))
    }
    @Test fun `mp3 and other provider types are preserved`() {
        assertEquals("audio/mpeg", normalizeAttachmentMime("voice.mp3", "audio/mpeg"))
        assertEquals("image/png", normalizeAttachmentMime("image.png", "image/png"))
    }
}
