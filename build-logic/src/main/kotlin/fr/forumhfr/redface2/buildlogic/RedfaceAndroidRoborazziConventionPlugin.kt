package fr.forumhfr.redface2.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/** Configures the plain Roborazzi 1.77.0 artifacts; the upstream Gradle plugin is not needed. */
class RedfaceAndroidRoborazziConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val mode = providers.gradleProperty("roborazzi.mode").getOrElse("verify")
        require(mode in setOf("record", "verify")) {
            "roborazzi.mode must be record or verify (was '$mode')"
        }
        val references = layout.projectDirectory.dir("src/test/screenshots")

        extensions.configure<LibraryExtension> {
            // One strict comparison helper, compiled into the six modules' test source sets.
            sourceSets.getByName("test").kotlin.directories +=
                rootProject.layout.projectDirectory.dir("build-logic/src/roborazziTest/kotlin").asFile.absolutePath
        }

        tasks.withType<Test>().configureEach {
            val comparisons = layout.buildDirectory.dir("outputs/roborazzi/$name")
            val results = layout.buildDirectory.dir("test-results/roborazzi/$name")
            inputs.property("roborazzi.mode", mode)
            inputs.dir(references)
                .withPropertyName("roborazziReferences")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.dir(comparisons).withPropertyName("roborazziComparisons")
            outputs.dir(results).withPropertyName("roborazziResults")
            // Recording mutates the references: it must execute even if all inputs are unchanged.
            outputs.upToDateWhen { mode != "record" }
            outputs.doNotCacheIf("Recording updates versioned screenshot references") { mode == "record" }

            systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            systemProperty("roborazzi.test.record", (mode == "record").toString())
            systemProperty("roborazzi.test.verify", (mode == "verify").toString())
            systemProperty("roborazzi.test.compare", "false")
            systemProperty("roborazzi.output.dir", references.asFile.absolutePath)
            systemProperty("roborazzi.compare.output.dir", comparisons.get().asFile.absolutePath)
            systemProperty("roborazzi.result.dir", results.get().asFile.absolutePath)
            systemProperty("roborazzi.record.filePathStrategy", "relativePathFromCurrentDirectory")
            systemProperty("roborazzi.record.resizeScale", "1.0")
        }
    }
}
