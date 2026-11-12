package com.mango.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DomainModelTest {

    private fun user(displayName: String, username: String = "u") = User(
        id = UserId("1"),
        username = username,
        displayName = displayName,
    )

    @Test
    fun `initials take first letters of up to two words`() {
        assertEquals("ИП", user("Иван Петров").initials)
        assertEquals(
            "Больше двух букв в аватар не влезает",
            "ИП",
            user("Иван Петрович Сидоров").initials,
        )
        assertEquals("И", user("Иван").initials)
        assertEquals("Двойные пробелы не дают пустых инициалов", "ИП", user("Иван  Петров").initials)
    }

    @Test
    fun `initials fall back to username when display name is blank`() {
        assertEquals("N", user(displayName = "   ", username = "nick").initials)
    }

    @Test
    fun `member role permissions follow the hierarchy`() {
        assertTrue(MemberRole.OWNER.canManageMembers)
        assertTrue(MemberRole.OWNER.canManageRoles)

        assertTrue(MemberRole.ADMIN.canManageMembers)
        assertFalse("Только владелец назначает администраторов", MemberRole.ADMIN.canManageRoles)

        assertFalse(MemberRole.MEMBER.canManageMembers)
        assertFalse(MemberRole.MEMBER.canManageConversation)
    }

    @Test
    fun `terminal task statuses are done and cancelled`() {
        assertTrue(TaskStatus.DONE.isTerminal)
        assertTrue(TaskStatus.CANCELLED.isTerminal)
        assertFalse(TaskStatus.TODO.isTerminal)
        assertFalse(TaskStatus.IN_PROGRESS.isTerminal)
        assertFalse(TaskStatus.REVIEW.isTerminal)
    }

    @Test
    fun `completion outweighs other statuses in conflict resolution`() {
        // При равных метках времени более «сильное» намерение должно победить.
        assertTrue(TaskStatus.DONE.conflictWeight > TaskStatus.TODO.conflictWeight)
        assertTrue(TaskStatus.DONE.conflictWeight > TaskStatus.IN_PROGRESS.conflictWeight)
        assertTrue(TaskStatus.DONE.conflictWeight > TaskStatus.REVIEW.conflictWeight)
        assertTrue(TaskStatus.CANCELLED.conflictWeight > TaskStatus.REVIEW.conflictWeight)
        assertTrue(
            "Завершение сильнее отмены: это осознанный выбор, зафиксированный в модели",
            TaskStatus.DONE.conflictWeight > TaskStatus.CANCELLED.conflictWeight,
        )
    }

    @Test
    fun `priority sort order puts urgent first`() {
        val sorted = TaskPriority.entries.sortedBy { it.sortOrder }
        assertEquals(
            listOf(TaskPriority.URGENT, TaskPriority.HIGH, TaskPriority.MEDIUM, TaskPriority.LOW),
            sorted,
        )
    }

    @Test
    fun `local only delivery statuses are identified correctly`() {
        assertTrue(MessageDeliveryStatus.PENDING.isLocalOnly)
        assertTrue(MessageDeliveryStatus.SENDING.isLocalOnly)
        assertTrue(MessageDeliveryStatus.FAILED.isLocalOnly)

        assertFalse(MessageDeliveryStatus.SENT.isLocalOnly)
        assertFalse(MessageDeliveryStatus.DELIVERED.isLocalOnly)
        assertFalse(MessageDeliveryStatus.READ.isLocalOnly)
    }

    @Test
    fun `task is overdue only when due date passed and work is not finished`() {
        val past = Instant.now().minusSeconds(3600)
        val future = Instant.now().plusSeconds(3600)

        assertTrue(task(dueAt = past, status = TaskStatus.TODO).isOverdue)
        assertFalse("Будущий срок не просрочен", task(dueAt = future, status = TaskStatus.TODO).isOverdue)
        assertFalse("Без срока просрочки быть не может", task(dueAt = null).isOverdue)
        assertFalse(
            "Выполненная задача не считается просроченной",
            task(dueAt = past, status = TaskStatus.DONE).isOverdue,
        )
        assertFalse(
            "Отменённая задача не считается просроченной",
            task(dueAt = past, status = TaskStatus.CANCELLED).isOverdue,
        )
    }

    @Test
    fun `subtask progress is a fraction and never divides by zero`() {
        assertEquals(0f, task(subtaskCount = 0).subtaskProgress, 0.0001f)
        assertEquals(0.5f, task(subtaskCount = 4, completed = 2).subtaskProgress, 0.0001f)
        assertEquals(1f, task(subtaskCount = 3, completed = 3).subtaskProgress, 0.0001f)
    }

    @Test
    fun `message states derive from timestamps`() {
        val base = message()
        assertFalse(base.isDeleted)
        assertFalse(base.isEdited)
        assertFalse("Без seq сообщение не подтверждено", base.isConfirmed)

        assertTrue(message(seq = 10).isConfirmed)
        assertTrue(message(editedAt = Instant.now()).isEdited)
        assertTrue(message(deletedAt = Instant.now()).isDeleted)
    }

    @Test
    fun `retry is offered only for failed messages`() {
        assertTrue(message(deliveryStatus = MessageDeliveryStatus.FAILED).canRetry)
        assertFalse(message(deliveryStatus = MessageDeliveryStatus.PENDING).canRetry)
        assertFalse(message(deliveryStatus = MessageDeliveryStatus.SENT).canRetry)
    }

    @Test
    fun `empty task filter is recognised`() {
        assertTrue(TaskFilter().isEmpty)
        assertFalse(TaskFilter(query = "релиз").isEmpty)
        assertFalse(TaskFilter(statuses = setOf(TaskStatus.TODO)).isEmpty)
        assertTrue(
            "Scope не делает фильтр непустым: это переключатель раздела, а не фильтр",
            TaskFilter(scope = TaskScope.TEAM).isEmpty,
        )
    }

    @Test
    fun `conversation mute state depends on expiry`() {
        val muted = conversation(mutedUntil = Instant.now().plusSeconds(3600))
        val expired = conversation(mutedUntil = Instant.now().minusSeconds(3600))

        assertTrue(muted.isMuted)
        assertFalse("Истёкшее отключение уведомлений больше не действует", expired.isMuted)
        assertFalse(conversation(mutedUntil = null).isMuted)
    }

    @Test
    fun `attachment type is derived from mime type`() {
        assertTrue(attachment("image/jpeg").isImage)
        assertTrue(attachment("video/mp4").isVideo)
        assertTrue(attachment("audio/ogg").isAudio)
        assertFalse(attachment("application/pdf").isImage)
    }

    @Test
    fun `attachment is uploading until progress reaches one`() {
        assertTrue(attachment(progress = 0.4f).isUploading)
        assertFalse(attachment(progress = 1f).isUploading)
        assertFalse("Без прогресса загрузка не идёт", attachment(progress = null).isUploading)
    }

    private fun task(
        dueAt: Instant? = null,
        status: TaskStatus = TaskStatus.TODO,
        subtaskCount: Int = 0,
        completed: Int = 0,
    ) = Task(
        id = TaskId("t"),
        title = "Задача",
        status = status,
        priority = TaskPriority.MEDIUM,
        createdBy = UserId("u"),
        dueAt = dueAt,
        subtaskCount = subtaskCount,
        completedSubtaskCount = completed,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun message(
        seq: Long? = null,
        deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.PENDING,
        editedAt: Instant? = null,
        deletedAt: Instant? = null,
    ) = Message(
        id = MessageId("m"),
        conversationId = ConversationId("c"),
        senderId = UserId("u"),
        clientMsgId = ClientOpId("op"),
        seq = seq,
        type = MessageType.TEXT,
        text = "текст",
        deliveryStatus = deliveryStatus,
        syncStatus = SyncStatus.PENDING,
        editedAt = editedAt,
        deletedAt = deletedAt,
        createdAt = Instant.EPOCH,
    )

    private fun conversation(mutedUntil: Instant?) = Conversation(
        id = ConversationId("c"),
        type = ConversationType.DIRECT,
        title = "Чат",
        mutedUntil = mutedUntil,
        updatedAt = Instant.EPOCH,
    )

    private fun attachment(mimeType: String = "image/png", progress: Float? = null) = Attachment(
        id = AttachmentId("a"),
        mimeType = mimeType,
        sizeBytes = 1024,
        fileName = "file",
        uploadProgress = progress,
    )
}
