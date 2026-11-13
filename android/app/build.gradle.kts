plugins {
    id("mango.android.application")
    id("mango.android.compose")
    id("mango.android.hilt")
}

android {
    namespace = "com.mango.messenger"

    defaultConfig {
        applicationId = "com.mango.messenger"
        versionCode = 1
        versionName = "0.1.0-alpha01"
    }

    buildTypes {
        debug {
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
            // Релизная сборка пока подписывается debug-ключом, чтобы assembleRelease
            // был проверяем в CI. Реальный keystore подключается на Этапе 7.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.lifecycle.process)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
}
