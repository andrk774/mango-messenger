#!/usr/bin/env bash
# Сквозная проверка поднятого стека: регистрация → письмо → подтверждение →
# вход → защищённый ресурс → обновление токена → выход.
#
# Проверяет не код, а РАБОТАЮЩУЮ систему: настоящий HTTP, настоящий
# PostgreSQL, настоящий SMTP. Интеграционные тесты поднимают приложение
# в том же процессе, поэтому не покрывают сборку образа, миграции при
# старте контейнера и доставку почты.
#
# Использование:  scripts/smoke-test.sh [базовый-URL] [URL-Mailpit]
set -euo pipefail

API="${1:-http://127.0.0.1:8081}"
MAILPIT="${2:-http://127.0.0.1:8026}"

EMAIL="smoke-$(date +%s)@example.com"
USERNAME="smoke$(date +%s | tail -c 7)"
# Данные запросов — только ASCII.
#
# Git Bash на Windows передаёт аргументы в кодировке консоли, и кириллица
# в теле curl-запроса доезжает до сервера испорченной. Это ограничение
# оболочки, а не приложения: обработка кириллицы проверяется
# интеграционными тестами на JVM, где кодировка задана явно.
PASSWORD="SmokeTestPassword-2026"

# Адрес клиента для этого прогона.
#
# Регистрация ограничена тремя попытками в час на адрес, поэтому
# повторные прогоны с одного адреса упирались бы в собственную защиту.
# Проверяется работа регистрации, а не ограничителя: у него свои тесты.
RUN_IP="198.51.100.$(( (RANDOM % 250) + 1 ))"

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; exit 1; }

