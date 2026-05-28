plugins {
    id("redface.android.compose.library")
    id("redface.android.hilt.library")
}

android {
    namespace = "fr.forumhfr.redface2.feature.profile"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Review feedback C2 (a11y back button): the detekt rule ForbiddenImport blocks
    // `androidx.compose.material.*` (legacy Material 2 surface), which includes
    // `material-icons-core` — so we cannot import `Icons.AutoMirrored.Filled.ArrowBack`.
    // The back button uses `IconButton + Text("←") + Modifier.semantics { contentDescription }`
    // instead, which gives a real TalkBack label without pulling Material 2 in. See
    // ProfileScreen.kt § TopAppBar.navigationIcon.
    // Review feedback M4 — `coil.compose` removed: `:feature:profile` does not use AsyncImage
    // directly. The avatar rendering goes through `RedfaceUserAvatar` from `:core:ui` which
    // already pulls Coil in its own classpath. No transitive guarantee — if a future change
    // needs AsyncImage here, add the dependency back explicitly.

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
