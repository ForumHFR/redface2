plugins {
    id("redface.android.compose.library")
}

android {
    namespace = "fr.forumhfr.redface2.core.ui"

    // Compose UI test (#130 / Phase 2F-A) needs Android resources at JVM unit-test time : the
    // `createComposeRule()` host activity reads them on inflation. Same convention as :core:data.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Stubs `android.util.Log.*` to no-op so production code paths exercised by Compose
            // (PostRenderer, Coil) do not crash with "not mocked" on JVM. Matches :core:data.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    // Coil 3 split the network fetcher out of coil-compose. Without this dependency, AsyncImage
    // resolves http(s) models to a no-op and every smiley / inline / block image stays on its
    // placeholder. The dependency must reach :app's runtime classpath, so it lives here next to
    // the Compose entry points that use AsyncImage.
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit4)
    // #130 — Robolectric runtime hosts `createComposeRule()` on JVM ; the manifest is debug-only
    // and pulls the Activity surrogate the rule mounts internally ; the BOM platform aligns the
    // ui-test artifacts with the production Compose versions.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
