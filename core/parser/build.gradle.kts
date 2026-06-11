plugins {
    id("redface.kotlin.jvm.library")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.jsoup)
    // MPStorage (#6/ADR-014) : tolerant JsonElement-level parsing of the v0.1 envelope.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
}
