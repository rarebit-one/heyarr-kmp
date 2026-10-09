rootProject.name = "heyarr-kmp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        // Compose Multiplatform's dev/EAP artifacts (harmless for stable, kept for parity
        // with the wider Compose MP ecosystem). Stable releases resolve from the portal.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")

        // ── `void-which-binds-client` from void-which-binds-kmp (GitHub Packages, private) ──
        // The shared Voidbind identity/net/flow brain (WebLoginClient, LoginQr,
        // DeviceIdentity, DevicePairing …). Consumed by :androidApp (heyarr-mobile).
        // GitHub Packages requires a token with `read:packages` even for a same-org read:
        //   - CI: `GITHUB_ACTOR` / `GITHUB_TOKEN` (the android workflow's own token, with
        //     `packages: read` — see .github/workflows/android.yml).
        //   - Locally: `gpr.user` / `gpr.token` in ~/.gradle/gradle.properties, or the env
        //     vars, e.g. `GITHUB_ACTOR=<login> GITHUB_TOKEN=$(gh auth token) ./gradlew …`.
        // Scoped to the void-which-binds group so no other dependency ever probes this repo. EVERY
        // module needs it: :core consumes void-which-binds-client in commonMain (so :ui,
        // :composeApp and :androidApp all get it transitively), and :composeApp and :androidApp also
        // depend on it directly for device enrolment and pairing. Its version is pinned
        // once, in gradle/libs.versions.toml.
        // The owner-wide `rarebit-one/*` path resolves packages from any repo in the org,
        // so a rename of the publishing repo cannot break resolution (the per-repo
        // Packages URL is not redirected after a GitHub repo rename).
        maven {
            name = "GitHubPackagesVoidWhichBindsKmp"
            url = uri("https://maven.pkg.github.com/rarebit-one/*")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: providers.gradleProperty("gpr.key").orNull // allthing-android's spelling
                    ?: System.getenv("GITHUB_TOKEN")
            }
            content { includeGroup("one.rarebit.voidwhichbinds") }
        }
    }
}

include(":core")
include(":ui")
// The trusted vault client, published to GitHub Packages as one.rarebit.heyarr:vault-client
// (with :core, which it exposes) for other first-party clients. See vault-client/build.gradle.kts.
include(":vault-client")
include(":composeApp")

// :androidApp (heyarr-mobile) applies `com.android.application`, which — unlike the
// SDK-gated library target on :core/:ui — demands an Android SDK at CONFIGURATION time.
// Including it unconditionally would break every Gradle invocation on an SDK-less desktop
// dev box (the Asahi laptop, the screenshots flow). So it joins the build ONLY when an SDK
// is present: CI (setup-android) and any dev machine with the SDK get the full project;
// desktop-only boxes silently skip the android app, exactly as they skip the android
// variants of :core/:ui. This is the ONE place the SDK is detected: every project reads the
// result as the `hasAndroidSdk` extra property (see :core/:ui), so the guards cannot drift.
val hasAndroidSdk =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true
if (hasAndroidSdk) include(":androidApp")
gradle.beforeProject { extensions.extraProperties["hasAndroidSdk"] = hasAndroidSdk }
