plugins {
    id("redface.kotlin.jvm.library")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.jsoup)

    testImplementation(libs.junit4)
}
