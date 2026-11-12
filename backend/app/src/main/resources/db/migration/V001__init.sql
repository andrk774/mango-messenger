-- Mango Messenger — начальная схема.
--
-- Принципы:
--   * идентификаторы — UUID v7, генерируются ПРИЛОЖЕНИЕМ до запроса.
--     Это тот же идентификатор, который клиент использует как ключ
--     идемпотентности, поэтому генерировать его в БД нельзя;
--   * время — всегда TIMESTAMPTZ, никогда timestamp без зоны;
--   * удаление — мягкое (deleted_at) там, где на строку могут ссылаться
--     ответы, задачи и вложения;
--   * ограничения целостности — в схеме, а не только в коде: индекс
--     отработает даже при ошибке в приложении.

CREATE EXTENSION IF NOT EXISTS citext;

-- ──────────────────────────────── users ────────────────────────────────

CREATE TABLE users (
    id              uuid        PRIMARY KEY,
    email           citext      NOT NULL,
    email_verified  boolean     NOT NULL DEFAULT false,
    password_hash   text        NOT NULL,
    username        citext      NOT NULL,
    display_name    text        NOT NULL,
    avatar_url      text,
    bio             text,
    presence_status text        NOT NULL DEFAULT 'OFFLINE'
                                CHECK (presence_status IN ('ONLINE','OFFLINE','AWAY','DO_NOT_DISTURB')),
    last_seen_at    timestamptz,
    privacy         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,

    CONSTRAINT users_display_name_not_blank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT users_username_format CHECK (username ~ '^[A-Za-z0-9_]{3,32}$')
);

-- Уникальность только среди живых учётных записей: удалённый пользователь
-- не должен вечно держать занятым адрес.
CREATE UNIQUE INDEX users_email_key    ON users (email)    WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX users_username_key ON users (username) WHERE deleted_at IS NULL;

-- ─────────────────────────────── devices ───────────────────────────────

CREATE TABLE devices (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    platform       text        NOT NULL CHECK (platform IN ('ANDROID','IOS','WEB')),
    model          text        NOT NULL DEFAULT '',
    app_version    text        NOT NULL DEFAULT '',
    push_provider  text        CHECK (push_provider IN ('FCM','HMS','DEV')),
    push_token     text,
    last_active_at timestamptz NOT NULL DEFAULT now(),
    created_at     timestamptz NOT NULL DEFAULT now(),
    revoked_at     timestamptz
);

CREATE INDEX devices_user_idx ON devices (user_id) WHERE revoked_at IS NULL;

-- Один push-токен не может принадлежать двум устройствам: иначе уведомление
-- уедет не тому человеку после переустановки приложения.
CREATE UNIQUE INDEX devices_push_token_key
    ON devices (push_provider, push_token)
    WHERE push_token IS NOT NULL AND revoked_at IS NULL;

-- ─────────────────────────────── sessions ──────────────────────────────

CREATE TABLE sessions (
    id                 uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id          uuid        REFERENCES devices (id) ON DELETE SET NULL,
    -- Хранится ТОЛЬКО хеш: утечка дампа БД не должна давать доступ к аккаунтам.
    refresh_token_hash text        NOT NULL,
    -- Цепочка ротации: позволяет обнаружить повторное использование
    -- уже обменянного refresh token и трактовать это как компрометацию.
    previous_session_id uuid       REFERENCES sessions (id) ON DELETE SET NULL,
    expires_at         timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    last_used_at       timestamptz,
    revoked_at         timestamptz,
    revoked_reason     text,
    ip                 inet,
    user_agent         text
);

CREATE UNIQUE INDEX sessions_refresh_hash_key ON sessions (refresh_token_hash);
CREATE INDEX sessions_user_active_idx ON sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX sessions_expiry_idx ON sessions (expires_at) WHERE revoked_at IS NULL;

-- ───────────────────────── email / password tokens ─────────────────────

