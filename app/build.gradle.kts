plugins {
    id("redface.android.compose.application")
    id("redface.android.hilt.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "fr.forumhfr.redface2"

    defaultConfig {
        applicationId = "fr.forumhfr.redface2"
        // Bump versionCode + versionName at every release. Play Console rejects any AAB
        // whose versionCode is already uploaded, so this is the canonical source of truth
        // (the local signing init-script no longer overrides these — it only injects the
        // upload signing config).
        // versionName is also surfaced in the app footer via BuildConfig.VERSION_NAME so
        // dogfood builds advertise their phase / commit lineage to the user.
        versionCode = 28
        versionName = "0.1.0-phase1d.0"

        // Manifest placeholder so a side-by-side install (dogfood/preview overlay)
        // can override the launcher label without touching tracked manifest/strings.
        // Defaults to the in-app string resource for production builds.
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    buildFeatures {
        // Expose BuildConfig.VERSION_NAME / VERSION_CODE to Kotlin code so the
        // placeholder screens can show them while :feature:settings (the future
        // home of an About screen) is empty.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
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

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.konsist)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
