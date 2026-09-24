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
}
