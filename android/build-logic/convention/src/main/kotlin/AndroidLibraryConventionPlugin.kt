import com.android.build.api.dsl.LibraryExtension
import com.mango.buildlogic.addCommonUnitTestDependencies
import com.mango.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `mango.android.library` — базовый плагин для всех Android-библиотечных модулей.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            // Библиотеки не нуждаются в BuildConfig — экономим на генерации.
            buildFeatures.buildConfig = false
            resourcePrefix = "mango_"
        }

        addCommonUnitTestDependencies()
    }
}
