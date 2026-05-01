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

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}
