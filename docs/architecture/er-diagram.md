# Модель данных Mango Messenger

Серверная схема (PostgreSQL) и её клиентское зеркало (Room). Расхождения
между ними перечислены в разделе 4 — они намеренные.

---

## 1. ER-диаграмма

```mermaid
erDiagram
    users ||--o{ devices : "владеет"
    users ||--o{ sessions : "имеет"
    users ||--o{ conversation_members : "участвует"
    users ||--o{ messages : "отправляет"
    users ||--o{ message_reactions : "реагирует"
    users ||--o{ tasks : "создаёт"
    users ||--o{ task_assignees : "назначен"
    users ||--o{ task_comments : "комментирует"
    users ||--o{ notifications : "получает"
    users ||--o{ sync_events : "читает журнал"
    users ||--o{ outbox_operations : "выполняет"

    devices ||--o{ sessions : "привязана"

    conversations ||--o{ conversation_members : "включает"
    conversations ||--o{ messages : "содержит"
    conversations ||--o{ tasks : "порождает"

    messages ||--o{ message_reactions : "получает"
    messages ||--o{ attachments : "несёт"
    messages ||--o| messages : "ответ на"
    messages ||--o{ tasks : "источник задачи"

    tasks ||--o{ task_assignees : "назначена"
    tasks ||--o{ task_comments : "обсуждается"
    tasks ||--o{ task_events : "история"
    tasks ||--o{ attachments : "несёт"
    tasks ||--o| tasks : "подзадача"

    users {
        uuid id PK
        citext email UK
        bool email_verified
        text password_hash
        citext username UK
        text display_name
        text avatar_url
        text bio
        text presence_status
        timestamptz last_seen_at
        jsonb privacy
        timestamptz created_at
        timestamptz deleted_at
    }

    devices {
        uuid id PK
        uuid user_id FK
        text platform
        text model
        text push_token
        text push_provider
        text app_version
        timestamptz last_active_at
        timestamptz revoked_at
    }

    sessions {
        uuid id PK
        uuid user_id FK
        uuid device_id FK
        text refresh_token_hash UK
        timestamptz expires_at
        timestamptz revoked_at
        inet ip
        text user_agent
    }

    conversations {
        uuid id PK
        text type
        text title
        text avatar_url
        uuid created_by FK
        bigint last_event_seq
        jsonb settings
        timestamptz updated_at
        timestamptz deleted_at
    }

    conversation_members {
        uuid conversation_id PK_FK
        uuid user_id PK_FK
        text role
        timestamptz joined_at
        timestamptz left_at
        timestamptz muted_until
        bigint last_read_seq
        text draft
    }

    messages {
        uuid id PK
        uuid conversation_id FK
        uuid sender_id FK
        text client_msg_id
        bigint seq
        text type
        text body
        uuid reply_to_id FK
        uuid forwarded_from_id FK
        timestamptz edited_at
        timestamptz deleted_at
        timestamptz pinned_at
        timestamptz created_at
    }

    message_reactions {
        uuid message_id PK_FK
        uuid user_id PK_FK
        text emoji PK
        timestamptz created_at
    }

    attachments {
        uuid id PK
        uuid owner_id FK
        uuid message_id FK
        uuid task_id FK
        text storage_key UK
        text mime_type
        bigint size_bytes
        int width
        int height
        int duration_ms
        text checksum_sha256
        text scan_status
        timestamptz deleted_at
    }

    tasks {
        uuid id PK
        uuid conversation_id FK
        uuid created_by FK
        uuid source_message_id FK
        jsonb source_message_snapshot
        text title
        text description
        text status
        text priority
        timestamptz due_at
        timestamptz remind_at
        uuid parent_task_id FK
        bigint version
        jsonb field_versions
        timestamptz completed_at
        timestamptz deleted_at
    }

    task_assignees {
        uuid task_id PK_FK
        uuid user_id PK_FK
        uuid assigned_by FK
        timestamptz assigned_at
    }

    task_comments {
        uuid id PK
        uuid task_id FK
        uuid author_id FK
        text client_op_id
        text body
        timestamptz edited_at
        timestamptz deleted_at
    }

    task_events {
        bigserial id PK
        uuid task_id FK
        uuid actor_id FK
        text event_type
        text field
        jsonb old_value
        jsonb new_value
        timestamptz created_at
    }

    notifications {
        uuid id PK
        uuid user_id FK
        text type
        uuid conversation_id FK
        uuid message_id FK
        uuid task_id FK
        jsonb payload
        timestamptz read_at
    }

    sync_events {
        bigserial id PK
        uuid user_id FK
        text scope
        uuid scope_id
        text event_type
        jsonb payload
        timestamptz created_at
    }

    outbox_operations {
        uuid id PK
        uuid user_id FK
        text client_op_id
        text op_type
        jsonb payload
        text status
        jsonb result
        timestamptz created_at
    }
```

