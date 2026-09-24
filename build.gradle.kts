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
