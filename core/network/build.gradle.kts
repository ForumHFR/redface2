plugins {
    id("redface.android.library")
    id("redface.android.hilt.library")
}

android {
    namespace = "fr.forumhfr.redface2.core.network"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))

    implementation(platform(libs.okhttp.bom))
    api(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
}
