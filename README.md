# Mango Messenger

Мессенджер для Android с интегрированным управлением задачами: задачу можно
создать прямо из сообщения, и она сохраняет связь с исходной перепиской.

> **Статус: Этапы 1–2 завершены.** Собирается Android-приложение с рабочим
> offline-ядром. Сервер, сеть и обмен сообщениями — следующие этапы.
> Честное разделение готового и запланированного — в
> [отчёте по этапам](docs/reports/stage-1-2-report.md).

---

## Что уже работает

- Многомодульный Gradle-проект: 9 модулей, convention-плагины, сборка
  debug и release APK.
- Offline-ядро: Room (14 таблиц), очередь исходящих операций с
  идемпотентностью, backoff с джиттером, восстановление после перезапуска.
- Создание задач и смена статусов **полностью работают без сети**.
- Design System: тема Material 3 со светлым и тёмным вариантами,
  Dynamic Color, 18 компонентов с поддержкой TalkBack.
- Шифрование токенов ключом Android Keystore (AES-256-GCM).
- Редактирование персональных данных перед записью в лог.
- **109 unit-тестов, 0 падений.**

## Чего ещё нет

Backend, сетевой слой, WebSocket, обмен сообщениями, авторизация, push,
медиа, групповые чаты, Kanban, календарь. Это Этапы 3–6 — см.
[план развития](docs/reports/stage-1-2-report.md#план-дальнейшего-развития).

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
| Docker + Compose | — | с Этапа 3 (backend) |

JDK отдельно устанавливать не обязательно: подойдёт JetBrains Runtime
из комплекта Android Studio — скрипт сборки находит его сам.

---

## Быстрый старт

```bash
git clone <repository-url> mango-messenger
cd mango-messenger

# Путь к Android SDK
cp android/local.properties.example android/local.properties
# отредактируйте sdk.dir под свою машину

# Сборка и все тесты
scripts/gradlew.sh build
```

Скрипт `scripts/gradlew.sh` сам подбирает JDK 17+ (сначала `JAVA_HOME`,
затем JBR из Android Studio, затем стандартные каталоги) и вызывает Gradle
wrapper. Путь к JDK намеренно не хранится в `gradle.properties`: он зависит
от машины, и в коммитируемом файле ломал бы сборку у всех остальных.

### Полезные команды

```bash
scripts/gradlew.sh build                    # сборка + все тесты + lint
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
│   │   ├── data/         репозитории, мапперы, очередь операций
│   │   └── testing/      правила и фейки для тестов
│   ├── build-logic/      convention-плагины Gradle
│   └── gradle/           version catalog
├── backend/              Ktor (Этап 3)
├── infrastructure/       Docker Compose (Этап 3)
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
| [Отчёт по Этапам 1–2](docs/reports/stage-1-2-report.md) | Что готово, что частично, что запланировано |

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
| Сеть (Этап 4) | Retrofit, OkHttp, WebSocket |
| Тесты | JUnit 4, MockK, Turbine, Robolectric |
| Сборка | Gradle 9.3.1, AGP 8.13.1, KSP |
| Backend (Этап 3) | Ktor, PostgreSQL, Redis, S3-совместимое хранилище |

Версии зафиксированы по доступному SDK и проверены сборкой —
см. [ADR-0002](docs/adr/0002-build-toolchain.md).

---

## Лицензия

Не определена. До выбора лицензии права на код принадлежат владельцу
репозитория.
