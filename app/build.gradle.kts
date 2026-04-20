plugins {
    id("redface.android.compose.application")
    id("redface.android.hilt.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "fr.forumhfr.redface2"

    defaultConfig {
        applicationId = "fr.forumhfr.redface2"
        versionCode = 1
        versionName = "0.1.0-bootstrap"
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(project(":feature:forum"))
    implementation(project(":feature:topic"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:messages"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.konsist)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
