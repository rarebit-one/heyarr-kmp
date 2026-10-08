// Root build. Plugins are declared here (apply false) and applied in the subprojects; every
// version lives in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    // The Compose *compiler* plugin ships WITH Kotlin (versioned with it), so it stays
    // compatible with the Kotlin compiler automatically.
    alias(libs.plugins.kotlin.compose) apply false
    // The Compose Multiplatform Gradle plugin: wires the Compose runtime deps and the
    // `compose.desktop.application { }` / jpackage packaging DSL.
    alias(libs.plugins.compose.multiplatform) apply false
    // Android Gradle Plugin — the same AGP as :androidApp so the shared
    // `:core` / `:ui` modules build an android variant identical to what the phone app
    // will consume when it folds in. Declared here (apply false) and applied CONDITIONALLY
    // in :core / :ui only when an Android SDK is present (see each module's build script):
    // a plain `com.android.library` apply would make AGP demand an SDK at CONFIGURATION
    // time, breaking every Gradle invocation on an SDK-less desktop dev box. CI installs
    // the SDK (setup-android) so it builds the android variants; SDK-less desktop builds
    // simply skip the android target and are unaffected.
    alias(libs.plugins.android.library) apply false

    // ── Android app (heyarr-mobile, folded in as :androidApp) ────────────────────
    // Same AGP/Kotlin as above. The app module applies these; the multiplatform
    // plugin (for :core/:ui/:composeApp) and the android plugins coexist fine since
    // they apply to different modules. serialization is for nav route args only
    // (nav/Routes.kt) — wire JSON stays hand-read on net/JsonScan, per the org rule.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false

    // Lint, applied to every project below.
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// ── Lint: ktlint + detekt for every module ─────────────────────────────────────
// Findings that predate the linters are frozen in each module's
// config/ktlint/baseline.xml and config/detekt/baseline.xml, so only NEW violations
// fail. Fix code rather than growing them; regenerate only to deliberately accept debt
// (`./gradlew ktlintGenerateBaseline detektBaseline`). Style lives in .editorconfig.
// Both plugins hook into `check`, so `:module:build` lints that module too; CI also
// runs them by name (desktop.yml: :core :ui :composeApp; android.yml: :androidApp).
allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint)
        baseline.set(file("config/ktlint/baseline.xml"))
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        // detekt's default source set misses the KMP layout (src/commonMain, src/jvmMain, …)
        // and :androidApp's Kotlin under src/*/java, so scan all of src/.
        source.setFrom("src")
        baseline = file("config/detekt/baseline.xml")
    }

    // detekt 1.23.8 embeds the Kotlin 2.0.21 compiler and refuses to run against a newer
    // one; keep its (isolated) tool classpath on the version it was built with.
    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(rootProject.libs.versions.detektKotlin.get())
            }
        }
    }
}

// ── Published client libraries: one.rarebit.heyarr:{core,vault-client} ───────────
// :vault-client is the trusted vault client other first-party apps consume (the Jumpdrive KMP
// client renders private blocks with it); :core publishes alongside because vault-client's API
// exposes it (HttpTransport, Credential, JsonScan). Both go to this repo's GitHub Packages
// registry at ONE version, cut by a `lib-v<version>` tag (.github/workflows/publish.yml checks the
// tag against the line below; a plain `v*` tag is an android app release, android-release.yml).
// Bump on any API- or wire-affecting change to either module.
// 0.1.0: first cut (heyarr-kmp M1): SpaceOpen/SpaceKeyring (ADR-0103), the drive CRDT in
// commonMain, VaultFrame, VaultSpaceClient, and ref-addressed objects (VaultRef/VaultObjects,
// ADR-0104).
// 0.2.0 (breaking): VaultSpace.pushChange takes the sealing key epoch (heyarr-core#712), which
// changes the interface's JVM signature — a 0.1.x consumer must recompile and pass it.
// VaultObjects.put and the sync engine push conditionally and re-seal once on
// change_key_epoch_mismatch. Not kept as an overload: a delegating 3-argument default would let
// an implementer drop the epoch silently, i.e. fail open.
// 0.3.0: the void-which-binds-go device store reader (`gostore.GoDeviceStore`, with `recipient()`
// for VaultObjects) and the CLI-minted Device credential (`gostore.VoidbindCliCredential`) move
// from the desktop app into jvmAndAndroidMain (JVM-API code; the tests also run on Android), so
// another desktop client can open the same vault objects.
val publishedLibraryVersion = "0.3.0"

listOf(":core", ":vault-client").forEach { path ->
    project(path) {
        group = "one.rarebit.heyarr"
        version = publishedLibraryVersion
        plugins.withId("maven-publish") {
            configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    pom {
                        name.set(project.name)
                        description.set(
                            if (project.name == "vault-client") {
                                "heyarr's trusted vault client: open an encrypted space, fold its drive, and read or " +
                                    "write sealed objects by vault ref, decrypting only on the device."
                            } else {
                                "heyarr's shared client layer (wire JSON, HTTP seam, credentials, BLAKE3)."
                            },
                        )
                        url.set("https://github.com/rarebit-one/heyarr-kmp")
                    }
                }
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/rarebit-one/heyarr-kmp")
                        credentials {
                            // CI: the publish workflow's GITHUB_TOKEN (packages: write). Locally:
                            // gpr.user / gpr.token, as for resolving void-which-binds-client.
                            username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                            password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.token") as String?
                        }
                    }
                }
            }
        }
    }
}
