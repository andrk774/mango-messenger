import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.ApplicationExtension
import com.mango.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * `mango.android.compose` — включает Compose и подключает общий набор Compose-зависимостей.
 *
 * Применяется ПОСЛЕ mango.android.library / mango.android.application.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val commonExtension = when {
            pluginManager.hasPlugin("com.android.application") ->
                extensions.getByType<ApplicationExtension>()
            else ->
                extensions.getByType<com.android.build.api.dsl.LibraryExtension>()
        } as CommonExtension

        commonExtension.buildFeatures.compose = true

        // Проектное решение: Material 3 помечает экспериментальными вещи,
        // без которых не собрать обычный экран (Scaffold + TopAppBar
        // со scrollBehavior). Аннотировать каждый экран вручную — это шум,
        // который ничего не даёт: отказаться от этих API мы всё равно не можем.
        // Opt-in объявляется один раз здесь.
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension>("kotlin") {
            compilerOptions {
                freeCompilerArgs.addAll(
                    "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                    "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                )
            }
        }

        addComposeDependencies(libs)
    }

    private fun Project.addComposeDependencies(libs: VersionCatalog) {
        val bom = libs.findLibrary("compose-bom").get()
        dependencies {
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))

            add("implementation", libs.findLibrary("compose-ui").get())
            add("implementation", libs.findLibrary("compose-ui-graphics").get())
            add("implementation", libs.findLibrary("compose-foundation").get())
            add("implementation", libs.findLibrary("compose-material3").get())
            add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("lifecycle-viewmodel-compose").get())

            add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
            add("debugImplementation", libs.findLibrary("compose-ui-test-manifest").get())

            add("androidTestImplementation", libs.findLibrary("compose-ui-test-junit4").get())
        }
    }
}
