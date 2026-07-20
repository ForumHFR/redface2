plugins {
    id("redface.android.compose.library")
    id("redface.android.hilt.library")
}

android {
    namespace = "fr.forumhfr.redface2.feature.messages"

    testOptions {
        unitTests {
            // #958 — PostRendererHostMatrixTest mounts MessageCard via `createComposeRule()` and
            // reads `stringResource`, so the host activity needs the merged Android resources at
            // JVM unit-test time. Same convention as :core:ui / :feature:topic (Compose UI tests).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    // #958 — PostRendererHostMatrixTest pins the MP host's total image inertia on the REAL
    // MessageCard: same JVM Compose-test harness as :core:ui / :feature:topic, with the fake
    // Coil engine keeping image rendering off the network (coil is `implementation` in
    // :core:ui, so the test classpath needs its own coil artifacts).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coil.core)
    testImplementation(libs.coil.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
