pluginManagement {
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "redface2"

include(":app")

include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:network")
include(":core:parser")
include(":core:database")
include(":core:ui")
include(":core:extension")

include(":feature:forum")
include(":feature:topic")
include(":feature:editor")
include(":feature:messages")
include(":feature:auth")
include(":feature:search")
include(":feature:settings")

// Phase 4 — modules extension gardés visibles mais hors bootstrap v1.
// include(":feature:bookmarks")
// include(":feature:blacklist")
// include(":feature:qualitay")
// include(":feature:redflag")
// include(":feature:colortag")
// include(":feature:imagehost")
// include(":feature:gifpicker")
// include(":feature:stats")
