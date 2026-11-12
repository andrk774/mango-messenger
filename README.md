# Mango Messenger

Мессенджер для Android с интегрированным управлением задачами: задачу можно
создать прямо из сообщения, и она сохраняет связь с исходной перепиской.

> **Статус: Этапы 1–4 завершены из семи.** Работает обмен сообщениями:
> сервер, синхронизация по журналу событий, WebSocket, очередь исходящих
> и экраны переписки. Задачи на сервере — следующий этап.
>
> **Приложение ни разу не запускалось на устройстве** — в окружении нет
> эмулятора. Проверены сборка, тесты и серверная часть на живом стеке.
>
> Честное разделение готового и запланированного — в отчётах:
> [Этапы 1–2](docs/reports/stage-1-2-report.md),
> [Этап 3](docs/reports/stage-3-report.md),
> [Этап 4](docs/reports/stage-4-report.md).

---

## Что уже работает

- Многомодульный Gradle-проект: 12 модулей Android и 6 модулей backend,
  convention-плагины, сборка debug и release APK.
- Offline-ядро: Room (14 таблиц), очередь исходящих операций с
  идемпотентностью, backoff с джиттером, восстановление после перезапуска.
- Создание задач и смена статусов **полностью работают без сети**.
- Design System: тема Material 3 со светлым и тёмным вариантами,
  Dynamic Color, 18 компонентов с поддержкой TalkBack.
- Шифрование токенов ключом Android Keystore (AES-256-GCM).
- Редактирование персональных данных перед записью в лог.

**Обмен сообщениями (Этап 4):**

- Личные чаты, отправка и получение сообщений, история с пагинацией.
- Порядок сообщений гарантирован блокировкой чата; повтор отправки
  не создаёт дубликат.
- Журнал событий с курсором: потеря связи не теряет сообщения.
- WebSocket с переподключением и сверкой по курсору, рассылка между
  экземплярами через Redis.
- Очередь исходящих отправляет операции сама, в том числе после
  перезапуска приложения.
- Экраны входа, регистрации, списка чатов и переписки.

**Backend (Этап 3):**

- Ktor + PostgreSQL 17, схема из 16 таблиц через миграции Flyway.
- Регистрация, подтверждение адреса, вход, выход, восстановление пароля.
- Argon2id, JWT с коротким сроком, ротация refresh и обнаружение
  повторного использования украденного токена.
- Эндпоинты не раскрывают, зарегистрирован ли адрес.
- Docker Compose: PostgreSQL, Redis, Mailpit, backend — одной командой.
- Проверки живости и готовности, корректная остановка.

**Тесты: 147 на Android + 80 на backend, 0 падений.**
Интеграционные идут на настоящем PostgreSQL в контейнере.

## Чего ещё нет

