package com.mango.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * Доступ к version catalog изнутри convention-плагина.
 * В precompiled-плагинах `libs` недоступен как accessor, поэтому читаем каталог явно.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow {
        IllegalStateException("Version '$alias' не найдена в gradle/libs.versions.toml")
    }.requiredVersion

internal fun VersionCatalog.intVersion(alias: String): Int =
    version(alias).toInt()
