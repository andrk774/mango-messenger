plugins {
    id("mango.android.library")
    id("mango.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mango.core.sync"
}

// :core:sync — доставка изменений в обе стороны:
//   * очередь исходящих операций → сервер;
//   * журнал событий сервера → Room.
//
// Зависит от :core:data ради описания полезной нагрузки операций:
// это общий контракт между тем, кто кладёт операцию в очередь,
// и тем, кто её отправляет. Обратной зависимости нет — репозитории
// про этот модуль не знают.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.database)
    implementation(projects.core.network)
    implementation(projects.core.data)
    implementation(projects.core.datastore)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)

    // Тесты идут против настоящего HTTP-клиента и настоящего разбора JSON:
    // подделка источника данных не проверила бы соответствие DTO ответам
    // сервера, а именно там расхождения и возникают.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.retrofit)
    testImplementation(libs.retrofit.serialization)
    testImplementation(libs.okhttp)
    testImplementation(libs.androidx.test.core)
}
