plugins {
    id("redface.android.application")
}

android {
    namespace = "fr.forumhfr.redface2"

    defaultConfig {
        applicationId = "fr.forumhfr.redface2"
        versionCode = 1
        versionName = "0.1.0-bootstrap"
    }
}

dependencies {
    implementation(project(":core:data"))

    implementation(project(":feature:forum"))
    implementation(project(":feature:topic"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:messages"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))
}
