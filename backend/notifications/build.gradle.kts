plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :notifications — push-уведомления и напоминания о задачах.
//
// Доставка скрыта за PushProvider (ADR-0010): FCM HTTP v1 либо запись
// в журнал, если ключ Firebase не задан. Клиент Google не подключается:
// протокол — два HTTP-запроса, их выполняет HttpClient из JDK.
dependencies {
    api(projects.common)
    api(projects.sync)
    implementation(projects.auth)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
