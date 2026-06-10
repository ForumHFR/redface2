plugins {
    id("redface.android.library")
    id("redface.android.hilt.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fr.forumhfr.redface2.core.data"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // android.util.Log.* gets called from production code paths reached by JVM
            // unit tests (DefaultForumRepository's onFailure logger, future mappers).
            // Without this flag, every Log.* call throws "not mocked"; default-values
            // stubs them to no-ops returning 0, which is what we want — we don't assert
            // on logcat in tests. Same convention as :core:network.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:parser"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    // androidx.tracing for `rf2.topic.parse_html`, `rf2.topic.map_domain`, `rf2.topic.room_read`,
    // `rf2.topic.room_write` markers around the topic-page repository pipeline (#117).
    implementation(libs.androidx.tracing)

    // Coil singleton accessor for DefaultImageCacheMaintenance (#314) — the `coil` artifact
    // carries SingletonImageLoader and exposes ImageLoader/MemoryCache/DiskCache via its
    // api dependency on coil-core. No compose/network artifacts here: :core:data only
    // clears caches, it never loads images.
    implementation(libs.coil.core)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
}
