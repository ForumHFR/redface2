plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "fr.forumhfr.redface2.core.ui"

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:model"))
}
