plugins {
    id("redface.android.compose.library")
    id("redface.android.roborazzi")
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
    // #324 — `error/HfrErrorLabels.kt` maps the domain `HfrErrorKind` classification to the
    // shared `error_hfr_server_down` / `error_no_connection` string resources. No cycle:
    // :core:domain only depends on :core:model.
    implementation(project(":core:domain"))
    implementation(project(":core:model"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    // #1128 — shared photo / document picker launchers for every editor surface.
    implementation(libs.androidx.activity.compose)
    // #1040 — androidx.core.net.toUri for the shared image-menu host label and browser Intent.
    implementation(libs.androidx.core.ktx)
    // #1513 / P20 — current Activity bounds, including API 29 adjustResize compatibility.
    implementation(libs.androidx.window)
    implementation(libs.materialKolor.utilities)
    implementation(libs.coil.compose)
    // #182 — fullscreen-only pan/pinch/double-tap viewer. Kept behind
    // viewer/ZoomableRemoteImage.kt so the rendering engine remains replaceable.
    implementation(libs.telephoto.zoomable.image.coil3)
    // #959 — EXIF orientation read by the header-only intrinsic probe (ProbeMetadataDecoder).
    implementation(libs.androidx.exifinterface)
    // #959 — Lifecycle.currentStateAsState() gates GIF animation on RESUMED (§3 GIF).
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Coil 3 split the network fetcher out of coil-compose. Without this dependency, AsyncImage
    // resolves http(s) models to a no-op and every smiley / inline / block image stays on its
    // placeholder. The dependency must reach :app's runtime classpath, so it lives here next to
    // the Compose entry points that use AsyncImage.
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit4)
    // #956 (passe images, Lot 1A) — the segmentation policy tests parse the REAL bench fixture
    // (charte : fixtures from live HFR, they dictate exhaustiveness). Test-only dependency on the
    // parser's MAIN classes ; the fixture HTML itself lives in THIS module's test resources.
    testImplementation(project(":core:parser"))
    // #175 — FakeImageLoaderEngine + ColorImage(width,height) give deterministic intrinsic-size
    // measurements under Robolectric (no network/decode), and runTest drives the suspend measure.
    testImplementation(libs.coil.test)
    // #960 P4 — the exotic-formats integration test drives the REAL SvgDecoder (prod parity:
    // RedfaceApplication registers it in the singleton loader).
    testImplementation(libs.coil.svg)
    // v1.6-10 — real animated GIF decoder and independent HTTP transfer counting.
    testImplementation(libs.coil.gif)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // #130 — Robolectric runtime hosts `createComposeRule()` on JVM ; the manifest is debug-only
    // and pulls the Activity surrogate the rule mounts internally ; the BOM platform aligns the
    // ui-test artifacts with the production Compose versions.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Screenshot references are compared by default; explicit record mode updates them.
    // Shared configuration and exact comparator: redface.android.roborazzi (#1571).
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // #958 (Lot 2) — SPIKE instrumenté : la garde « pas de tap image pendant une sélection
    // texte active » ne peut PAS se tester sous Robolectric (crash magnifier). androidTest connecté
    // (S10e) sur du contenu synthétique — voir SelectionTapSpikeTest. Runner déjà fourni par la
    // convention (testInstrumentationRunner AndroidJUnitRunner).
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coil.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
