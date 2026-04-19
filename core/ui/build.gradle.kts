plugins {
    id("redface.android.compose.library")
}

android {
    namespace = "fr.forumhfr.redface2.core.ui"
}

dependencies {
    implementation(project(":core:model"))
}
