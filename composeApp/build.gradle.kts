import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    // KMP with a single JVM/desktop target for now. This is a genuine Kotlin
    // Multiplatform project (kotlin("multiplatform") + a jvm() target and a
    // `jvmMain` source set) rather than a plain kotlin("jvm") module, so that when the
    // shared client is extracted later, adding `androidTarget()` / `iosX64()` … and a
    // `commonMain` is a source-set move, not a plugin swap. A generic JVM/desktop
    // target covers Linux x64 AND aarch64 (Asahi/Omarchy) — the JVM is the portability layer.
    jvm()

    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // Shared modules: pure domain (`:core`) + Compose design layer (`:ui`).
                // JdkHttpTransport / HeyarrApi (this module) implement/orchestrate over them.
                implementation(project(":core"))
                implementation(project(":ui"))
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                // Material's extended icon set is the Compose analog of lucide-react: one
                // dependency, vector icons, no font or CDN.
                implementation(compose.materialIconsExtended)
                // JNA only for Native.getComponentID: the X11 window id of the AWT canvas
                // the embedded mpv renders into (--wid). No other native call.
                implementation(libs.jna)
                implementation(libs.kotlinx.coroutines.swing)

                // mDNS / DNS-SD browser for auto-discovery of the `_heyarr._tcp` node
                // (heyarr-core Phase 2). jmdns is a small pure-JVM Bonjour implementation
                // on Maven Central (not GitHub Packages) with no native bits — the desktop
                // MdnsResolver actual wraps it; the fallback chain itself lives in pure :core.
                implementation(libs.jmdns)

                // ── Voidbind device login (device enrolment + QR/pairing) ─────────────
                // The shared device-auth brain: DeviceIdentity, DevicePairing (relay
                // responder), the Invite/SAS handshake, PossessionProof + DeviceCredential
                // and voidbind's own JdkHttpTransport for the relay. Already on :core's
                // classpath (Credential.Device delegates to voidbind DeviceCredential);
                // :composeApp now depends on it DIRECTLY so the desktop device/ package can
                // drive the pairing flow. Resolves from the org's GitHub Packages repo
                // (settings.gradle.kts) — CI passes GITHUB_ACTOR/GITHUB_TOKEN (desktop.yml),
                // locally gpr.user/gpr.token in ~/.gradle/gradle.properties.
                implementation(libs.voidbind.client)

                // The plain-JVM DeviceKeyStore actual voidbind ships is a NON-persisted,
                // process-lifetime software key (regenerated each launch) and never exposes
                // its seed, so it cannot back an enrolment that survives a restart. The
                // desktop keyring therefore holds its OWN Ed25519 signing seed, sealed at
                // rest, and signs through the SAME vetted provider voidbind uses internally
                // (cryptography-kotlin 0.6.0) so seed / public-key / signature bytes are
                // wire-identical. voidbind depends on this only as `implementation`, so it
                // must be declared here to reach :composeApp's COMPILE classpath.
                implementation(libs.cryptography.core)
                implementation(libs.cryptography.provider.optimal)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "one.rarebit.heyarr.desktop.MainKt"

        nativeDistributions {
            // The bundled runtime is a jlink image: name every module the app reaches
            // beyond the defaults. java.net.http backs JdkHttpTransport / ArtworkLoader;
            // jdk.crypto.ec carries the TLS elliptic-curve suites heyarr's cert needs.
            modules("java.net.http", "jdk.crypto.ec", "jdk.unsupported")
            // Linux packaging via jpackage. Declared (not run) here — `build` does not
            // package; `packageDeb` / `packageReleaseDeb` (and Rpm) would. AppImage is
            // NOT a jpackage format: it is produced out-of-band by wrapping the
            // `createDistributable` app-image (task `:composeApp:createDistributable`),
            // which is why only Deb/Rpm are listed here.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "heyarr-desktop"
            packageVersion = "1.0.0"
            description = "heyarr desktop client (Linux)"
            vendor = "rarebit.one"

            linux {
                // Populated as the app matures; menu group + a real icon come later.
                menuGroup = "AudioVideo"
                // iconFile.set(project.file("src/jvmMain/resources/icon.png"))
            }
        }
    }
}

// Off-screen screenshots of every screen with fixture data — the "show the running app"
// artefact for a headless container. Renders through Compose's ImageComposeScene (no
// display needed) into build/screenshots/*.png.
tasks.register<JavaExec>("screenshots") {
    group = "verification"
    description = "Render each screen to build/screenshots/*.png without a display."
    dependsOn("jvmMainClasses")
    classpath = files(
        layout.buildDirectory.dir("classes/kotlin/jvm/main"),
        layout.buildDirectory.dir("processedResources/jvm/main"),
        configurations.getByName("jvmRuntimeClasspath"),
    )
    mainClass.set("one.rarebit.heyarr.desktop.preview.ScreenshotsKt")
    systemProperty("java.awt.headless", "true")
    args(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
