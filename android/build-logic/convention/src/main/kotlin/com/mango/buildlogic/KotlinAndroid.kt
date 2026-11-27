package com.mango.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Единая Kotlin/Android-конфигурация для всех Android-модулей проекта.
 *
 * Все значения берутся из version catalog, чтобы не было рассинхрона между модулями.
 */
internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension,
) {
    commonExtension.apply {
        compileSdk = libs.intVersion("compileSdk")
        compileSdkMinor = libs.intVersion("compileSdkMinor")
        buildToolsVersion = libs.version("buildTools")

        defaultConfig.apply {
            minSdk = libs.intVersion("minSdk")
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            isCoreLibraryDesugaringEnabled = false
        }

        packaging.apply {
            resources {
                excludes += setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/LICENSE*",
                    "/META-INF/NOTICE*",
                    "META-INF/*.kotlin_module",
                )
            }
        }

        testOptions.apply {
            unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = true
            }
        }
    }

    extensions.configure<KotlinAndroidExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            configureKotlinCompilerArgs(freeCompilerArgs)
        }
    }
}

/** Конфигурация чистого JVM/Kotlin-модуля (например, :core:model). */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            configureKotlinCompilerArgs(freeCompilerArgs)
        }
    }
}

private fun configureKotlinCompilerArgs(
    freeCompilerArgs: org.gradle.api.provider.ListProperty<String>,
) {
    freeCompilerArgs.addAll(
        // Явная корутинная модель: предупреждаем о подозрительных конструкциях.
        "-Xconsistent-data-class-copy-visibility",
        "-opt-in=kotlin.RequiresOptIn",
    )
}

/** Общие тестовые зависимости, нужные каждому модулю. */
internal fun Project.addCommonUnitTestDependencies() {
    dependencies {
        add("testImplementation", libs.findLibrary("junit4").get())
        add("testImplementation", libs.findLibrary("mockk").get())
        add("testImplementation", libs.findLibrary("turbine").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    }
}
