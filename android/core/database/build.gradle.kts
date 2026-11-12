plugins {
    id("mango.android.library")
    id("mango.android.hilt")
    id("mango.android.room")
}

android {
    namespace = "com.mango.core.database"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.room.paging)
    implementation(libs.paging.runtime)

    // DAO-тесты идут на JVM через Robolectric: эмулятор в окружении недоступен.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.paging.testing)
}
