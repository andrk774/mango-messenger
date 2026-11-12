#!/usr/bin/env bash
# Обёртка над Gradle wrapper для Android-части Mango Messenger.
#
# Зачем: путь к JDK зависит от машины, а записывать его в коммитируемый
# gradle.properties нельзя — он ломал бы сборку у всех остальных.
# Скрипт подбирает JDK 17+ сам (см. scripts/find-jdk.sh).
#
# Использование:  scripts/gradlew.sh <задачи...>     например:  scripts/gradlew.sh build
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"

# shellcheck source=find-jdk.sh
source "$PROJECT_ROOT/scripts/find-jdk.sh"

if ! mango_find_jdk; then
    echo "Не найден JDK 17 или новее." >&2
    echo "Установите JDK 17+ и задайте JAVA_HOME, либо установите Android Studio." >&2
    exit 1
fi

echo "Используется JDK: $JAVA_HOME"
exec "$ANDROID_DIR/gradlew" -p "$ANDROID_DIR" "$@"
