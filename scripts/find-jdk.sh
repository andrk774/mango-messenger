#!/usr/bin/env bash
# Поиск JDK 17 или новее. Предназначен для подключения через `source`.
#
# Экспортирует JAVA_HOME, если подходящий JDK найден.
# Возвращает ненулевой код, если не найден.
#
# Порядок поиска:
#   1. JAVA_HOME, если там JDK нужной версии;
#   2. JetBrains Runtime из комплекта Android Studio;
#   3. стандартные каталоги установки JDK.

_mango_java_major() {
    local java_bin="$1/bin/java"
    [ -x "$java_bin" ] || java_bin="$1/bin/java.exe"
    [ -x "$java_bin" ] || return 1
    "$java_bin" -version 2>&1 | head -1 |
        sed -E 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/' | head -1
}

_mango_has_javac() {
    [ -x "$1/bin/javac" ] || [ -x "$1/bin/javac.exe" ]
}

mango_find_jdk() {
    local candidates=()
    [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME")
    candidates+=(
        "/c/Program Files/Android/Android Studio/jbr"
        "/c/Program Files/Android/Android Studio/jre"
        "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        "/usr/lib/jvm/java-21-openjdk-amd64"
        "/usr/lib/jvm/java-17-openjdk-amd64"
    )

    local candidate major
    for candidate in "${candidates[@]}"; do
        [ -d "$candidate" ] || continue
        _mango_has_javac "$candidate" || continue
        major="$(_mango_java_major "$candidate" 2>/dev/null || true)"
        [ -n "$major" ] || continue
        if [ "$major" -ge 17 ] 2>/dev/null; then
            export JAVA_HOME="$candidate"
            return 0
        fi
    done
    return 1
}
