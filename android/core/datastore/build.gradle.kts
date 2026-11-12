plugins {
    id("mango.android.library")
    id("mango.android.hilt")
}

android {
    namespace = "com.mango.core.datastore"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.datastore.preferences)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
