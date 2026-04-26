plugins {
    id("redface.android.library")
    id("redface.android.hilt.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fr.forumhfr.redface2.core.database"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))

    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
