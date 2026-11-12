plugins {
    alias(libs.plugins.kotlin.serialization)
    application
}

// :app — композиционный корень: конфигурация, миграции, DI «руками»,
// маршрутизация, запуск сервера.
dependencies {
    implementation(projects.common)
    implementation(projects.auth)
    implementation(projects.sync)
    implementation(projects.messaging)
    implementation(projects.realtime)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.snakeyaml)
}

application {
    mainClass.set("com.mango.backend.app.MainKt")
}

// installDist вместо fat jar: даёт каталог bin/ + lib/, который напрямую
// копируется в образ. Это избавляет от плагина shadow и от слияния
// META-INF/services, на котором ломаются JDBC-драйверы.
tasks.named<JavaExec>("run") {
    // Локальный запуск читает .env-подобные переменные из окружения оболочки.
    systemProperty("io.ktor.development", "true")
}
