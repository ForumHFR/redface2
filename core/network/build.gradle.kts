plugins {
    id("redface.android.library")
    id("redface.android.hilt.library")
}

android {
    namespace = "fr.forumhfr.redface2.core.network"

    testOptions {
        unitTests {
            // android.util.Log is invoked from AuthRemoteDataSource (alpha-friendly logcat
            // trail). Without this flag, every Log.* call throws "not mocked" in JVM unit
            // tests. Default-values stubs Log.* to no-ops returning 0, which is what we
            // want — we don't assert on logcat in tests.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:domain"))

    implementation(platform(libs.okhttp.bom))
    api(libs.okhttp)
    implementation(libs.okhttp.logging)

    // androidx.tracing for `Trace.beginSection`/`endSection` and the `trace { … }` extension
    // used by `HfrClient` to mark `rf2.topic.network` and `rf2.topic.body_read` phases. See
    // #117 / `docs/guides/profiling.md` for the section-name catalogue.
    implementation(libs.androidx.tracing)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(testFixtures(project(":core:domain")))
}

// Tests read captures from :core:parser and :core:data by relative path.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("core/parser/src/test/resources/fixtures"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("sharedParserFixtures")
    inputs.files(rootProject.fileTree("core/data/src/test/resources/fixtures") {
        include(
            "rest_categories*.json",
            "rest_topics_cat23_subcat550_p1.json",
            "rest_cat23_participated.json",
            "rest_cat13_favorites_empty_despite_sticky.json",
            "rest_global_participated.json",
        )
    }).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("sharedRestFixtures")
}
