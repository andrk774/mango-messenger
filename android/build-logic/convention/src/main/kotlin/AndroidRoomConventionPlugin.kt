import androidx.room.gradle.RoomExtension
import com.mango.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `mango.android.room` — Room + KSP + экспорт схемы.
 *
 * Используется именно Room Gradle-плагин, а не `ksp { arg("room.schemaLocation", ...) }`:
 * вариант с ksp-аргументом вызывает гонку между debug/release при parallel-сборке
 * (проверено — сборка падала на kspReleaseKotlin с JsonDecodingException).
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("androidx.room")

        extensions.configure<RoomExtension> {
            schemaDirectory("$projectDir/schemas")
        }

        dependencies {
            add("implementation", libs.findLibrary("room-runtime").get())
            add("implementation", libs.findLibrary("room-ktx").get())
            add("ksp", libs.findLibrary("room-compiler").get())
            add("testImplementation", libs.findLibrary("room-testing").get())
        }
    }
}
