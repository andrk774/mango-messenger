package com.mango.core.designsystem

import com.mango.core.designsystem.component.label
import com.mango.core.model.TaskPriority
import com.mango.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка подписей статусов и приоритетов.
 *
 * Это единственная часть design system, проверяемая на JVM: остальное —
 * Compose-компоненты, для которых нужны UI-тесты на устройстве
 * (в текущем окружении недоступны).
 */
class LabelsTest {

    @Test
    fun `every task status has a non empty russian label`() {
        TaskStatus.entries.forEach { status ->
            val label = status.label()
            assertTrue("Пустая подпись для $status", label.isNotBlank())
            assertTrue(
                "Подпись для $status должна быть на русском: $label",
                label.any { it in 'А'..'я' },
            )
        }
    }

    @Test
    fun `every priority has a non empty russian label`() {
        TaskPriority.entries.forEach { priority ->
            val label = priority.label()
            assertTrue("Пустая подпись для $priority", label.isNotBlank())
            assertTrue(
                "Подпись для $priority должна быть на русском: $label",
                label.any { it in 'А'..'я' },
            )
        }
    }

    @Test
    fun `labels are unique so the user can tell states apart`() {
        val statusLabels = TaskStatus.entries.map { it.label() }
        assertEquals(statusLabels.size, statusLabels.toSet().size)

        val priorityLabels = TaskPriority.entries.map { it.label() }
        assertEquals(priorityLabels.size, priorityLabels.toSet().size)
    }
}
