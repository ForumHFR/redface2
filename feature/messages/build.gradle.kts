plugins {
    id("redface.android.compose.library")
    id("redface.android.hilt.library")
}

android {
    namespace = "fr.forumhfr.redface2.feature.messages"

    testOptions {
        unitTests {
            // #958/#1040 — PostRendererHostMatrixTest and MessageCardRoborazziTest mount
            // MessageCard via `createComposeRule()` and read `stringResource`, so the host activity
            // needs the merged Android resources at JVM unit-test time. Same convention as
            // :core:ui / :feature:topic (Compose UI tests).
            isIncludeAndroidResources = true
            all {
                // Record-only Roborazzi harness, identical to :core:ui: hardware PixelCopy keeps
                // Compose drawing faithful and the plain test task writes the diagnostic PNG.
                // The Gradle plugin stays unapplied because it is incompatible with AGP 9.
                it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
                it.systemProperties["roborazzi.test.record"] = "true"
            }
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
    // #1040 — record-only visual control of the real MP card. No Roborazzi Gradle plugin under
    // AGP 9: captureRoboImage writes under build/outputs/roborazzi from testDebugUnitTest.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
