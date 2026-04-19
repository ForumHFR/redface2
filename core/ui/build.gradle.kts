plugins {
    id("redface.android.compose.library")
}

android {
    namespace = "fr.forumhfr.redface2.core.ui"
}

dependencies {
    implementation(project(":core:model"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
}
