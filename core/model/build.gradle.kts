plugins {
    id("redface.kotlin.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.core)
    testImplementation(libs.junit4)
}
