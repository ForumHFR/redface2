plugins {
    id("redface.android.compose.library")
}

android {
    namespace = "fr.forumhfr.redface2.feature.settings"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
}
