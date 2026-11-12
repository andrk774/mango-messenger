#!/usr/bin/env bash
# Управление Android SDK из командной строки.
#
# Зачем обёртка:
#   - ANDROID_HOME в системе не задан; путь берётся из android/local.properties;
#   - legacy-утилита avdmanager требует JDK 17+, а на PATH может быть старый JRE.
#
# Использование:
#   scripts/sdk.sh list                       установленные и доступные пакеты
#   scripts/sdk.sh list --all                 весь каталог репозитория
#   scripts/sdk.sh install "platforms;android-37"
#   scripts/sdk.sh avd list device            доступные профили устройств
#   scripts/sdk.sh avd create avd -n Pixel8 -k "system-images;android-36;google_apis;x86_64"
#   scripts/sdk.sh raw sdk list               произвольная команда android CLI
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=find-jdk.sh
source "$PROJECT_ROOT/scripts/find-jdk.sh"

# --- путь к SDK ---------------------------------------------------------

find_sdk() {
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
        echo "$ANDROID_HOME"
        return 0
    fi
    if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
        echo "$ANDROID_SDK_ROOT"
        return 0
    fi

    local props="$PROJECT_ROOT/android/local.properties"
    if [ -f "$props" ]; then
        # sdk.dir=C\:/Users/... — снимаем экранирование двоеточия
        local dir
        dir="$(grep -E '^sdk\.dir=' "$props" | head -1 | cut -d= -f2- | sed 's/\\:/:/g' | tr -d '\r')"
        if [ -n "$dir" ] && [ -d "$dir" ]; then
            echo "$dir"
            return 0
        fi
    fi

    local fallback="$LOCALAPPDATA/Android/Sdk"
    [ -d "$fallback" ] && { echo "$fallback"; return 0; }
    return 1
}

SDK="$(find_sdk || true)"
if [ -z "$SDK" ]; then
    echo "Не удалось определить путь к Android SDK." >&2
    echo "Задайте ANDROID_HOME или sdk.dir в android/local.properties." >&2
    exit 1
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

CLI_DIR="$SDK/cmdline-tools/latest/bin"
if [ ! -d "$CLI_DIR" ]; then
    echo "cmdline-tools не установлены в $SDK/cmdline-tools/latest" >&2
    echo "Установите их через Android Studio (SDK Manager → SDK Tools)" >&2
    echo "или скачайте с https://developer.android.com/studio#command-line-tools-only" >&2
    exit 1
fi

# avdmanager требует JDK 17+; новый android CLI обходится своим рантаймом.
mango_find_jdk || true

ANDROID_CLI="$CLI_DIR/android.exe"
[ -x "$ANDROID_CLI" ] || ANDROID_CLI="$CLI_DIR/android"

usage() {
    sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

command="${1:-}"
[ $# -gt 0 ] && shift || true

case "$command" in
    list|install|uninstall|update)
        exec "$ANDROID_CLI" sdk "$command" --sdk "$SDK" "$@"
        ;;
    avd)
        if [ -z "${JAVA_HOME:-}" ]; then
            echo "avdmanager требует JDK 17 или новее, подходящий JDK не найден." >&2
            exit 1
        fi
        exec "$CLI_DIR/avdmanager.bat" "$@"
        ;;
    raw)
        exec "$ANDROID_CLI" "$@"
        ;;
    ""|-h|--help|help)
        usage
        exit 0
        ;;
    *)
        echo "Неизвестная команда: $command" >&2
        echo >&2
        usage >&2
        exit 2
        ;;
esac