CREATE TABLE auth_tokens (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    text        NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION','PASSWORD_RESET')),
    -- Как и refresh: в базе лежит хеш, а не сам токен из письма.
    token_hash text        NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX auth_tokens_hash_key ON auth_tokens (token_hash);
CREATE INDEX auth_tokens_user_idx ON auth_tokens (user_id, purpose) WHERE used_at IS NULL;

-- ───────────────────────────── conversations ───────────────────────────

CREATE TABLE conversations (
    id              uuid        PRIMARY KEY,
    type            text        NOT NULL CHECK (type IN ('DIRECT','GROUP')),
    title           text,
    avatar_url      text,
    created_by      uuid        REFERENCES users (id) ON DELETE SET NULL,
    last_message_id uuid,
    -- Счётчик порядкового номера сообщений внутри чата.
    -- Назначение seq идёт через UPDATE ... RETURNING по этой строке:
    -- блокировка строки сериализует конкурентные отправки в один чат.
    last_event_seq  bigint      NOT NULL DEFAULT 0,
    -- Нормализованная пара участников для DIRECT: 'minUuid:maxUuid'.
    -- Делает невозможным второй личный чат между теми же людьми.
    direct_key      text,
    settings        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,

    CONSTRAINT conversations_direct_key_required
        CHECK ((type = 'DIRECT' AND direct_key IS NOT NULL)
            OR (type = 'GROUP'  AND direct_key IS NULL)),
    CONSTRAINT conversations_group_title_required
        CHECK (type <> 'GROUP' OR length(btrim(coalesce(title, ''))) > 0)
);

CREATE UNIQUE INDEX conversations_direct_key_uq ON conversations (direct_key)
    WHERE type = 'DIRECT' AND deleted_at IS NULL;
CREATE INDEX conversations_updated_idx ON conversations (updated_at DESC);

CREATE TABLE conversation_members (
    conversation_id uuid        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role            text        NOT NULL DEFAULT 'MEMBER'
                                CHECK (role IN ('OWNER','ADMIN','MEMBER')),
    joined_at       timestamptz NOT NULL DEFAULT now(),
    left_at         timestamptz,
    muted_until     timestamptz,
    last_read_seq   bigint      NOT NULL DEFAULT 0,
    draft           text,

    PRIMARY KEY (conversation_id, user_id)
);

-- Главный индекс защиты от IDOR: проверка «состоит ли пользователь в чате»
-- выполняется в КАЖДОМ запросе к сообщениям, задачам и вложениям.
CREATE INDEX conversation_members_user_idx
    ON conversation_members (user_id) WHERE left_at IS NULL;

-- ─────────────────────────────── messages ──────────────────────────────

CREATE TABLE messages (
    id                 uuid        PRIMARY KEY,
    conversation_id    uuid        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sender_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Идентификатор, сгенерированный клиентом ДО отправки.
    client_msg_id      text        NOT NULL,
    seq                bigint      NOT NULL,
    type               text        NOT NULL
                                   CHECK (type IN ('TEXT','IMAGE','VIDEO','VOICE','FILE','SYSTEM')),
    body               text,
    reply_to_id        uuid        REFERENCES messages (id) ON DELETE SET NULL,
    forwarded_from_id  uuid        REFERENCES messages (id) ON DELETE SET NULL,
    edited_at          timestamptz,
    deleted_at         timestamptz,
    pinned_at          timestamptz,
    pinned_by          uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT messages_body_length CHECK (body IS NULL OR length(body) <= 8192)
);

-- Порядок внутри чата однозначен.
CREATE UNIQUE INDEX messages_conversation_seq_uq ON messages (conversation_id, seq);

-- Последний рубеж защиты от дубликатов: даже если идемпотентность
-- на уровне HTTP даст сбой, второе сообщение не появится.
CREATE UNIQUE INDEX messages_dedup_uq
    ON messages (conversation_id, sender_id, client_msg_id);

CREATE INDEX messages_history_idx ON messages (conversation_id, seq DESC);
CREATE INDEX messages_pinned_idx  ON messages (conversation_id, pinned_at DESC) WHERE pinned_at IS NOT NULL;
CREATE INDEX messages_search_idx  ON messages USING GIN (to_tsvector('simple', coalesce(body, '')));

CREATE TABLE message_reactions (
    message_id uuid        NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      text        NOT NULL CHECK (length(emoji) BETWEEN 1 AND 16),
    created_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (message_id, user_id, emoji)
);

-- ────────────────────────────── attachments ────────────────────────────

CREATE TABLE attachments (
    id              uuid        PRIMARY KEY,
    owner_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id uuid        REFERENCES conversations (id) ON DELETE CASCADE,
    message_id      uuid        REFERENCES messages (id) ON DELETE CASCADE,
    task_id         uuid,
    task_comment_id uuid,
    storage_key     text        NOT NULL,
    mime_type       text        NOT NULL,
    size_bytes      bigint      NOT NULL CHECK (size_bytes > 0),
    file_name       text        NOT NULL DEFAULT '',
    width           int,
    height          int,
    duration_ms     int,
    checksum_sha256 text,
    -- Файл не отдаётся клиенту, пока не получил статус CLEAN.
    scan_status     text        NOT NULL DEFAULT 'PENDING'
                                CHECK (scan_status IN ('PENDING','CLEAN','REJECTED')),
    uploaded_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz
);

CREATE UNIQUE INDEX attachments_storage_key_uq ON attachments (storage_key);
CREATE INDEX attachments_message_idx ON attachments (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX attachments_task_idx    ON attachments (task_id)    WHERE task_id IS NOT NULL;

-- ──────────────────────────────── tasks ────────────────────────────────

CREATE TABLE tasks (
    id                      uuid        PRIMARY KEY,
    conversation_id         uuid        REFERENCES conversations (id) ON DELETE SET NULL,
    created_by              uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Ссылка на сообщение-источник и его снапшот. Снапшот ПЕРЕЖИВАЕТ
    -- удаление сообщения: иначе задача теряет смысл.
    source_message_id       uuid        REFERENCES messages (id) ON DELETE SET NULL,
    source_message_snapshot jsonb,
    title                   text        NOT NULL,
    description             text,
    status                  text        NOT NULL DEFAULT 'TODO'
                                        CHECK (status IN ('TODO','IN_PROGRESS','REVIEW','DONE','CANCELLED')),
    priority                text        NOT NULL DEFAULT 'MEDIUM'
                                        CHECK (priority IN ('LOW','MEDIUM','HIGH','URGENT')),
    due_at                  timestamptz,
    remind_at               timestamptz,
    parent_task_id          uuid        REFERENCES tasks (id) ON DELETE CASCADE,
    -- Оптимистичная блокировка: клиент присылает baseVersion.
    version                 bigint      NOT NULL DEFAULT 1,
    -- Метки времени последнего изменения ПО КАЖДОМУ полю.
    -- Основа разрешения конфликтов per-field LWW (см. ADR-0005).
    field_versions          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    completed_at            timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    deleted_at              timestamptz,

    CONSTRAINT tasks_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT tasks_title_length CHECK (length(title) <= 256),
    CONSTRAINT tasks_not_own_parent CHECK (parent_task_id IS NULL OR parent_task_id <> id)
);

CREATE INDEX tasks_conversation_idx ON tasks (conversation_id, status) WHERE deleted_at IS NULL;
CREATE INDEX tasks_due_idx          ON tasks (due_at)                  WHERE deleted_at IS NULL AND due_at IS NOT NULL;
CREATE INDEX tasks_parent_idx       ON tasks (parent_task_id)          WHERE parent_task_id IS NOT NULL;
CREATE INDEX tasks_source_idx       ON tasks (source_message_id)       WHERE source_message_id IS NOT NULL;
CREATE INDEX tasks_creator_idx      ON tasks (created_by)              WHERE deleted_at IS NULL;
CREATE INDEX tasks_search_idx       ON tasks
    USING GIN (to_tsvector('simple', title || ' ' || coalesce(description, '')));

CREATE TABLE task_assignees (
    task_id     uuid        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    assigned_by uuid        REFERENCES users (id) ON DELETE SET NULL,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    -- OR-Set: удаление помечается, а не стирается, иначе параллельное
    -- добавление с другого устройства «воскресило» бы исполнителя.
    removed_at  timestamptz,

    PRIMARY KEY (task_id, user_id)
);

CREATE INDEX task_assignees_user_idx ON task_assignees (user_id) WHERE removed_at IS NULL;

CREATE TABLE task_comments (
    task_id      uuid        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    id           uuid        PRIMARY KEY,
    author_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    client_op_id text        NOT NULL,
    body         text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    edited_at    timestamptz,
    deleted_at   timestamptz,

    CONSTRAINT task_comments_body_not_blank CHECK (length(btrim(body)) > 0)
);

CREATE UNIQUE INDEX task_comments_dedup_uq ON task_comments (task_id, author_id, client_op_id);
CREATE INDEX task_comments_task_idx ON task_comments (task_id, created_at) WHERE deleted_at IS NULL;

-- История изменений. Содержит в том числе правки, проигравшие при
-- разрешении конфликта, — это делает любой конфликт разбираемым постфактум.
CREATE TABLE task_events (
    id         bigserial   PRIMARY KEY,
    task_id    uuid        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    actor_id   uuid        REFERENCES users (id) ON DELETE SET NULL,
    event_type text        NOT NULL,
    field      text,
    old_value  jsonb,
    new_value  jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX task_events_task_idx ON task_events (task_id, id DESC);

-- Отложенные внешние ключи вложений на задачи: таблица tasks создаётся позже.
ALTER TABLE attachments
    ADD CONSTRAINT attachments_task_fk
        FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
    ADD CONSTRAINT attachments_task_comment_fk
        FOREIGN KEY (task_comment_id) REFERENCES task_comments (id) ON DELETE CASCADE;

-- Ссылка на последнее сообщение добавляется после создания messages.
ALTER TABLE conversations
    ADD CONSTRAINT conversations_last_message_fk
        FOREIGN KEY (last_message_id) REFERENCES messages (id) ON DELETE SET NULL;

-- ───────────────────────────── notifications ───────────────────────────

CREATE TABLE notifications (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type            text        NOT NULL
                                CHECK (type IN ('NEW_MESSAGE','MENTION','TASK_ASSIGNED','TASK_UPDATED',
                                                'TASK_COMMENT','TASK_REMINDER','CONVERSATION_EVENT')),
    conversation_id uuid        REFERENCES conversations (id) ON DELETE CASCADE,
    message_id      uuid        REFERENCES messages (id) ON DELETE CASCADE,
    task_id         uuid        REFERENCES tasks (id) ON DELETE CASCADE,
    payload         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    read_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX notifications_unread_idx
    ON notifications (user_id, created_at DESC) WHERE read_at IS NULL;

-- ────────────────────────────── sync_events ────────────────────────────

-- Персональный журнал событий — ИСТОЧНИК ИСТИНЫ для синхронизации.
-- WebSocket лишь ускоряет доставку; после каждого реконнекта клиент
-- сверяется с этим журналом по курсору (см. ADR-0006).
CREATE TABLE sync_events (
    id         bigserial   PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    scope      text        NOT NULL CHECK (scope IN ('CONVERSATION','TASK','PROFILE')),
    scope_id   uuid,
    event_type text        NOT NULL,
    payload    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX sync_events_cursor_idx ON sync_events (user_id, id);

-- ─────────────────────────── outbox_operations ─────────────────────────

-- Серверная идемпотентность. Повторный запрос с тем же client_op_id
-- возвращает СОХРАНЁННЫЙ результат, а не применяет операцию заново.
CREATE TABLE outbox_operations (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    client_op_id text        NOT NULL,
    op_type      text        NOT NULL,
    request_hash text,
    status       text        NOT NULL CHECK (status IN ('APPLIED','REJECTED')),
    status_code  int         NOT NULL,
    result       jsonb,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX outbox_operations_idempotency_uq
    ON outbox_operations (user_id, client_op_id);

-- Очистка по TTL (7 дней) выполняется фоновой задачей: журнал
-- идемпотентности не должен расти бесконечно.
CREATE INDEX outbox_operations_created_idx ON outbox_operations (created_at);
