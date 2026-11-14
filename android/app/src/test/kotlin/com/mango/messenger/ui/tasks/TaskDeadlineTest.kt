package com.mango.messenger.ui.tasks

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDeadlineTest {
    private val zone = ZoneId.of("Asia/Yekaterinburg")

    @Test
    fun `editing title keeps existing deadline time`() {
        val previous = Instant.parse("2026-11-14T10:30:00Z")
        assertEquals(previous.toEpochMilli(), editedDeadline("2026-11-14", previous, zone))
    }

    @Test
    fun `changing date uses selected local day`() {
        assertEquals(Instant.parse("2026-11-14T19:00:00Z").toEpochMilli(),
            editedDeadline("2026-11-15", Instant.parse("2026-11-14T10:30:00Z"), zone))
    }

    @Test
    fun `empty date removes deadline`() {
        assertNull(editedDeadline("", Instant.parse("2026-11-14T10:30:00Z"), zone))
    }
}
