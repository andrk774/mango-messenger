# Сборка образа backend.
#
# Многоэтапная и герметичная: исходники собираются ВНУТРИ образа, поэтому
# результат не зависит от того, что установлено на машине сборщика.
# Это требование к воспроизводимости — на чистой машине и в CI получается
# тот же образ, что и локально.

# ──────────────────────────── этап сборки ────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /src

# Сначала копируются только файлы сборки. Слой с загруженными
# зависимостями переиспользуется, пока не изменился их список, —
# правка исходников не вызывает повторную загрузку.
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
# ВНИМАНИЕ: при добавлении модуля его файл сборки нужно добавить сюда,
# иначе перестанет работать кеширование слоя с зависимостями.
COPY common/build.gradle.kts common/
COPY auth/build.gradle.kts auth/
COPY sync/build.gradle.kts sync/
COPY messaging/build.gradle.kts messaging/
COPY realtime/build.gradle.kts realtime/
COPY tasks/build.gradle.kts tasks/
COPY storage/build.gradle.kts storage/
COPY notifications/build.gradle.kts notifications/
COPY app/build.gradle.kts app/

RUN chmod +x gradlew && \
    ./gradlew --no-daemon --quiet :app:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

# Исходники копируются целиком. Перечисление модулей здесь приводило
# к тому, что новый модуль молча не попадал в образ и собирался
# устаревший код — сборка при этом завершалась без явной ошибки.
COPY . .

# Тесты здесь не запускаются намеренно: интеграционные поднимают
# контейнер PostgreSQL, а Docker-in-Docker во время сборки образа
# недоступен. Тесты — отдельный шаг конвейера до сборки образа.
RUN ./gradlew --no-daemon :app:installDist -x test

# ─────────────────────────── этап выполнения ───────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN apk add --no-cache postgresql17-client

# Процесс не от root: компрометация приложения не должна давать
# прав на запись в системные каталоги контейнера.
RUN addgroup -S mango && adduser -S mango -G mango

# Каталог вложений создаётся заранее с нужным владельцем: новый том
# Docker наследует владельца каталога из образа. Без этого том
# принадлежал бы root, и загрузка файлов падала бы с отказом в доступе.
RUN mkdir -p /data/attachments && chown mango:mango /data/attachments
RUN mkdir -p /data/backups && chown mango:mango /data/backups && chmod 700 /data/backups

WORKDIR /opt/mango
COPY --from=build --chown=mango:mango /src/app/build/install/app ./

USER mango

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Dfile.encoding=UTF-8"

# MaxRAMPercentage вместо -Xmx: JVM учитывает лимит памяти контейнера,
# а не память хоста. Без этого в контейнере с лимитом 512 МБ JVM
# считала бы доступной всю память машины и была бы убита OOM-killer-ом.

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1:8080/v1/health || exit 1

ENTRYPOINT ["./bin/app"]