Задачи на сервере и разрешение конфликтов, групповые чаты и роли,
медиа и объектное хранилище, push, поиск, реакции, Kanban, календарь.
Это Этапы 5–7 — см. [план развития](docs/reports/stage-4-report.md#что-дальше).

**Приложение не использует сквозное шифрование** и не заявляет его —
см. [ADR-0009](docs/adr/0009-no-e2ee-claims.md).

---

## Требования

| Инструмент | Версия | Обязателен |
|---|---|---|
| JDK | 17 или новее | да |
| Android SDK | платформа **android-36**, build-tools **36.0.0** | да |
| Android SDK cmdline-tools | 23.0 (установлены) | для управления SDK из CLI |
| Android Studio | любая актуальная | рекомендуется |
| Docker + Compose | — | для backend |

JDK отдельно устанавливать не обязательно: подойдёт JetBrains Runtime
из комплекта Android Studio — скрипт сборки находит его сам.

---

## Быстрый старт

### Backend

```bash
git clone <repository-url> mango-messenger
cd mango-messenger

cp .env.example .env
# задайте MANGO_JWT_SECRET:  openssl rand -base64 48

docker compose --env-file .env -f infrastructure/docker/docker-compose.yml up -d --build
```

Поднимается PostgreSQL, Redis, Mailpit и backend. Миграции применяются
автоматически при старте. Первая сборка образа занимает 10–15 минут:
Gradle внутри контейнера загружает зависимости.

Проверка работоспособности — сквозным сценарием через настоящий HTTP и SMTP:

```bash
scripts/smoke-test.sh
```

Скрипт проходит путь регистрация → письмо → подтверждение → вход →
защищённый ресурс → ротация refresh → обнаружение повторного использования.

| Что | Где |
|---|---|
| API | http://127.0.0.1:8081 |
| Письма (Mailpit) | http://127.0.0.1:8026 |
| PostgreSQL | 127.0.0.1:5433 |
| Redis | 127.0.0.1:6380 |

Остановка: `docker compose -f infrastructure/docker/docker-compose.yml down`
(добавьте `-v`, чтобы удалить и данные).

### Android

```bash
cp android/local.properties.example android/local.properties
# отредактируйте sdk.dir под свою машину

scripts/gradlew.sh build
```

Скрипт `scripts/gradlew.sh` сам подбирает JDK 17+ (сначала `JAVA_HOME`,
затем JBR из Android Studio, затем стандартные каталоги) и вызывает Gradle
wrapper. Путь к JDK намеренно не хранится в `gradle.properties`: он зависит
от машины, и в коммитируемом файле ломал бы сборку у всех остальных.

### Полезные команды

```bash
scripts/backend.sh build                    # backend: сборка + все тесты
scripts/backend.sh :auth:test               # backend: только модульные тесты, без Docker
scripts/smoke-test.sh                       # проверка поднятого стека

scripts/gradlew.sh build                    # Android: сборка + все тесты + lint
scripts/gradlew.sh test                     # только unit-тесты
scripts/gradlew.sh :app:assembleDebug       # debug APK
scripts/gradlew.sh :app:assembleRelease     # release APK (R8)
scripts/gradlew.sh :core:data:testDebugUnitTest   # тесты одного модуля
scripts/gradlew.sh --stop                   # остановить демоны Gradle
```

APK появляются в `android/app/build/outputs/apk/`.

Из Android Studio: откройте каталог **`android`** (не корень репозитория) —
Studio подставит свой JDK автоматически.

### Управление Android SDK

`cmdline-tools` 23.0 установлены, поэтому SDK управляется из командной
строки. Обёртка `scripts/sdk.sh` сама находит путь к SDK (из
`android/local.properties`) и подходящий JDK:

```bash
scripts/sdk.sh list                     # установленные и доступные пакеты
scripts/sdk.sh list --all               # весь каталог репозитория
scripts/sdk.sh install "platforms;android-37"
scripts/sdk.sh avd list device          # профили устройств
scripts/sdk.sh avd list avd             # созданные эмуляторы
```

Создание эмулятора (нужен системный образ, ~1,5 ГБ):

```bash
scripts/sdk.sh install "system-images;android-36;google_apis;x86_64"
scripts/sdk.sh avd create avd -n Pixel8_API36     -k "system-images;android-36;google_apis;x86_64" -d pixel_8
```

> В cmdline-tools 23.0 `sdkmanager` объявлен устаревшим и заменён новым
> `android` CLI; `sdkmanager --licenses` больше не требуется.

### Если сборка падает на `clean`

На Windows демоны Gradle держат файлы собранных jar. Остановите их и
повторите:

```bash
scripts/gradlew.sh --stop
scripts/gradlew.sh build
```

---

## Структура репозитория

```
mango-messenger/
├── android/              Android-приложение
│   ├── app/              Application, навигация, экраны
│   ├── core/
│   │   ├── model/        domain-модели (чистый Kotlin)
│   │   ├── common/       ошибки, UUIDv7, backoff, редактирование ПД
│   │   ├── designsystem/ тема Material 3 и компоненты
│   │   ├── database/     Room: 14 таблиц, 6 DAO
│   │   ├── datastore/    настройки пользователя
│   │   ├── security/     Keystore, хранилище токенов
│   │   ├── network/      Retrofit, DTO, обновление токена
│   │   ├── sync/         очередь исходящих, применение событий
│   │   ├── realtime/     WebSocket, переподключение, сверка
│   │   ├── data/         репозитории, мапперы
│   │   └── testing/      правила и фейки для тестов
│   ├── build-logic/      convention-плагины Gradle
│   └── gradle/           version catalog
├── backend/              Ktor
│   ├── common/           ошибки, UUIDv7, доступ к БД, конфигурация
│   ├── auth/             Argon2id, JWT, сессии, письма
│   ├── sync/             журнал событий и курсоры догонки
│   ├── messaging/        чаты, участники, сообщения
│   ├── realtime/         WebSocket-хаб, рассылка через Redis
│   └── app/              композиционный корень, миграции, маршруты
├── infrastructure/
│   └── docker/           docker-compose.yml
├── docs/
│   ├── adr/              архитектурные решения (11 документов)
│   ├── api/openapi.yaml  контракт API
│   ├── architecture/     обзор, модель данных, синхронизация, безопасность
│   └── reports/          отчёты по этапам
└── scripts/
    ├── gradlew.sh        сборка с автоопределением JDK
    ├── sdk.sh            управление Android SDK и эмуляторами
    └── find-jdk.sh       общий поиск JDK 17+
```

---

## Документация

| Документ | О чём |
|---|---|
| [Архитектура](docs/architecture/overview.md) | Слои, модули, принципы, ограничения окружения |
| [Модель данных](docs/architecture/er-diagram.md) | ER-диаграмма, ограничения целостности, отличия Room от PostgreSQL |
| [Синхронизация](docs/architecture/sync-model.md) | Очередь, идемпотентность, догонка событий, разрешение конфликтов |
| [Безопасность](docs/architecture/security-plan.md) | Модель угроз, реализованное и запланированное |
| [API](docs/api/openapi.yaml) | Контракт REST и WebSocket |
| [ADR](docs/adr/) | Принятые решения с обоснованием и альтернативами |
| [Отчёт по Этапам 1–2](docs/reports/stage-1-2-report.md) | Android: что готово, что частично, что запланировано |
| [Отчёт по Этапу 3](docs/reports/stage-3-report.md) | Backend: то же, плюс найденные дефекты и расхождения с планом |
| [Отчёт по Этапу 4](docs/reports/stage-4-report.md) | Обмен сообщениями: что работает, что не проверено |

Начинать чтение стоит с [архитектурного обзора](docs/architecture/overview.md),
затем [модель синхронизации](docs/architecture/sync-model.md) — в ней
сосредоточена основная сложность продукта.

---

## Стек

| Слой | Технологии |
|---|---|
| Язык | Kotlin 2.2.21 |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Архитектура | Clean Architecture, MVVM + MVI |
| DI | Hilt 2.58 |
| Хранение | Room 2.8.5, DataStore |
| Асинхронность | Coroutines, StateFlow |
| Сеть Android (Этап 4) | Retrofit, OkHttp, WebSocket |
| Backend | Ktor 3.6.1, Kotlin 2.3.21, HikariCP, Flyway |
| Хранение (сервер) | PostgreSQL 17, Redis 7.4 |
| Безопасность | Argon2id (Bouncy Castle), JWT (java-jwt) |
| Тесты | JUnit 4, MockK, Turbine, Robolectric |
| Сборка | Gradle 9.3.1, AGP 8.13.1, KSP |


Версии зафиксированы по доступному SDK и проверены сборкой —
см. [ADR-0002](docs/adr/0002-build-toolchain.md).

---

## Лицензия

Не определена. До выбора лицензии права на код принадлежат владельцу
репозитория.
