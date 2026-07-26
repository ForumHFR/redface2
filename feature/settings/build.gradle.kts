plugins {
    id("redface.android.compose.library")
    id("redface.android.hilt.library")
}

android {
    namespace = "fr.forumhfr.redface2.feature.settings"

    testOptions {
        unitTests {
            // #884 — SettingsCatalogueFullWidthPostsTest mounts the real catalogue via
            // `createComposeRule()` and resolves `stringResource`, so the host activity needs the
            // merged Android resources at JVM unit-test time. Same convention as :feature:topic
            // and :core:ui (Compose UI tests).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))

    // #494 — BackHandler intercepts system/gesture back to close the settings search instead of
    // popping the whole route (nav3 otherwise pops it).
    implementation(libs.androidx.activity.compose)
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
    // #884 — Robolectric hosts `createComposeRule()` on the JVM so the catalogue test can exercise
    // the full-width-posts row (section placement, search index, tap dispatch, disabled gate)
    // without a device. The BOM aligns the ui-test artifacts with the production Compose versions;
    // the ui-test-manifest (debug-only) pulls the Activity surrogate the rule mounts internally.
    // Same harness as :feature:topic and :core:ui.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
