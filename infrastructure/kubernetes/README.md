# Mango в Kubernetes

Добавлен отдельный локальный стенд Kustomize. Существующий Docker Compose и его данные не меняются. Это подготовка запуска в Kubernetes, а не публикация Mango в интернете.

Проверено 19 ноября 2026: обе конфигурации собираются через `kubectl kustomize`; kubeconform v0.8.0 в строгом режиме проверил 22 ресурса — все корректны, ошибок и пропусков нет. Синтаксис PowerShell-скрипта проверен без генерации секретов. Kubernetes-контекст на ноутбуке не настроен, поэтому server-side dry-run, запуск Pod, подключение томов и проверка приложения в кластере ещё не выполнены. Это не проверка готовности промышленного развёртывания.

## Состав

- Namespace `mango-dev`.
- Backend: одна реплика, `Recreate`, Service ClusterIP, startup/liveness/readiness probes, лимиты CPU/RAM. UID/GID `100:101` проверены в текущем образе backend; при смене базового образа проверить их повторно.
- PostgreSQL 17 и Redis 7.4: StatefulSet и собственные постоянные тома.
- Вложения: отдельный PVC 10 GiB. Хранилище пока файловое, поэтому backend нельзя просто масштабировать на несколько реплик.
- Mailpit: локальная проверка email; письма перехватываются и не доставляются внешним адресатам. Его данные временные, база Mango сохраняется отдельно.
- `local`: без FCM. `with-firebase`: тот же стенд с обязательным Secret `mango-firebase`.
- Пароли и JWT приходят из заранее созданного Secret `mango-runtime`; действующие секреты Compose не копируются.

Нужны настроенный кластер, `kubectl` и StorageClass по умолчанию. Контекст кластера проверяйте явно; приведённые команды используют `docker-desktop` как пример. Если контекст отсутствует, сначала создайте/включите локальный кластер. Включение Kubernetes в Docker Desktop здесь автоматически не выполняется.

## Подготовка и запуск из корня репозитория

```powershell
kubectl config get-contexts
kubectl --context docker-desktop get storageclass
docker build -t mango-backend:k8s-local backend
```

Образ должен быть доступен узлам кластера. В kind загрузите его через `kind load docker-image mango-backend:k8s-local --name <cluster>`. Для удалённого кластера используйте свой registry и замените image в Kustomization. Наличие образа в Docker хоста не гарантирует его наличие в containerd узла.

Создание отдельных локальных секретов (Windows PowerShell, значения не выводятся):

```powershell
powershell -NoProfile -File infrastructure/kubernetes/prepare-secrets.ps1
kubectl --context docker-desktop apply -f infrastructure/kubernetes/local/namespace.yaml
kubectl --context docker-desktop -n mango-dev create secret generic mango-runtime --from-env-file=secrets/kubernetes/runtime.env
```

Скрипт отказывается перезаписывать существующий файл. Не добавляйте `runtime.env` в Git. После инициализации PostgreSQL смена Secret сама по себе не меняет пароль внутри существующей БД: ротация требует согласованного обновления PostgreSQL и backend.

```powershell
kubectl kustomize infrastructure/kubernetes/local
kubectl --context docker-desktop apply --dry-run=server -k infrastructure/kubernetes/local
kubectl --context docker-desktop apply -k infrastructure/kubernetes/local
kubectl --context docker-desktop -n mango-dev rollout status statefulset/postgres --timeout=300s
kubectl --context docker-desktop -n mango-dev rollout status statefulset/redis --timeout=300s
kubectl --context docker-desktop -n mango-dev rollout status deployment/backend --timeout=300s
kubectl --context docker-desktop -n mango-dev get pods,pvc,services
```

Backend выполняет миграции при старте. Пока PostgreSQL/Redis запускаются, первые попытки backend могут перезапускаться. PVC должны перейти в `Bound`; ожидание PVC требует проверки StorageClass, а не удаления данных.

Для проверки на ноутбуке, в двух отдельных терминалах:

```powershell
kubectl --context docker-desktop -n mango-dev port-forward service/backend 18081:8080 --address 127.0.0.1
kubectl --context docker-desktop -n mango-dev port-forward service/mailpit 18026:8025 --address 127.0.0.1
```

API: `http://127.0.0.1:18081/v1/health/ready`; почта: `http://127.0.0.1:18026`. Эти адреса не доступны напрямую с Galaxy. Текущий APK продолжает обращаться к `https://mango.home.arpa/` и Compose. Переключение Android требует отдельного HTTPS-маршрута к кластеру и проверки WebSocket/вложений; оно не выполнено автоматически.

## Firebase по желанию

Используйте существующий файл, не выводя содержимое:

```powershell
kubectl --context docker-desktop -n mango-dev create secret generic mango-firebase --from-file=firebase-service-account.json=secrets/firebase-service-account.json
kubectl --context docker-desktop apply --dry-run=server -k infrastructure/kubernetes/with-firebase
kubectl --context docker-desktop apply -k infrastructure/kubernetes/with-firebase
```

Ключ монтируется только для чтения. Наличие Secret не доказывает доставку push: нужен вход Android в этот backend и регистрация FCM-токена. Kubernetes Secrets не следует считать автоматически зашифрованным хранилищем: для внешнего кластера нужны настроенные права и шифрование секретов в хранилище кластера.

## Данные и границы стенда

Это новая БД: пользователи и сообщения из Compose сами не переносятся. Миграция требует резервной копии PostgreSQL, согласованного переноса вложений и проверки восстановления. Не удаляйте namespace/PVC для перезапуска: политика StorageClass может удалить и данные тома. Обновление backend одной реплики через Recreate имеет перерыв в доступности.

Для внешнего запуска дополнительно нужны домен и TLS, выбранный Ingress/Gateway с поддержкой WebSocket и размера вложений, настоящий SMTP, сетевые ограничения, резервирование БД/файлов и проверка восстановления. `MANGO_ENV=LOCAL` нельзя оставлять на публичном сервере. Подписки, платёжные звёзды, веб-клиент и QR-вход не появляются от добавления Kubernetes.

Официальные основы: [постоянные тома](https://kubernetes.io/docs/concepts/storage/persistent-volumes/), [проверки готовности и живости](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/).
