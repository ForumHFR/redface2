plugins {
    id("redface.kotlin.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.javax.inject)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
