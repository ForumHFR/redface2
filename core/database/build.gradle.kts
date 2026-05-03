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

    sourceSets {
        // MigrationTestHelper resolves schemas from the test assets folder. Pin the
        // exported `schemas/` directory there so the v1 fixture is visible without
        // copying — keeps a single source of truth in `$projectDir/schemas/`.
        named("test") {
            assets.srcDir("$projectDir/schemas")
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
    testImplementation(libs.room.testing)
}
