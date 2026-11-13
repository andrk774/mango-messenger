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
// notifications появится на Этапе 6b.
include(":common")
include(":auth")
include(":sync")
include(":messaging")
include(":realtime")
include(":tasks")
include(":storage")
include(":app")
