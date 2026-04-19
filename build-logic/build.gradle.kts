plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("redfaceAndroidApplication") {
            id = "redface.android.application"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceAndroidApplicationConventionPlugin"
        }
        register("redfaceAndroidLibrary") {
            id = "redface.android.library"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceAndroidLibraryConventionPlugin"
        }
        register("redfaceAndroidComposeLibrary") {
            id = "redface.android.compose.library"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceAndroidComposeLibraryConventionPlugin"
        }
        register("redfaceKotlinJvmLibrary") {
            id = "redface.kotlin.jvm.library"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceKotlinJvmLibraryConventionPlugin"
        }
    }
}
