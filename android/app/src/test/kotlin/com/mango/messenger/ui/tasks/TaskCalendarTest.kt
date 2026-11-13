package com.mango.messenger.ui.tasks

import com.mango.core.model.Task
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskCalendarTest {
    @Test
    fun `calendar assigns deadline using local day rather than UTC day`() {
        val task = mockk<Task> { every { dueAt } returns Instant.parse("2026-11-13T22:00:00Z") }
        val noDate = mockk<Task> { every { dueAt } returns null }
        val zone = ZoneId.of("Asia/Yekaterinburg")
        assertEquals(listOf(task), tasksOnDate(listOf(task, noDate), LocalDate.parse("2026-11-14"), zone))
        assertEquals(emptyList<Task>(), tasksOnDate(listOf(task, noDate), LocalDate.parse("2026-11-13"), zone))
    }
}
