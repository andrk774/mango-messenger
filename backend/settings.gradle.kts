@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "mango-backend"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Модули создаются вместе с содержимым, а не заранее пустыми:
// каталог из пустых классов создаёт ложное впечатление готовности.
// tasks, notifications, storage появятся на Этапах 5-6.
include(":common")
include(":auth")
include(":sync")
include(":messaging")
include(":realtime")
include(":app")
