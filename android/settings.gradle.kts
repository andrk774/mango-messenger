@file:Suppress("UnstableApiUsage")

pluginManagement {
    // build-logic — included build с convention-плагинами.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Модули не объявляют свои repositories — только корень.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mango-messenger"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

// --- core ---
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:datastore")
include(":core:security")
include(":core:data")
include(":core:testing")
