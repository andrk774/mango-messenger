// Корневой build-файл. Плагины объявляются здесь без применения,
// чтобы версии были едиными для всех модулей.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.google.services) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            // Hilt's reader must understand the metadata produced by Kotlin 2.4.
            if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-metadata-jvm") {
                useVersion("2.4.20")
            }
        }
    }
}
