plugins {
    id("redface.android.compose.library")
    id("redface.android.hilt.library")
}

android {
    namespace = "fr.forumhfr.redface2.feature.topic"

    testOptions {
        unitTests {
            // `androidx.tracing.Trace` delegates to `android.os.Trace`, which is unavailable
            // in JVM unit tests. The default-values stub returns no-ops for void methods, so
            // the tracing calls in `TopicViewModel.{begin,end}FirstContentSection` become
            // no-ops in JVM tests instead of throwing "not mocked". Same convention as
            // :core:network and :core:data.
            isReturnDefaultValues = true
            // #436 — TopicPostCardMultiQuoteTest mounts the card via `createComposeRule()` and
            // reads `stringResource`, so the host activity needs the merged Android resources at
            // JVM unit-test time. Same convention as :core:ui (Compose UI tests).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(project(":core:extension"))

    // androidx.core.net.toUri for the post-menu « Ouvrir dans le navigateur » action (#362
    // follow-up) — same ktx idiom as the mailto: intent in :app.
    implementation(libs.androidx.core.ktx)
    // BackHandler for the in-screen #782 quote-jump unwind (#895 étape 4 — the page engine no
    // longer swaps nav entries, so the interception moved from :app to the screen).
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // androidx.tracing for the `rf2.topic.first_content` marker emitted on the first state
    // transition into `Mode.Loaded` (#117).
    implementation(libs.androidx.tracing)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // #436 — Robolectric hosts `createComposeRule()` on the JVM so TopicPostCardMultiQuoteTest
    // can exercise the per-post « + » affordance (gating, label flip, tap callback) without a
    // device. Non-roborazzi (no screenshot baseline), so the AGP-9 roborazzi plugin gap does not
    // apply. The BOM aligns the ui-test artifacts with the production Compose versions; the
    // ui-test-manifest (debug-only) pulls the Activity surrogate the rule mounts internally.
    // Same harness as :core:ui.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
