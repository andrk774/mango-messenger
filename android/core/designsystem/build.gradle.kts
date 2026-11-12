plugins {
    id("mango.android.library")
    id("mango.android.compose")
}

android {
    namespace = "com.mango.core.designsystem"
}

dependencies {
    api(projects.core.model)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
