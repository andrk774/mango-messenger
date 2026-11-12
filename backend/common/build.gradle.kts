plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :common — фундамент монолита: ошибки, идентификаторы, время, конфигурация,
// доступ к БД. Модуль НЕ знает ни про Ktor, ни про предметную область,
// поэтому его может использовать любой другой модуль без обратных связей.
dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.hikari)
    api(libs.postgresql)
    api(libs.logback)

    // Общие счётчики ограничения частоты и рассылка событий.
    api(libs.lettuce)
}
