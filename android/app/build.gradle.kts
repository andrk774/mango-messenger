plugins {
    id("mango.android.application")
    id("mango.android.compose")
    id("mango.android.hilt")
}

// Firebase подключается, только если владелец проекта положил
// google-services.json (см. README, «Push-уведомления»). Без файла
// плагин прервал бы сборку, а приложение и без push полностью рабочее:
// уведомления заменяет синхронизация при открытии.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// ─────────────────────── запуск в локальной сети ───────────────────────
//
// Адрес сервера: -Pmango.serverUrl=https://mango.home.arpa/ (или строкой
// в gradle.properties пользователя). По умолчанию — хост для эмулятора.
val serverUrl = (findProperty("mango.serverUrl") as String?)?.trimEnd('/')?.plus("/")
    ?: "http://10.0.2.2:8081/"

// Резервный IP сервера: используется, если DNS телефона не знает имени
// (VPN, «Частный DNS»). Пусто — резерва нет.
val serverFallbackIp = (findProperty("mango.serverIp") as String?)?.trim().orEmpty()

// Корневой сертификат локального ЦС (scripts/export-local-ca.sh). Если он
// есть, приложение доверяет ему — и только для домена локального сервера.
// Нет файла — сборка та же, что и без локальной сети.
val lanCaFile = rootProject.file("../secrets/mango-local-ca.crt")
val lanDomain = (findProperty("mango.lanDomain") as String?) ?: "mango.home.arpa"
val hasLanCa = lanCaFile.isFile
val lanResDir = layout.buildDirectory.dir("generated/lan-ca/res")

abstract class PrepareLanCaTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val prepareLanCa = tasks.register<PrepareLanCaTask>("prepareLanCa") {
    description = "Кладёт сертификат локального ЦС и настройку доверия к нему в ресурсы"
    inputs.file(lanCaFile).optional()
    inputs.property("domain", lanDomain)
    outputDirectory.set(lanResDir)
    onlyIf { hasLanCa }
    doLast {
        val res = lanResDir.get().asFile
        res.resolve("raw").mkdirs()
        lanCaFile.copyTo(res.resolve("raw/mango_local_ca.pem"), overwrite = true)
        res.resolve("xml").mkdirs()
        // Релиз: только HTTPS; локальный ЦС признаётся лишь для своего домена.
        res.resolve("xml/network_security_config_lan.xml").writeText(lanNetworkConfig(lanDomain, debug = false))
        // Отладка: то же плюс открытый HTTP к эмулятору, как раньше.
        res.resolve("xml/network_security_config_lan_debug.xml").writeText(lanNetworkConfig(lanDomain, debug = true))
    }
}

fun lanNetworkConfig(domain: String, debug: Boolean): String = buildString {
    appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
    appendLine("<!-- Создан сборкой (app/build.gradle.kts, prepareLanCa). Не править вручную. -->")
    appendLine("<network-security-config>")
    appendLine("""    <base-config cleartextTrafficPermitted="false" />""")
    appendLine("""    <domain-config cleartextTrafficPermitted="false">""")
    appendLine("""        <domain includeSubdomains="false">$domain</domain>""")
    appendLine("        <trust-anchors>")
    appendLine("""            <certificates src="@raw/mango_local_ca" />""")
    appendLine("        </trust-anchors>")
    appendLine("    </domain-config>")
    if (debug) {
        appendLine("""    <domain-config cleartextTrafficPermitted="true">""")
        listOf("10.0.2.2", "localhost", "127.0.0.1").forEach {
            appendLine("""        <domain includeSubdomains="false">$it</domain>""")
        }
        appendLine("    </domain-config>")
    }
    appendLine("</network-security-config>")
}

androidComponents {
    onVariants { variant ->
        if (hasLanCa) {
            variant.sources.res?.addGeneratedSourceDirectory(prepareLanCa) { it.outputDirectory }
        }
    }
}

android {
    namespace = "com.mango.messenger"

    defaultConfig {
        applicationId = "com.mango.messenger"
        versionCode = 1
        versionName = "0.1.0-alpha01"

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "SERVER_FALLBACK_IP", "\"$serverFallbackIp\"")
        manifestPlaceholders["networkSecurityConfig"] =
            if (hasLanCa) "@xml/network_security_config_lan" else "@xml/network_security_config"
    }

    val releaseStore = System.getenv("MANGO_KEYSTORE_PATH")
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs.create("mangoRelease") {
            storeFile = file(releaseStore)
            storePassword = System.getenv("MANGO_KEYSTORE_PASSWORD") ?: error("MANGO_KEYSTORE_PASSWORD is required")
            keyAlias = System.getenv("MANGO_KEY_ALIAS") ?: "mango-release"
            keyPassword = System.getenv("MANGO_KEY_PASSWORD") ?: storePassword
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["networkSecurityConfig"] =
                if (hasLanCa) "@xml/network_security_config_lan_debug" else "@xml/network_security_config"
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release signing is supplied by scripts/build-release.ps1 or the environment.
            signingConfig = signingConfigs.findByName("mangoRelease")
        }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(projects.core.sync)
    implementation(projects.core.realtime)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.security)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.compose.material.icons.extended)
    // Показ картинок-вложений из локальных файлов. Сетевой модуль Coil
    // не нужен: файлы скачивает слой данных по подписанной ссылке.
    implementation(libs.coil.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.lifecycle.process)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
}
