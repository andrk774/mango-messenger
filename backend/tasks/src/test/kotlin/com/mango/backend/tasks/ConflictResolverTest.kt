package com.mango.backend.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Разрешение конфликтов по полям.
 *
 * Здесь проверяется правило, от которого зависит, чья правка выживет при
 * одновременном редактировании с двух устройств. Ошибка не проявится
 * сбоем — она проявится молча потерянной правкой, поэтому случаи
 * перечислены исчерпывающе.
 */
class ConflictResolverTest {

    private val now = 1_000_000L

    private val current = mapOf(
        "title" to "Исходное",
        "description" to "Описание",
        "status" to "TODO",
        "priority" to "MEDIUM",
        "due_at" to null,
    )

    private val versions = mapOf(
        "title" to 500L,
        "description" to 500L,
        "status" to 500L,
        "priority" to 500L,
        "due_at" to 500L,
    )

    private fun resolve(vararg changes: FieldChange) =
        ConflictResolver.resolve(current, versions, changes.toList(), now)

    @Test
    fun `более новая правка применяется`() {
        val result = resolve(FieldChange("title", "Новое", 600))

        assertEquals(mapOf("title" to "Новое"), result.applied)
        assertTrue(result.rejected.isEmpty())
        assertEquals(600L, result.fieldVersions["title"])
    }

    @Test
    fun `устаревшая правка проигрывает серверу`() {
        val result = resolve(FieldChange("title", "Старое", 400))

        assertTrue(result.applied.isEmpty())
        assertEquals(listOf("title"), result.rejected)
        assertEquals(500L, result.fieldVersions["title"], "метка сервера не должна откатываться")
    }

    @Test
    fun `правки разных полей не конфликтуют`() {
        // Главный довод в пользу сравнения по полям: один меняет срок,
        // другой — приоритет. Блокировка по версии объекта отвергла бы одну
        // из правок, хотя спорить не о чем.
        val result = resolve(
            FieldChange("priority", "HIGH", 600),
            FieldChange("due_at", "2026-12-01T10:00:00Z", 600),
        )

        assertEquals(
            mapOf("priority" to "HIGH", "due_at" to "2026-12-01T10:00:00Z"),
            result.applied,
        )
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `в одном запросе часть полей применяется, часть отклоняется`() {
        val result = resolve(
            FieldChange("title", "Новое", 600),
            FieldChange("priority", "LOW", 400),
        )

        assertEquals(mapOf("title" to "Новое"), result.applied)
        assertEquals(listOf("priority"), result.rejected)
    }

    @Test
    fun `при равных метках завершение сильнее возврата в работу`() {
        val result = ConflictResolver.resolve(
            current = current + ("status" to "IN_PROGRESS"),
            currentVersions = versions,
            changes = listOf(FieldChange("status", "DONE", 500)),
            serverNow = now,
        )

        assertEquals(mapOf("status" to "DONE"), result.applied)
    }

    @Test
    fun `при равных метках возврат в работу не отменяет завершение`() {
        val result = ConflictResolver.resolve(
            current = current + ("status" to "DONE"),
            currentVersions = versions,
            changes = listOf(FieldChange("status", "TODO", 500)),
            serverNow = now,
        )

        assertTrue(result.applied.isEmpty())
        assertEquals(listOf("status"), result.rejected)
    }

    @Test
    fun `при равных метках завершение сильнее отмены`() {
        val result = ConflictResolver.resolve(
            current = current + ("status" to "CANCELLED"),
            currentVersions = versions,
            changes = listOf(FieldChange("status", "DONE", 500)),
            serverNow = now,
        )

        assertEquals(mapOf("status" to "DONE"), result.applied)
    }

    @Test
    fun `при равных метках прочие поля сохраняют значение сервера`() {
        // Правило обязано быть детерминированным: два сервера, получившие
        // одни и те же правки в разном порядке, должны прийти к одному итогу.
        val result = resolve(FieldChange("title", "Претендент", 500))

        assertTrue(result.applied.isEmpty())
        assertEquals(listOf("title"), result.rejected)
    }

    @Test
    fun `повтор той же правки не считается конфликтом`() {
        // Клиент повторил запрос после обрыва связи. Значение уже такое,
        // и сообщать ему «изменено на другом устройстве» было бы неправдой.
        val result = resolve(FieldChange("title", "Исходное", 400))

        assertTrue(result.applied.isEmpty())
        assertTrue(result.rejected.isEmpty(), "получено: ${result.rejected}")
    }

    @Test
    fun `часы устройства из будущего не дают вечного преимущества`() {
        // Устройство с часами на год вперёд выигрывало бы любой конфликт
        // в течение года. Метка ограничивается временем сервера.
        val result = resolve(FieldChange("title", "Из будущего", now + 365L * 24 * 3600 * 1000))

        assertEquals(mapOf("title" to "Из будущего"), result.applied)
        assertEquals(
            now,
            result.fieldVersions["title"],
            "Сохранённая метка не должна уходить дальше времени сервера",
        )

        // И следующая честная правка уже может её перебить.
        val next = ConflictResolver.resolve(
            current = current + ("title" to "Из будущего"),
            currentVersions = result.fieldVersions,
            changes = listOf(FieldChange("title", "Честная правка", now + 1)),
            serverNow = now + 10,
        )
        assertEquals(mapOf("title" to "Честная правка"), next.applied)
    }

    @Test
    fun `поле без сохранённой метки принимает любую правку`() {
        val result = ConflictResolver.resolve(
            current = current,
            currentVersions = emptyMap(),
            changes = listOf(FieldChange("remind_at", "2026-12-01T09:00:00Z", 1)),
            serverNow = now,
        )

        assertEquals(mapOf("remind_at" to "2026-12-01T09:00:00Z"), result.applied)
    }

    @Test
    fun `служебные поля через частичное изменение не меняются`() {
        assertFailsWith<IllegalArgumentException> {
            resolve(FieldChange("created_by", "кто-то другой", 600))
        }
        assertFailsWith<IllegalArgumentException> {
            resolve(FieldChange("version", "999", 600))
        }
    }

    @Test
    fun `клиент, видевший актуальную версию, не проигрывает из-за отстающих часов`() {
        // Часы устройства отстают: метка правки меньше сохранённой. Но
        // клиент правил актуальную версию — конкурирующих правок не было,
        // и отклонять его изменение значило бы спорить с ним самим.
        val result = ConflictResolver.resolve(
            current = current,
            currentVersions = versions,
            changes = listOf(FieldChange("title", "Правка с отстающих часов", 100)),
            serverNow = now,
            clientSawLatest = true,
        )

        assertEquals(mapOf("title" to "Правка с отстающих часов"), result.applied)
        assertTrue(result.rejected.isEmpty())
        assertEquals(
            500L,
            result.fieldVersions["title"],
            "Метка не должна откатываться назад даже на быстром пути",
        )
    }

    @Test
    fun `быстрый путь не отменяет ограничение служебных полей`() {
        assertFailsWith<IllegalArgumentException> {
            ConflictResolver.resolve(
                current, versions,
                listOf(FieldChange("created_by", "x", 600)),
                now,
                clientSawLatest = true,
            )
        }
    }

    @Test
    fun `снятие значения поля тоже является правкой`() {
        val result = ConflictResolver.resolve(
            current = current + ("due_at" to "2026-12-01T10:00:00Z"),
            currentVersions = versions,
            changes = listOf(FieldChange("due_at", null, 600)),
            serverNow = now,
        )

        assertEquals(mapOf("due_at" to null), result.applied)
    }
}