---

## 2. Ключевые ограничения целостности

Эти индексы — не оптимизация, а механизм корректности. Они обеспечивают
инварианты, которые нельзя доверить только коду.

| Ограничение | Зачем |
|---|---|
| `UNIQUE (conversation_id, sender_id, client_msg_id)` | Защита от дубликатов при повторной отправке. Последний рубеж, если идемпотентность на уровне HTTP дала сбой |
| `UNIQUE (conversation_id, seq)` | Порядок внутри чата однозначен |
| `UNIQUE (user_id, client_op_id)` в `outbox_operations` | Серверная идемпотентность: повтор возвращает сохранённый результат |
| `UNIQUE (task_id, author_id, client_op_id)` | Комментарий не дублируется при повторе |
| `UNIQUE (refresh_token_hash)` | Ротация refresh token, обнаружение повторного использования |
| `UNIQUE (push_provider, push_token)` где токен не NULL | Один токен не привязан к двум устройствам |
| `UNIQUE` по нормализованной паре участников для `DIRECT` | Повторное открытие переписки не создаёт второй личный чат |
| `CHECK` на `type`, `status`, `priority`, `role` | Невалидное значение не попадёт в БД даже при ошибке в коде |

---

## 3. Индексы для запросов

| Таблица | Индекс | Запрос |
|---|---|---|
| `messages` | `(conversation_id, seq DESC)` | пагинация истории |
| `messages` | `GIN(to_tsvector(body))` | полнотекстовый поиск |
| `conversation_members` | `(user_id) WHERE left_at IS NULL` | список чатов пользователя |
| `tasks` | `(conversation_id, status)` | Kanban по чату |
| `tasks` | `(due_at) WHERE deleted_at IS NULL` | календарь и напоминания |
| `tasks` | `(source_message_id)` | пометка источника при удалении сообщения |
| `task_events` | `(task_id, id)` | история изменений |
| `sync_events` | `(user_id, id)` | догонка по курсору |
| `notifications` | `(user_id, created_at DESC) WHERE read_at IS NULL` | счётчик непрочитанных |

---

## 4. Клиентская схема: намеренные отличия

Room-схема (версия 1, 14 таблиц) зеркалит серверную, но отличается в
нескольких местах — это осознанные решения.

| Отличие | Причина |
|---|---|
| Нет таблиц `sessions`, `devices` | Клиент хранит только свою пару токенов, зашифрованную в Keystore |
| Добавлена `outbox_operations` с полями `attempt_count`, `next_attempt_at`, `depends_on_op_id`, `status` | Это локальная очередь с расписанием повторов, а не серверный журнал идемпотентности |
| Добавлена `sync_state` | Курсоры догонки; лежат в БД, чтобы продвигаться в одной транзакции с событиями |
| Добавлена `message_gaps` | Явный учёт разрывов истории для точечной догрузки |
| `messages.seq` допускает `NULL` | Сообщение, ещё не подтверждённое сервером |
| Добавлено `sync_status` в `messages`, `tasks`, `attachments`, `task_comments` | Состояние доставки видно пользователю |
| Снапшот источника задачи разложен по колонкам, а не в JSON | Поля читаются при отрисовке каждой карточки; разбор JSON на каждый элемент списка не нужен |
| Время — `INTEGER` (epoch millis), не ISO-строка | Сравнение и сортировка идут по индексу |
| Enum хранятся строками, не `ordinal` | Добавление значения в середину перечисления не портит существующие записи |

### Экспорт схемы

`exportSchema = true`, схема версии 1 лежит в
`android/core/database/schemas/com.mango.core.database.MangoDatabase/1.json`.
Без экспортированной схемы невозможен тест миграции, а значит невозможно
безопасно менять схему в уже установленном приложении.

Деструктивные миграции **не включены**: локальная база может содержать
неотправленные сообщения и задачи, и их потеря при обновлении недопустима.

---

## 5. Состояние

| Часть | Статус |
|---|---|
| Клиентская схема Room (14 таблиц, 6 DAO) | ✅ реализована, покрыта 23 тестами |
| Экспорт схемы версии 1 | ✅ |
| Серверная схема PostgreSQL | ⏳ Этап 3 (миграции Flyway) |
| Тест миграции Room | ⏳ появится вместе со второй версией схемы |
