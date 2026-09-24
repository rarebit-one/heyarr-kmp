import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ── Release version + signing ────────────────────────────────────────────────
// `versionName` comes from the git tag: CI passes `-PreleaseVersionName=$GITHUB_REF_NAME`
// (the tag, e.g. `v0.2.1`); a plain local build falls back to the constant below.
// `versionCode` is DERIVED from it (major*10000 + minor*100 + patch) so it is
// monotonic with the tag and never has to be hand-bumped.
val releaseVersionName: String =
    providers.gradleProperty("releaseVersionName").orNull
        ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
        ?: "0.5.0"

fun versionCodeOf(name: String): Int {
    val parts = name.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

// Signing material is read from the environment (CI: repo secrets) or gradle
// properties (locally: ~/.gradle/gradle.properties). NOTHING is ever committed —
// `*.jks` is git-ignored and the keystore is materialised into build/ from base64.
// The keys live at ~/.config/rarebit-android-signing/ and in 1Password (Sysadmins).
fun releaseSecret(env: String, property: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty(property).orNull?.takeIf { it.isNotBlank() }

val releaseKeystoreBase64 = releaseSecret("RELEASE_KEYSTORE_BASE64", "release.keystoreBase64")
val releaseKeystorePassword = releaseSecret("RELEASE_KEYSTORE_PASSWORD", "release.keystorePassword")
val releaseKeyAlias = releaseSecret("RELEASE_KEY_ALIAS", "release.keyAlias")
val releaseKeyPassword = releaseSecret("RELEASE_KEY_PASSWORD", "release.keyPassword")

// Present only when the base64 keystore was supplied; otherwise the release build
// type stays unsigned (a local `assembleRelease` still works, it just isn't signed).
val releaseKeystore: File? = releaseKeystoreBase64?.let { encoded ->
    layout.buildDirectory.file("release-signing/release.jks").get().asFile.apply {
        parentFile.mkdirs()
        writeBytes(Base64.getMimeDecoder().decode(encoded))
    }
}

android {
    namespace = "one.rarebit.heyarr.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "one.rarebit.heyarr.mobile"
        // minSdk 33: the published `voidbind-client` Android variant is minSdk 33
        // (StrongBox + a platform Ed25519 provider + the modern BiometricPrompt API).
        minSdk = 33
        targetSdk = 35
        versionCode = versionCodeOf(releaseVersionName)
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the app points by default: the live Bartley Ridge heyarr node, now
        // served over internal TLS (valid Let's Encrypt cert at
        // https://heyarr.br.thesim.family:7777, reachable on the LAN and via the
        // Tailscale subnet route when out). Override at build time with
        // `-PheyarrBaseUrl=…` (or in gradle.properties); override at runtime from the
        // in-app Settings screen (persisted in SharedPreferences) — e.g. back to the
        // plain-http node IP as a fallback while TLS beds in.
        val heyarrBaseUrl = (project.findProperty("heyarrBaseUrl") as String?)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://heyarr.br.thesim.family:7777"
        buildConfigField("String", "HEYARR_BASE_URL", "\"$heyarrBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            // Left unconfigured (and unreferenced) when no keystore was supplied.
            releaseKeystore?.let { keystore ->
                storeFile = keystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // minify stays OFF until proguard rules exist for the reflective bits.
            isMinifyEnabled = false
            signingConfig = releaseKeystore?.let { signingConfigs.getByName("release") }
        }
    }

    lint {
        // AGP 8.7.3's bundled lint uses a Kotlin 2.0-era UAST analyzer that throws
        // IncompatibleClassChangeError on our Kotlin 2.3.20 module metadata, crashing
        // lintVitalRelease during `assembleRelease` (debug is unaffected — it skips
        // lint-vital). Don't run lint as part of the release build; `./gradlew lint`
        // is still available on demand. Revisit when AGP ships a Kotlin 2.3-compatible lint.
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ── Shared heyarr-kmp modules ────────────────────────────────────────────────
    // The pure client layer (:core) and shared Compose tokens (:ui). This app still
    // carries its own copies of the not-yet-converged code (HeyarrApi, McpModels,
    // Credential, transports — see Gate A/B); only the drift-free leaves are shared.
    implementation(project(":core"))
    implementation(project(":ui"))

    // ── Compose UI ───────────────────────────────────────────────────────────────
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // The design system's glyphs (media kinds, transport, nav) — the extended set; the
    // core set lacks Tv / MenuBook / Podcasts / Cast / Replay10 and friends.
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // ── Navigation ───────────────────────────────────────────────────────────────
    // Typed routes (nav/Routes.kt). kotlinx-serialization is here for ROUTE ARGUMENTS
    // ONLY — wire JSON stays hand-read on net/JsonScan, the repo's convention.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.core)
    // Test-only: the route round-trip test encodes through JSON; the app never does.
    testImplementation(libs.kotlinx.serialization.json)

    // HTTP for the login seam, library browse, blob-stream and personal-state fetches.
    implementation(libs.okhttp)
    // Posters. Rides the SAME OkHttp client (AppGraph) so a poster fetch carries the
    // credential through net/AuthInterceptor without ever seeing it.
    implementation(libs.coil.compose)

    // QR encoding for the `voidbind:login?…` tuple (pure Java — the BitMatrix half is
    // JVM-unit-tested; only the Bitmap conversion touches Android).
    implementation(libs.zxing.core)

    // ── Voidbind shared client (the identity seam) ──────────────────────────────
    // `WebLoginClient`/`LoginQr` (QR web-login, wire-identical to voidbind-go),
    // `DeviceIdentity` + the hardware-sealed `DeviceKeyStore` (ADR-0001), `Cert`,
    // and the `DevicePairing` relay flow that enrols this device. Resolved from
    // GitHub Packages (settings.gradle.kts).
    implementation(libs.voidbind.client)
    // The device key's hardware wrapping key is user-auth-gated: BiometricPrompt
    // needs a FragmentActivity, and a modern fragment so it still extends the
    // ComponentActivity that activity-compose's setContent requires.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)

    // ── Invite QR scanning (Enrol → scan the Mac's `voidbind:pair?…` QR) ───────
    // CameraX preview + analysis with ML Kit barcode decoding — the same stack and
    // versions as the Voidbind authenticator (voidbind-kmp androidApp), so both apps
    // scan alike on the same phone. Validation of what was scanned is the library's
    // `Invite.decode` (device/PairInvite), never a re-derived parser.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // ── Media3 / ExoPlayer (M10 playback) ───────────────────────────────────────
    // The player itself, the PlayerView (transport controls) and the OkHttp-backed
    // HTTP data source. ExoPlayer's HTTP data source issues Range requests and
    // handles 206 partial content natively; we point it at the authenticated blob
    // endpoint and inject the Authorization header as a default request property.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    // The audio queue lives in a MediaSessionService (notification controls, survives the
    // Activity); the app talks to it through a MediaController behind the AudioPlayer seam.
    implementation(libs.androidx.media3.session)

    // ── The reader (Readium Kotlin toolkit) ─────────────────────────────────────
    // EPUB, PDF (pdfium) and comic archives, fetched over the authenticated blob route
    // through a DefaultHttpClient callback (reader/ReaderHttp). Readium needs core
    // library desugaring (compileOptions below).
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.adapter.pdfium)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // ── Unit tests (pure JVM — no Android runtime) ──────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ── On-device smoke test (.github/workflows/instrumented.yml) ───────────────
    // The JVM unit tests cannot see Android-only behaviour — the Series regex that
    // compiled on the desktop JVM and threw on the phone's ICU engine took every
    // Detail screen down (#49). One Compose test renders real screens on an emulator.
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
