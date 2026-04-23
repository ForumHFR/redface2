plugins {
    id("redface.android.compose.library")
}

android {
    namespace = "fr.forumhfr.redface2.feature.topic"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:extension"))

    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
}
