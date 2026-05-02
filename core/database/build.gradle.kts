plugins {
    id("redface.android.library")
    id("redface.android.hilt.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fr.forumhfr.redface2.core.database"

    testOptions {
        unitTests {
            // FlagDao + RedfaceDatabase Robolectric tests need Android resources
            // (Context for Room.inMemoryDatabaseBuilder).
            isIncludeAndroidResources = true
        }
    }
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

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
