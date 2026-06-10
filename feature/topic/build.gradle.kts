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
}
