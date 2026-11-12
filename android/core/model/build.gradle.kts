plugins {
    id("mango.jvm.library")
}

// :core:model — чистый Kotlin. Никаких Android-зависимостей: domain-слой
// должен компилироваться и тестироваться на JVM без Robolectric.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