json_field() { # $1 — json, $2 — имя поля
    printf '%s' "$1" | sed -nE "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"([^\"]*)\".*/\1/p"
}

http_code() { # последний аргумент — файл тела
    curl -s -o "$2" -w '%{http_code}' -m 15 "$@" 2>/dev/null || true
}

echo "Проверка стека: $API"
echo

# ─────────────────────────── 1. состояние ───────────────────────────
code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$API/v1/health")
[ "$code" = "200" ] || fail "health вернул $code"
pass "сервис отвечает"

ready=$(curl -s -m 10 "$API/v1/health/ready")
printf '%s' "$ready" | grep -q '"database":"ok"' || fail "база недоступна: $ready"
pass "база данных доступна"

# ────────────────────────── 2. регистрация ──────────────────────────
body=$(curl -s -m 15 -X POST "$API/v1/auth/register" \
    -H 'Content-Type: application/json' -H "X-Forwarded-For: $RUN_IP" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke Test\",\"username\":\"$USERNAME\"}" \
    -w '\n%{http_code}')
code=$(printf '%s' "$body" | tail -1)
[ "$code" = "202" ] || fail "регистрация вернула $code: $body"
pass "регистрация принята ($EMAIL)"

# Повторная регистрация на тот же адрес обязана выглядеть так же.
code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' -X POST "$API/v1/auth/register" \
    -H 'Content-Type: application/json' -H "X-Forwarded-For: $RUN_IP" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke Other\",\"username\":\"${USERNAME}x\"}")
[ "$code" = "202" ] || fail "повторная регистрация вернула $code — занятость адреса раскрыта"
pass "занятость адреса не раскрывается"

# ──────────────────── 3. письмо и подтверждение ────────────────────
letters=$(curl -s -m 15 "$MAILPIT/api/v1/search?query=to%3A$EMAIL" || true)
message_id=$(printf '%s' "$letters" | sed -nE 's/.*"ID":"([^"]+)".*/\1/p' | head -1)
[ -n "$message_id" ] || fail "письмо не доставлено в Mailpit (проверьте $MAILPIT)"
pass "письмо доставлено"

letter=$(curl -s -m 15 "$MAILPIT/api/v1/message/$message_id")
token=$(printf '%s' "$letter" | sed -nE 's/.*Код подтверждения: ([A-Za-z0-9_-]+).*/\1/p' | head -1)
[ -n "$token" ] || fail "в письме нет кода подтверждения"

code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' -X POST "$API/v1/auth/verify-email" \
    -H 'Content-Type: application/json' -d "{\"token\":\"$token\"}")
[ "$code" = "200" ] || fail "подтверждение вернуло $code"
pass "адрес подтверждён"

code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' -X POST "$API/v1/auth/verify-email" \
    -H 'Content-Type: application/json' -d "{\"token\":\"$token\"}")
[ "$code" = "400" ] || fail "повторное подтверждение вернуло $code вместо 400"
pass "ссылка подтверждения одноразовая"

# ──────────────────────────── 4. вход ────────────────────────────
tokens=$(curl -s -m 20 -X POST "$API/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"device\":{\"platform\":\"ANDROID\",\"model\":\"smoke\",\"appVersion\":\"0.1\"}}")
access=$(json_field "$tokens" accessToken)
refresh=$(json_field "$tokens" refreshToken)
[ -n "$access" ] && [ -n "$refresh" ] || fail "вход не выдал токены: $tokens"
pass "вход выполнен, пара токенов получена"

code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' -X POST "$API/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"НеверныйПароль-2026\"}")
[ "$code" = "401" ] || fail "вход с неверным паролем вернул $code"
pass "неверный пароль отклонён"

# ───────────────────── 5. защищённый ресурс ─────────────────────
code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' "$API/v1/users/me")
[ "$code" = "401" ] || fail "профиль без токена вернул $code"
pass "без токена доступа нет"

me=$(curl -s -m 15 "$API/v1/users/me" -H "Authorization: Bearer $access")
printf '%s' "$me" | grep -q "\"email\":\"$EMAIL\"" || fail "профиль вернул неожиданное: $me"
printf '%s' "$me" | grep -qi 'argon2\|passwordHash' && fail "в профиле обнаружен хеш пароля"
pass "профиль доступен и не содержит хеша пароля"

# ──────────────────── 6. обновление и ротация ────────────────────
rotated=$(curl -s -m 15 -X POST "$API/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$refresh\"}")
new_refresh=$(json_field "$rotated" refreshToken)
new_access=$(json_field "$rotated" accessToken)
[ -n "$new_refresh" ] || fail "обновление не выдало токены: $rotated"
[ "$new_refresh" != "$refresh" ] || fail "refresh не ротировался"
pass "refresh ротирован"

code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' -X POST "$API/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$refresh\"}")
[ "$code" = "401" ] || fail "старый refresh всё ещё работает (код $code)"
pass "старый refresh недействителен"

# Повторное использование уже обменянного токена обязано погасить все сессии.
code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' -X POST "$API/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$new_refresh\"}")
[ "$code" = "401" ] || fail "после кражи токена сессии не отозваны (код $code)"
pass "повторное использование завершило все сессии"

# После отзыва всех сессий нужен свежий вход для проверок переписки.
fresh=$(curl -s -m 20 -X POST "$API/v1/auth/login"     -H 'Content-Type: application/json'     -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
new_access=$(json_field "$fresh" accessToken)
[ -n "$new_access" ] || fail "повторный вход не удался" 


# ───────────────────── 7. переписка (Этап 4) ─────────────────────

EMAIL2="smoke2-$(date +%s)@example.com"
USERNAME2="smoketwo$(date +%s | tail -c 6)"

curl -s -o /dev/null -m 15 -X POST "$API/v1/auth/register" \
    -H 'Content-Type: application/json' -H "X-Forwarded-For: $RUN_IP" \
    -d "{\"email\":\"$EMAIL2\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke Second\",\"username\":\"$USERNAME2\"}"

letters2=$(curl -s -m 15 "$MAILPIT/api/v1/search?query=to%3A$EMAIL2" || true)
mid2=$(printf '%s' "$letters2" | sed -nE 's/.*"ID":"([^"]+)".*/\1/p' | head -1)
letter2=$(curl -s -m 15 "$MAILPIT/api/v1/message/$mid2")
token2=$(printf '%s' "$letter2" | sed -nE 's/.*Код подтверждения: ([A-Za-z0-9_-]+).*/\1/p' | head -1)
curl -s -o /dev/null -m 15 -X POST "$API/v1/auth/verify-email" \
    -H 'Content-Type: application/json' -d "{\"token\":\"$token2\"}"

tokens2=$(curl -s -m 20 -X POST "$API/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL2\",\"password\":\"$PASSWORD\"}")
access2=$(json_field "$tokens2" accessToken)
[ -n "$access2" ] || fail "второй пользователь не смог войти"
pass "второй пользователь создан"

me2=$(curl -s -m 15 "$API/v1/users/me" -H "Authorization: Bearer $access2")
user2_id=$(json_field "$me2" id)
[ -n "$user2_id" ] || fail "не получен идентификатор второго пользователя"

conv=$(curl -s -m 15 -X POST "$API/v1/conversations" \
    -H "Authorization: Bearer $new_access" -H 'Content-Type: application/json' \
    -d "{\"type\":\"DIRECT\",\"memberIds\":[\"$user2_id\"]}")
conv_id=$(json_field "$conv" id)
[ -n "$conv_id" ] || fail "чат не создан: $conv"
pass "личный чат создан"

conv_again=$(curl -s -m 15 -X POST "$API/v1/conversations" \
    -H "Authorization: Bearer $new_access" -H 'Content-Type: application/json' \
    -d "{\"type\":\"DIRECT\",\"memberIds\":[\"$user2_id\"]}")
[ "$(json_field "$conv_again" id)" = "$conv_id" ] || fail "создан второй личный чат"
pass "повторное открытие возвращает тот же чат"

msg=$(curl -s -m 15 -X POST "$API/v1/conversations/$conv_id/messages" \
    -H "Authorization: Bearer $new_access" -H 'Content-Type: application/json' \
    -d '{"clientMsgId":"smoke-op-1","type":"TEXT","body":"Hello from smoke test"}')
msg_seq=$(printf '%s' "$msg" | sed -nE 's/.*"seq":([0-9]+).*/\1/p' | head -1)
[ "$msg_seq" = "1" ] || fail "первое сообщение должно получить номер 1: $msg"
pass "сообщение отправлено и получило номер 1"

code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' -X POST "$API/v1/conversations/$conv_id/messages" \
    -H "Authorization: Bearer $new_access" -H 'Content-Type: application/json' \
    -d '{"clientMsgId":"smoke-op-1","type":"TEXT","body":"Hello from smoke test"}')
[ "$code" = "200" ] || fail "повтор операции вернул $code вместо 200"
pass "повтор операции не создал дубликат"

history=$(curl -s -m 15 "$API/v1/conversations/$conv_id/messages?limit=50" \
    -H "Authorization: Bearer $access2")
printf '%s' "$history" | grep -q "Hello from smoke test" || fail "собеседник не видит сообщение"
count=$(printf '%s' "$history" | grep -o '"seq":' | wc -l)
[ "$count" -eq 1 ] || fail "в истории $count сообщений вместо одного"
pass "собеседник видит ровно одно сообщение"

events=$(curl -s -m 15 "$API/v1/sync?since=0" -H "Authorization: Bearer $access2")
printf '%s' "$events" | grep -q "MESSAGE_CREATED" || fail "события нет в журнале"
printf '%s' "$events" | grep -q "CONVERSATION_CREATED" || fail "нет события создания чата"
pass "изменения попали в журнал событий"

list=$(curl -s -m 15 "$API/v1/conversations" -H "Authorization: Bearer $access2")
printf '%s' "$list" | grep -q '"unreadCount":1' || fail "непрочитанные посчитаны неверно"
pass "непрочитанные посчитаны"

code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' -X PUT "$API/v1/conversations/$conv_id/read" \
    -H "Authorization: Bearer $access2" -H 'Content-Type: application/json' -d '{"seq":1}')
[ "$code" = "204" ] || fail "отметка о прочтении вернула $code"

list_after=$(curl -s -m 15 "$API/v1/conversations" -H "Authorization: Bearer $access2")
printf '%s' "$list_after" | grep -q '"unreadCount":0' || fail "непрочитанные не обнулились"
pass "отметка о прочтении обнулила счётчик"

code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' \
    "$API/v1/conversations/$conv_id/messages" -H "Authorization: Bearer $access")
[ "$code" = "401" ] || [ "$code" = "403" ] || fail "отозванная сессия дала доступ (код $code)"
pass "отозванная сессия не даёт доступа к переписке"

echo
printf '\033[32mВсе проверки пройдены.\033[0m Письма: %s\n' "$MAILPIT"
