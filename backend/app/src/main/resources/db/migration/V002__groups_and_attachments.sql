-- Этап 6a: управление группами и вложения.
--
-- Таблицы уже заложены в V001; здесь только то, что выяснилось
-- при реализации.

-- Незагруженные и непривязанные вложения: по этому индексу считается
-- лимит висящих разрешений на загрузку и будет работать очистка.
CREATE INDEX attachments_owner_pending_idx
    ON attachments (owner_id, created_at)
    WHERE message_id IS NULL AND deleted_at IS NULL;

-- Вложение сообщения может быть привязано только к одному сообщению,
-- и только к сообщению того же чата. Второе проверяется в коде
-- (условие UPDATE), первое гарантируется тем, что привязка выполняется
-- одним UPDATE ... WHERE message_id IS NULL.

-- MIME-тип уходит клиенту в заголовок Content-Type при скачивании.
-- Ограничение формата исключает подстановку заголовков и параметров
-- (например, `text/html; charset=...` или перевод строки).
ALTER TABLE attachments
    ADD CONSTRAINT attachments_mime_type_format
        CHECK (mime_type ~ '^[a-z]+/[a-z0-9.+-]+$');

-- Системные сообщения (изменения состава группы) не считаются
-- непрочитанными; индекс для подсчёта непрочитанных учитывает тип.
CREATE INDEX messages_unread_idx
    ON messages (conversation_id, seq)
    WHERE deleted_at IS NULL AND type <> 'SYSTEM';
