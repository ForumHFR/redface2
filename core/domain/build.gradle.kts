plugins {
    id("redface.kotlin.jvm.library")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:model"))
    api(libs.javax.inject)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)

    testFixturesApi(libs.junit4)
    testFixturesApi(libs.kotlinx.coroutines.test)
}
