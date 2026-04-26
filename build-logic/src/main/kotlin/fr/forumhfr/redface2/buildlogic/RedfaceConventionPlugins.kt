package fr.forumhfr.redface2.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class RedfaceAndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk = libs.versionInt("android-compileSdk")

            defaultConfig {
                minSdk = libs.versionInt("android-minSdk")
                targetSdk = libs.versionInt("android-targetSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            buildTypes {
                debug {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                }

                release {
                    isMinifyEnabled = false
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }

            lint {
                abortOnError = true
            }
        }

        configureReleaseBundleStamping()
    }
}

/**
 * After every successful `:app:bundleRelease`, copy the produced `app-release.aab` to a
 * stamped sibling `redface2-v<versionCode>-<YYYYMMDD>-<sha>.aab`. The original output stays
 * in place so downstream tooling that hard-codes `app-release.aab` (Play Console upload
 * scripts, signing init scripts, tests) keeps working — the stamped copy is purely for
 * archival so a contributor can keep several AABs side by side without overwriting.
 *
 * versionCode is resolved in [Project.afterEvaluate] (so any local init script that bumps
 * defaultConfig.versionCode wins) and captured as a plain Int to keep the action body
 * configuration-cache friendly — wrapping it in a Provider that touches the
 * ApplicationExtension would pin the project state into the cached task action.
 */
private fun Project.configureReleaseBundleStamping() {
    val outputDir = layout.buildDirectory.dir("outputs/bundle/release")
    val gitShaProvider = providers.exec {
        commandLine("git", "-C", rootDir.absolutePath, "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText

    val stamp = tasks.register("stampReleaseBundle") {
        dependsOn("bundleRelease")
    }
    tasks.matching { it.name == "bundleRelease" }.configureEach { finalizedBy(stamp) }

    afterEvaluate {
        val versionCode = extensions.getByType<ApplicationExtension>().defaultConfig.versionCode ?: 0
        stamp.configure {
            doLast {
                val src = outputDir.get().file("app-release.aab").asFile
                if (!src.exists()) {
                    logger.lifecycle("[stamp] no app-release.aab found, skipping")
                    return@doLast
                }
                val sha = gitShaProvider.get().trim().ifEmpty { "nogit" }
                val date = java.time.LocalDate.now()
                    .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val dst = src.parentFile.resolve("redface2-v$versionCode-$date-$sha.aab")
                src.copyTo(dst, overwrite = true)
                logger.lifecycle("[stamp] ${src.name} -> ${dst.name}")
            }
        }
    }
}

class RedfaceAndroidComposeApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("redface.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<ApplicationExtension> {
            buildFeatures {
                compose = true
            }
        }
    }
}

class RedfaceAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk = libs.versionInt("android-compileSdk")

            defaultConfig {
                minSdk = libs.versionInt("android-minSdk")
                consumerProguardFiles("consumer-rules.pro")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            lint {
                abortOnError = true
            }
        }
    }
}

class RedfaceAndroidComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("redface.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<LibraryExtension> {
            buildFeatures {
                compose = true
            }
        }
    }
}

class RedfaceAndroidHiltApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.withPlugin("com.android.application") {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            dependencies.add("implementation", libs.library("hilt-android"))
            dependencies.add("ksp", libs.library("hilt-android-compiler"))
        }
    }
}

class RedfaceAndroidHiltLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.withPlugin("com.android.library") {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            dependencies.add("implementation", libs.library("hilt-android"))
            dependencies.add("ksp", libs.library("hilt-android-compiler"))
        }
    }
}

class RedfaceKotlinJvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

private val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

private fun VersionCatalog.versionInt(alias: String): Int =
    findVersion(alias).get().requiredVersion.toInt()

private fun VersionCatalog.library(alias: String) =
    findLibrary(alias).get().get()
