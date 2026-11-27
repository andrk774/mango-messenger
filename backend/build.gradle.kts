plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // JVM 21: столько даёт JBR из Android Studio, и столько же будет
    // в контейнере (eclipse-temurin:21-jre). Версия одна в обоих местах,
    // чтобы «на машине собралось, в контейнере упало» было невозможно.
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(21)
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }

    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.fasterxml.jackson.core" && requested.name in setOf("jackson-core", "jackson-databind")) useVersion("2.22.3")
            if (requested.group == "com.google.guava" && requested.name == "guava") useVersion("33.7.2-jre")
        }
    }

    dependencies {
        add("testImplementation", rootProject.libs.junit.jupiter)
        add("testImplementation", rootProject.libs.kotlin.test)
        add("testImplementation", rootProject.libs.kotlinx.coroutines.test)
        add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        // Testcontainers поднимает настоящий PostgreSQL: на холодном старте
        // образ может тянуться из сети, поэтому запас по времени нужен.
        systemProperty("junit.jupiter.execution.timeout.default", "5m")
    }
}

/** Доступ к version catalog внутри блока subprojects. */
val org.gradle.api.Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = extensions.getByName("libs") as org.gradle.accessors.dm.LibrariesForLibs
