plugins {
    id("redface.android.compose.application")
    id("redface.android.hilt.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI-time signing config. The keystore (.jks) is provided as base64 in the GitHub Action
// secret UPLOAD_KEYSTORE_BASE64 — the workflow decodes it into UPLOAD_KEYSTORE_PATH before
// calling Gradle. When all four env vars are present we wire `buildTypes.release.signingConfig`
// to the "upload" config below. When they are absent (local dev), the historical
// .gradle-user/signing/signing.init.gradle init-script still kicks in via `--init-script` and
// signs with the dev-only password; this codepath stays untouched so the local dogfood flow
// keeps working without any contributor migration.
val ciKeystorePath = providers.environmentVariable("UPLOAD_KEYSTORE_PATH").orNull
val ciKeystorePassword = providers.environmentVariable("UPLOAD_KEYSTORE_PASSWORD").orNull
val ciKeyAlias = providers.environmentVariable("UPLOAD_KEY_ALIAS").orNull
val ciKeyPassword = providers.environmentVariable("UPLOAD_KEY_PASSWORD").orNull
val hasCiSigningConfig =
    !ciKeystorePath.isNullOrBlank() &&
        !ciKeystorePassword.isNullOrBlank() &&
        !ciKeyAlias.isNullOrBlank() &&
        !ciKeyPassword.isNullOrBlank()

android {
    namespace = "fr.forumhfr.redface2"

    defaultConfig {
        applicationId = "fr.forumhfr.redface2"
        // Bump versionCode + versionName at every release. Play Console rejects any AAB
        // whose versionCode is already uploaded, so this is the canonical source of truth
        // (the local signing init-script no longer overrides these — it only injects the
        // upload signing config).
        //
        // Naming convention (effective v39 / 0.2.0): pure semver `MAJOR.MINOR.PATCH`,
        // detached from the spec/site version (`docs/_config.yml`). The previous
        // `0.1.0-phaseN.X` tail confused the app version with the project phase milestone;
        // they evolve on different cadences. Pre-release suffix lives in the Play Console
        // track (alpha) and in the GitHub Release flag, not in versionName itself.
        // versionName is also surfaced in the app footer via BuildConfig.VERSION_NAME so
        // dogfood builds advertise their lineage to the user.
        versionCode = 60
        versionName = "0.3.20"

        // Manifest placeholder so a side-by-side install (dogfood/preview overlay)
        // can override the launcher label without touching tracked manifest/strings.
        // Defaults to the in-app string resource for production builds.
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    if (hasCiSigningConfig) {
        signingConfigs {
            create("upload") {
                storeFile = file(ciKeystorePath!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
        buildTypes.named("release") {
            signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildFeatures {
        // Expose BuildConfig.VERSION_NAME / VERSION_CODE to Kotlin code so the
        // placeholder screens can show them while :feature:settings (the future
        // home of an About screen) is empty.
        buildConfig = true
    }
}

// Play Console upload is delegated to the GitHub Action `r0adkll/upload-google-play` in
// .github/workflows/release.yml — it consumes the AAB artefact produced by `:app:bundleRelease`
// and pushes it via the Play Developer API directly. Keeping the upload step out of Gradle
// avoids a hard dependency on a Gradle plugin tracking AGP version churn (gradle-play-publisher
// 4.0.0 had documented incompat issues with AGP 9, and the project went into maintenance
// mode in April 2026 — see https://github.com/Triple-T/gradle-play-publisher/issues/1188).
//
// Release notes per locale live in app/src/main/play/whatsnew/whatsnew-<BCP47> (flat files,
// e.g. whatsnew-fr-FR, whatsnew-en-US — the layout that `r0adkll/upload-google-play` expects)
// and are passed to the upload action via its `whatsNewDirectory` input.

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:ui"))

    implementation(project(":feature:flags"))
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
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)

    // The Coil singleton ImageLoader is configured in `RedfaceApplication` via
    // `SingletonImageLoader.Factory` so the GIF decoder is registered once and shared by every
    // `AsyncImage` in the app (most HFR smileys and a fair share of `[img]` payloads are
    // animated GIFs). Both `coil-core` and `coil-gif` must therefore be on the `:app`
    // classpath. `coil-network-okhttp` is also pinned at the app layer to keep the HTTP
    // fetcher resolvable from the singleton config; `:core:ui` keeps its own coil-compose +
    // coil-network-okhttp for the `AsyncImage` call sites.
    implementation(libs.coil.core)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.konsist)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    // :core:parser is reachable transitively but explicit here for the cross-
    // module round-trip tests that prove toolbar emission parses back into the
    // expected AST (`:app` is the only place Konsist allows feature/parser
    // crossover in tests).
    testImplementation(project(":core:parser"))
}
