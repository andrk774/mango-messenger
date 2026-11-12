plugins {
    id("mango.jvm.library")
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
}
