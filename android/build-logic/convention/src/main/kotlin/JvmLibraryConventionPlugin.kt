import com.mango.buildlogic.addCommonUnitTestDependencies
import com.mango.buildlogic.configureKotlinJvm
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `mango.jvm.library` — чистый Kotlin/JVM-модуль без Android-зависимостей.
 *
 * Используется для :core:model и :core:common — это держит domain-слой
 * свободным от Android и делает его тесты быстрыми (без Robolectric).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        configureKotlinJvm()
        addCommonUnitTestDependencies()
    }
}
