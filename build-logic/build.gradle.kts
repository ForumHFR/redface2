plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.android.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("redfaceAndroidApplication") {
            id = "redface.android.application"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceAndroidApplicationConventionPlugin"
        }
        register("redfaceAndroidComposeApplication") {
            id = "redface.android.compose.application"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceAndroidComposeApplicationConventionPlugin"
        }
        register("redfaceAndroidLibrary") {
            id = "redface.android.library"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceAndroidLibraryConventionPlugin"
        }
        register("redfaceAndroidComposeLibrary") {
            id = "redface.android.compose.library"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceAndroidComposeLibraryConventionPlugin"
        }
        register("redfaceAndroidHiltApplication") {
            id = "redface.android.hilt.application"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceAndroidHiltApplicationConventionPlugin"
        }
        register("redfaceAndroidHiltLibrary") {
            id = "redface.android.hilt.library"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceAndroidHiltLibraryConventionPlugin"
        }
        register("redfaceKotlinJvmLibrary") {
            id = "redface.kotlin.jvm.library"
            implementationClass = "fr.forumhfr.redface2.buildlogic.RedfaceKotlinJvmLibraryConventionPlugin"
        }
    }
}
