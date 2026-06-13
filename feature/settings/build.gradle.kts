plugins {
    id("redface.android.compose.library")
    id("redface.android.hilt.library")
}

android {
    namespace = "fr.forumhfr.redface2.feature.settings"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // #459 PR3 — « Mes images uploadées » renders Coil thumbnails via AsyncImage. Like :feature:editor
    // (the only other feature calling AsyncImage directly), we add only coil-compose ; the Coil 3
    // network fetcher (coil-network-okhttp) already reaches the app runtime classpath via :core:ui,
    // and the SingletonImageLoader is configured at the :app layer.
    implementation(libs.coil.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
}
