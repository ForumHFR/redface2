plugins {
    id("redface.android.library")
}

android {
    namespace = "fr.forumhfr.redface2.core.data"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:parser"))
    implementation(project(":core:database"))
}
