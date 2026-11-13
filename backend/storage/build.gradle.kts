plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :storage — вложения: разрешения на загрузку, приём файла, проверка
// содержимого, временные ссылки на скачивание.
//
// Хранилище скрыто за интерфейсом BlobStorage (ADR-0019): сейчас это
// каталог на диске, в продакшне — S3-совместимое хранилище. К таблицам
// чатов модуль обращается только для проверки участия.
dependencies {
    api(projects.common)
    implementation(projects.auth)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
