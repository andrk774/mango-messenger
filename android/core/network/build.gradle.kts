plugins {
    id("mango.android.library")
    id("mango.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mango.core.network"
}

// :core:network — единственный модуль, знающий про HTTP.
// Наружу он отдаёт domain-модели и AppError; ни Retrofit, ни DTO
// за его границы не выходят.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    // api: тип AuthTokens входит в публичную поверхность SessionManager,
    // поэтому модулям-потребителям он нужен на классовом пути.
    api(projects.core.security)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.core.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
