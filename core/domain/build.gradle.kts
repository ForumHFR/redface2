plugins {
    id("redface.kotlin.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.javax.inject)
}
