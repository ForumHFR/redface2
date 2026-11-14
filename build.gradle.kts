plugins {
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("verifyRoborazziDebug") {
    group = "verification"
    description = "Verify the six modules' screenshots against versioned references (zero pixel tolerance)."
    require(providers.gradleProperty("roborazzi.mode").getOrElse("verify") == "verify") {
        "verifyRoborazziDebug requires -Proborazzi.mode=verify (the default)"
    }
    dependsOn(
        ":core:ui:testDebugUnitTest",
        ":feature:topic:testDebugUnitTest",
        ":feature:messages:testDebugUnitTest",
        ":feature:editor:testDebugUnitTest",
        ":feature:settings:testDebugUnitTest",
        ":feature:forum:testDebugUnitTest",
    )
}

val detektCli by configurations.creating

dependencies {
    detektCli(libs.detekt.cli)
}

val detektCliTask = tasks.register<JavaExec>("detektCliCheck") {
    group = "verification"
    description = "Run Detekt CLI with the project config."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")

    val reportsDir = layout.buildDirectory.dir("reports/detekt")

    args(
        "--input", projectDir.absolutePath,
        "--config", file("config/detekt/detekt.yml").absolutePath,
        "--build-upon-default-config",
        "--parallel",
        "--base-path", projectDir.absolutePath,
        "--report", "html:${reportsDir.get().file("detekt.html").asFile.absolutePath}",
        "--report", "sarif:${reportsDir.get().file("detekt.sarif").asFile.absolutePath}",
        "--report", "md:${reportsDir.get().file("detekt.md").asFile.absolutePath}",
        "--excludes", "**/build/**,**/.gradle/**,**/.gradle-user/**,**/generated/**,**/.claude/**",
    )
}

tasks.register("detektAll") {
    group = "verification"
    description = "Run Detekt CLI on the full project."
    dependsOn(detektCliTask)
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
