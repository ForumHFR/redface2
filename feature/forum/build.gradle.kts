plugins {
    id("redface.android.compose.library")
    id("redface.android.roborazzi")
    id("redface.android.hilt.library")
}

android {
    namespace = "fr.forumhfr.redface2.feature.forum"

    testOptions {
        unitTests {
            // #1149 — ForumCategoryContentInsetsTest mounts the category screen body via
            // `createComposeRule()` and reads `stringResource`, so the host activity needs the
            // merged Android resources at JVM unit-test time. Same convention as :core:ui /
            // :feature:topic / :feature:messages (Compose UI tests).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // #1149 — Robolectric hosts `createComposeRule()` on the JVM so the category screen body can
    // be mounted with synthetic system-bar insets (geometry proof + compared Roborazzi
    // captures). The BOM aligns the ui-test artifacts; the debug manifest pulls the Activity
    // surrogate mounted by the rule. Same setup as :feature:topic / :feature:messages.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
