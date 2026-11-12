plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :realtime — WebSocket-хаб и рассылка событий между экземплярами.
//
// Зависит от :sync (события и курсоры) и от :auth (проверка токена
// в первом кадре соединения).
dependencies {
    api(projects.common)
    api(projects.sync)
    implementation(projects.auth)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)

    // Redis pub/sub: рассылка события между несколькими экземплярами.
    implementation(libs.lettuce)
}
