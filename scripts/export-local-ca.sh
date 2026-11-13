#!/usr/bin/env bash
# Выгружает корневой сертификат локального ЦС Caddy в secrets/mango-local-ca.crt.
#
# Сборка Android встраивает его в приложение (только для домена
# MANGO_DOMAIN), если файл есть. Запускать после первого старта Caddy
# и после потери тома caddydata — тогда ЦС создаётся заново и
# приложение нужно пересобрать.
#
# Это открытый сертификат, не секрет. В secrets/ он лежит потому, что
# принадлежит конкретной машине и в репозиторий попадать не должен.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p secrets
docker cp mango-caddy-1:/data/caddy/pki/authorities/local/root.crt secrets/mango-local-ca.crt
echo "Сохранено: secrets/mango-local-ca.crt"
openssl x509 -in secrets/mango-local-ca.crt -noout -subject -enddate 2>/dev/null || true
