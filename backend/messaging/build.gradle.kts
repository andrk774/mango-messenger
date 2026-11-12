plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :messaging — чаты, участники, сообщения.
//
// Зависит от :sync, потому что каждое изменение обязано попасть в журнал
// событий — иначе клиент, переживший разрыв связи, его не увидит.
dependencies {
    api(projects.common)
    api(projects.sync)

    // Только для установления личности вызывающего — см. комментарий в :sync.
    implementation(projects.auth)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
