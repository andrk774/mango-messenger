#!/usr/bin/env bash
# Сборка и запуск backend.
#
# Использование:
#   scripts/backend.sh build        сборка и все тесты (нужен запущенный Docker)
#   scripts/backend.sh :auth:test   только модульные тесты, без Docker
#   scripts/backend.sh :app:run     локальный запуск (нужен поднятый стек)
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=find-jdk.sh
source "$PROJECT_ROOT/scripts/find-jdk.sh"

if ! mango_find_jdk; then
    echo "Не найден JDK 17 или новее." >&2
    echo "Установите JDK 17+ и задайте JAVA_HOME, либо установите Android Studio." >&2
    exit 1
fi

echo "Используется JDK: $JAVA_HOME"
exec "$PROJECT_ROOT/backend/gradlew" -p "$PROJECT_ROOT/backend" "$@"
