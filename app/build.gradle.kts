plugins {
    id("redface.android.compose.application")
    id("redface.android.hilt.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI-time signing config. The keystore (.jks) is provided as base64 in the GitHub Action
// secret UPLOAD_KEYSTORE_BASE64 — the workflow decodes it into UPLOAD_KEYSTORE_PATH before
// calling Gradle. When all four env vars are present we wire `buildTypes.release.signingConfig`
// to the "upload" config below. When they are absent (local dev), the historical
// .gradle-user/signing/signing.init.gradle init-script still kicks in via `--init-script` and
// signs with the dev-only password; this codepath stays untouched so the local dogfood flow
// keeps working without any contributor migration.
val ciKeystorePath = providers.environmentVariable("UPLOAD_KEYSTORE_PATH").orNull
val ciKeystorePassword = providers.environmentVariable("UPLOAD_KEYSTORE_PASSWORD").orNull
val ciKeyAlias = providers.environmentVariable("UPLOAD_KEY_ALIAS").orNull
val ciKeyPassword = providers.environmentVariable("UPLOAD_KEY_PASSWORD").orNull
val hasCiSigningConfig =
    !ciKeystorePath.isNullOrBlank() &&
        !ciKeystorePassword.isNullOrBlank() &&
        !ciKeyAlias.isNullOrBlank() &&
        !ciKeyPassword.isNullOrBlank()

// #233 — CD-injected overrides (release.yml). Both are OPTIONAL: absent in local/dev builds, so
// the per-flavor defaults below apply.
//   -PappLabel="Redface 2 β"  → forces the launcher label for the build (lets the prod flavor /
//                                base applicationId carry the β/dev label on the Play track build).
//   -PversionCodeOverride=N   → overrides versionCode for BOTH ship channels (beta+dev). The CD
//                                (release.yml) computes N from the canonical git tag ledger
//                                (max(app-v* tags, the floor below) + 1) and injects it into the Play
//                                base-appId build AND the F-Droid suffixed build, so beta and dev
//                                share one strictly-increasing versionCode sequence.
val cliAppLabel = providers.gradleProperty("appLabel").orNull?.takeIf { it.isNotBlank() }
val cliVersionCode = providers.gradleProperty("versionCodeOverride").orNull?.toIntOrNull()

android {
    namespace = "fr.forumhfr.redface2"

    defaultConfig {
        applicationId = "fr.forumhfr.redface2"
        // versionCode: the CANONICAL source at ship time is the git `app-v<N>` tag ledger — the CD
        // injects `-PversionCodeOverride=max(app-v* tags, this floor)+1` (release.yml). The literal
        // below is therefore (a) the value used by LOCAL builds without the prop, and (b) a SAFETY
        // FLOOR for the CD: it must stay ≥ nothing-special but bumping it can never lower a shipped
        // code. versionName is the human marketing version, decoupled from versionCode.
        // (The local signing init-script does not override these — it only injects the upload config.)
        //
        // Naming convention (effective v39 / 0.2.0): pure semver `MAJOR.MINOR.PATCH`,
        // detached from the spec/site version (`docs/_config.yml`). The previous
        // `0.1.0-phaseN.X` tail confused the app version with the project phase milestone;
        // they evolve on different cadences. Pre-release suffix lives in the Play Console
        // track (alpha) and in the GitHub Release flag, not in versionName itself.
        // versionName is also surfaced in the app footer via BuildConfig.VERSION_NAME so
        // dogfood builds advertise their lineage to the user.
        versionCode = cliVersionCode ?: 72
        versionName = "0.52.1"

        // Manifest placeholder so a side-by-side install (dogfood/preview overlay)
        // can override the launcher label without touching tracked manifest/strings.
        // Defaults to the in-app string resource for production builds.
        manifestPlaceholders["appLabel"] = cliAppLabel ?: "@string/app_name"
    }

    // Canonical DEBUG signing (BUILD-03): a committed debug-only key so every debug build —
    // CLI, Docker (any UID), Android Studio, CI — signs identically. Without it AGP generates a
    // per-ANDROID_USER_HOME debug.keystore, so `adb install -r` fails with
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE across environments. DEBUG ONLY (insecure by design,
    // package suffixed `.debug`, never published to Play/F-Droid). Cf. docs/guides/contributing.md.
    signingConfigs {
        create("redfaceDebug") {
            storeFile = rootProject.file("config/signing/redface2-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    if (hasCiSigningConfig) {
        signingConfigs {
            create("upload") {
                storeFile = file(ciKeystorePath!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
        buildTypes.named("release") {
            signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildTypes.named("debug") {
        // Distinct launcher label for the side-by-side dogfood install pushed over adb
        // (create-topic debugging, #213/#214). The debug variant already gets
        // `applicationIdSuffix=".debug"` from the convention plugin, so it installs next to
        // a release build ; this just makes the icon legible on the device. Release keeps
        // `@string/app_name`.
        manifestPlaceholders["appLabel"] = "Redface 2 ADB"

        // Canonical debug signing — see the `redfaceDebug` signingConfig above (BUILD-03).
        signingConfig = signingConfigs.getByName("redfaceDebug")

        // Stamp the exact build into versionName so a sideloaded dogfood APK is identifiable:
        // every debug build otherwise shares the release versionName (e.g. 0.3.28), which made
        // it impossible to tell which fix a tester was actually running. Git short SHA + a
        // `.dirty` marker when built with uncommitted changes — traceable to the exact commit,
        // automatic (no manual counter), and surfaced via BuildConfig.VERSION_NAME + the footer.
        // Result e.g. `0.3.28+debug.f813453` (or `…+debug.f813453.dirty`). Release is untouched
        // (clean SemVer for the stores / F-Droid reproducibility).
        // `isIgnoreExitValue` so a failing git (e.g. CI's "dubious ownership", exit 128, where the
        // checkout is owned by another uid) degrades to "" instead of failing the whole build —
        // the ProcessOutputValueSource exception escapes a plain runCatching, so we must let the
        // exec itself tolerate non-zero. In CI we prefer GITHUB_SHA (git refuses there anyway).
        fun gitOutput(vararg args: String): String {
            val exec = providers.exec {
                commandLine(listOf("git", "-C", rootDir.absolutePath) + args)
                isIgnoreExitValue = true
            }
            return if (exec.result.get().exitValue == 0) exec.standardOutput.asText.get().trim() else ""
        }
        val envSha = providers.environmentVariable("GITHUB_SHA").orNull?.trim()?.take(7)?.takeIf { it.isNotBlank() }
        val gitSha = envSha ?: gitOutput("rev-parse", "--short", "HEAD")
        val gitDirty = gitOutput("status", "--porcelain").isNotBlank()
        // Optional per-build stamp, e.g. `-PbuildStamp=$(date -u +%y%m%d.%H%M%S)`. The git SHA
        // alone cannot tell two builds of the SAME (uncommitted/dirty) tree apart, so when we
        // sideload successive dogfood iterations without committing we pass a fresh stamp to make
        // each APK distinguishable on-device. It is OFF by default (tests / lint / CI / clean
        // dogfood) so those keep a warm Gradle configuration cache — only explicit dogfood APK
        // builds opt in. Passing a new value each invocation invalidates the config cache, which
        // is exactly what we want for a value that must be fresh per build.
        val buildStamp = (project.findProperty("buildStamp") as String?)?.takeIf { it.isNotBlank() }
        versionNameSuffix = buildString {
            append("+debug")
            if (gitSha.isNotEmpty()) append(".$gitSha")
            if (gitDirty) append(".dirty")
            if (buildStamp != null) append(".$buildStamp")
        }
    }

    // #233 — `channel` product-flavor dimension (prod / beta / dev). The applicationId suffix decides
    // WHERE the artifact goes; the launcher label is set per build (cliAppLabel ?: per-flavor default):
    //   prod (base applicationId fr.forumhfr.redface2) → the PLAY artifact (one Play listing, label
    //         set by -PappLabel per track: "Redface 2 β" on beta, "Redface 2 dev" on internal,
    //         "Redface 2" on production).
    //   beta (.beta) / dev (.dev) → the F-DROID artifacts (distinct applicationId = distinct, coexisting
    //         F-Droid apps "Redface 2 β" / "Redface 2 dev"; F-Droid indexes by package, has no tracks).
    // Play = ONE applicationId (1 listing, label varies by track); F-Droid = packages per channel.
    // The two stores are independent build artifacts, so a single channel can be base-appId on Play and
    // suffixed on F-Droid at once. Code is 100% shared; only applicationId suffix + launcher label differ.
    // NB: the `debug` buildType still forces label "Redface 2 ADB" + `.debug` suffix (it overrides the
    // flavor placeholder), so the local adb dogfood build remains `prodDebug` (fr.forumhfr.redface2.debug).
    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
            isDefault = true
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            // Default label for a manual `assembleBetaRelease` (Codex: keep a per-flavor default so a
            // build without -PappLabel still gets the right name); the CI `-PappLabel` overrides it.
            manifestPlaceholders["appLabel"] = cliAppLabel ?: "Redface 2 β"
        }
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = cliAppLabel ?: "Redface 2 dev"
            // Stamp the CI build number into the dev versionName (XaTriX, 2026-06-12) : the base
            // versionName stayed « 0.9.0 » across v114→v123, so F-Droid .dev (which displays
            // versions by versionName) listed ten identical-looking entries and the app footer
            // could not tell builds apart. Same idea as the debug buildType's `+debug.<sha>`
            // stamp ; release.md already documented this suffix — it now actually exists. The CD
            // injects -PversionCodeOverride at dispatch ; a local dev build stamps `local`.
            versionNameSuffix = "-dev." + (cliVersionCode?.toString() ?: "local")
        }
    }

    buildFeatures {
        // Expose BuildConfig.VERSION_NAME / VERSION_CODE to Kotlin code so the
        // placeholder screens can show them while :feature:settings (the future
        // home of an About screen) is empty.
        buildConfig = true
    }
}

// Play Console upload is delegated to the GitHub Action `r0adkll/upload-google-play` in
// .github/workflows/release.yml — it consumes the AAB artefact produced by `:app:bundleRelease`
// and pushes it via the Play Developer API directly. Keeping the upload step out of Gradle
// avoids a hard dependency on a Gradle plugin tracking AGP version churn (gradle-play-publisher
// 4.0.0 had documented incompat issues with AGP 9, and the project went into maintenance
// mode in April 2026 — see https://github.com/Triple-T/gradle-play-publisher/issues/1188).
//
// Release notes per locale live in app/src/main/play/whatsnew/whatsnew-<BCP47> (flat files,
// e.g. whatsnew-fr-FR, whatsnew-en-US — the layout that `r0adkll/upload-google-play` expects)
// and are passed to the upload action via its `whatsNewDirectory` input.

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:parser"))
    implementation(project(":core:ui"))

    implementation(project(":feature:flags"))
    implementation(project(":feature:forum"))
    implementation(project(":feature:topic"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:messages"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:profile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)

    // The Coil singleton ImageLoader is configured in `RedfaceApplication` via
    // `SingletonImageLoader.Factory` so the GIF decoder is registered once and shared by every
    // `AsyncImage` in the app (most HFR smileys and a fair share of `[img]` payloads are
    // animated GIFs). Both `coil-core` and `coil-gif` must therefore be on the `:app`
    // classpath. `coil-network-okhttp` is also pinned at the app layer to keep the HTTP
    // fetcher resolvable from the singleton config; `:core:ui` keeps its own coil-compose +
    // coil-network-okhttp for the `AsyncImage` call sites.
    implementation(libs.coil.core)
    implementation(libs.coil.gif)
    // #960 P4 — SVG [img] payloads: decoder registered in the same singleton config.
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.coil.core)
    testImplementation(libs.coil.svg)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.konsist)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}

// Guard B (#1045): DocsConsistencyTest reads docs/specs/reading-parity.md at runtime, so the page
// must be a declared input of the test tasks. Without it, both the local UP-TO-DATE check and the
// CI-restored build cache (setup-gradle) could serve a stale green result to a docs-only PR that
// breaks a symbol citation.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("docs/specs/reading-parity.md"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("readingParityMatrix")
}
