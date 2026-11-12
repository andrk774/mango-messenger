plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :sync — журнал событий и курсоры догонки.
//
// Это ИСТОЧНИК ИСТИНЫ для синхронизации: WebSocket лишь ускоряет доставку
// того, что уже записано сюда. Модуль не зависит ни от messaging, ни от
// tasks — наоборот, они обращаются к нему через интерфейс EventLog.
dependencies {
    api(projects.common)

    // Из :auth используется ровно одно — установление личности
    // вызывающего по проверенному токену. Это публичный контракт
    // модуля аутентификации, а не обращение к его таблицам.
    implementation(projects.auth)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
