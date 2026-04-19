plugins {
    id("redface.android.library")
}

android {
    namespace = "fr.forumhfr.redface2.core.database"
}

dependencies {
    implementation(project(":core:model"))
}
