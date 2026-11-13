plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :tasks — задачи, исполнители, комментарии, история изменений.
//
// Зависит от :sync (каждое изменение — событие в журнале) и от :auth
// (установление личности). К таблицам чатов обращается только через
// проверку участия: задача чата видна его участникам.
dependencies {
    api(projects.common)
    api(projects.sync)
    implementation(projects.auth)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
