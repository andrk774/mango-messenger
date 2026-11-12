plugins {
    id("mango.android.library")
    id("mango.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mango.core.data"
}

// :core:data — реализации репозиториев. Единственный модуль, который видит
// одновременно и Room, и (в дальнейшем) сеть. Feature-модули знают только
// интерфейсы репозиториев, объявленные здесь.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.database)
    api(projects.core.network)
    implementation(libs.room.ktx)
    implementation(projects.core.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.paging.runtime)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
}
