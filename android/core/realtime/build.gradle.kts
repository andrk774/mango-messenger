plugins {
    id("mango.android.library")
    id("mango.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mango.core.realtime"
}

// :core:realtime — соединение WebSocket и его жизненный цикл.
//
// Доставка событий заканчивается вызовом :core:sync: сам модуль
// ничего не пишет в базу, поэтому логика применения событий
// существует в одном экземпляре и не расходится между каналами.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(projects.core.network)
    implementation(projects.core.sync)
    // Допуск к базе владельца сессии (AccountGuard).
    implementation(projects.core.data)
    implementation(projects.core.security)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.core.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
