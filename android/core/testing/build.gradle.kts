plugins {
    id("mango.android.library")
}

android {
    namespace = "com.mango.core.testing"
}

// Это модуль тестовых утилит: он содержит правила и фейки для ДРУГИХ модулей
// и по своей природе не имеет собственных тестов. Gradle 9 считает пустую
// тестовую задачу ошибкой конфигурации, поэтому проверка отключается здесь —
// и только здесь.
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}

// Тестовые утилиты отдаются как api, чтобы модули-потребители
// получали их транзитивно одним объявлением.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(libs.junit4)
    api(libs.mockk)
    api(libs.turbine)
    api(libs.kotlinx.coroutines.test)
    api(libs.androidx.test.core)
}
