plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :auth — регистрация, вход, JWT, ротация refresh, сессии и устройства.
// Зависит только от :common. Другие модули обращаются сюда через
// публичные интерфейсы сервисов, а не через таблицы auth напрямую.
dependencies {
    api(projects.common)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Argon2id: чистая Java, без JNI — работает в slim-контейнере.
    implementation(libs.bouncycastle)

    // SMTP для писем подтверждения и восстановления.
    implementation(libs.angus.mail)
}
