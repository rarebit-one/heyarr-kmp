import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    // Exports the dependency licences; thirdPartyNotices renders them for the app image.
    alias(libs.plugins.aboutlibraries)
}

// The deployment this build points at, from Gradle properties (locally
// ~/.gradle/gradle.properties), written as a classpath resource SiteDefaults reads. Never
// committed: this repository is public and a deployment's address is its own. Without them
// the app starts at a reserved placeholder and finds its node by mDNS, or from Settings.
//  - heyarrBaseUrl: the default node URL.  - heyarrDnsHost: the DNS name discovery tries.
// The same property names configure :androidApp.
val heyarrSiteDefaults = tasks.register("heyarrSiteDefaults") {
    val values = listOf("heyarrBaseUrl", "heyarrDnsHost").associateWith { name ->
        providers.gradleProperty(name).map { it.trim() }.orElse("")
    }
    values.forEach { (name, value) -> inputs.property(name, value) }
    val out = layout.buildDirectory.dir("generated/heyarrSite/resources")
    outputs.dir(out)
    doLast {
        val props = Properties()
        values.forEach { (name, value) -> value.get().takeIf { it.isNotEmpty() }?.let { props.setProperty(name, it) } }
        val file = out.get().file("heyarr-site.properties").asFile
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, "Generated from Gradle properties; see composeApp/build.gradle.kts") }
    }
}

kotlin {
    // KMP with a single JVM/desktop target for now. This is a genuine Kotlin
    // Multiplatform project (kotlin("multiplatform") + a jvm() target and a
    // `jvmMain` source set) rather than a plain kotlin("jvm") module, so that when the
    // shared client is extracted later, adding `androidTarget()` / `iosX64()` … and a
    // `commonMain` is a source-set move, not a plugin swap. A generic JVM/desktop
    // target covers Linux x64 AND aarch64 (Asahi/Omarchy) — the JVM is the portability layer.
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    jvmToolchain(21)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // Shared modules: pure domain (`:core`) + Compose design layer (`:ui`).
                // JdkHttpTransport / HeyarrApi (this module) implement/orchestrate over them.
                implementation(project(":core"))
                implementation(project(":ui"))
                // The trusted vault client (space open, drive CRDT, frame codec, ref-addressed
                // objects) — shared with the android app and published for other first-party
                // clients. The sync engine, custody and daemon on top of it stay desktop-only.
                implementation(project(":vault-client"))
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
                implementation(libs.void.which.binds.client)

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
        jvmMain {
            // The deployment this build points at (see heyarrSiteDefaults above).
            resources.srcDir(heyarrSiteDefaults)
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
                menuGroup = "AudioVideo"
                // The Archive H on its plate; jpackage installs it as lib/heyarr-desktop.png,
                // which scripts/install-desktop.sh's .desktop entry points at. The same
                // resource is the window/taskbar icon (Main.kt).
                iconFile.set(project.file("src/jvmMain/resources/heyarr-icon.png"))
            }
        }
    }
}

// jpackage records what the bundled runtime links against, not what the app loads through
// JNA at run time, so the .rpm and .deb would install without the one library playback
// needs. Declare it. libsecret stays optional on purpose: with no Secret Service the device
// keyring falls back to its own sealed key file (DesktopSecretStore).
//  - Fedora: mpv-libs is in Fedora's own repositories (libmpv.so.2; JNA finds the versioned
//    name without the -devel package). RPM Fusion is only needed for codecs such as HEVC.
//  - Debian 12+ / Ubuntu 24.04+: libmpv2.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    when (targetFormat) {
        TargetFormat.Rpm -> freeArgs.addAll("--linux-package-deps", "mpv-libs")
        TargetFormat.Deb -> freeArgs.addAll("--linux-package-deps", "libmpv2")
        else -> Unit
    }
}

// Third-party notices for the desktop app image and its .deb/.rpm. AboutLibraries exports
// the runtime classpath's libraries and licences (with full texts for the common ones) as
// JSON; thirdPartyNotices renders that as plain text, which Compose's appResourcesRootDir
// copies into the app image (lib/app/resources/THIRD_PARTY_LICENSES.txt).
aboutLibraries {
    collect {
        // Only what ships: the jvm target's classpaths (the plugin names a variant by
        // dropping the RuntimeClasspath/CompileClasspath suffix), not jvmTest's.
        filterVariants.add("jvm")
        // Licence texts the POMs only link (Bouncy Castle's MIT-style licence).
        configPath = file("aboutlibraries")
    }
    export {
        outputFile = layout.buildDirectory.file("generated/aboutLibraries/aboutlibraries.json").get().asFile
        prettyPrint = true
    }
}

val noticesRoot = layout.buildDirectory.dir("generated/notices")

val thirdPartyNotices by tasks.registering {
    group = "distribution"
    description = "Renders the AboutLibraries export as THIRD_PARTY_LICENSES.txt for the app image."
    dependsOn("exportLibraryDefinitions")
    val json = layout.buildDirectory.file("generated/aboutLibraries/aboutlibraries.json")
    val out = noticesRoot.map { it.file("common/THIRD_PARTY_LICENSES.txt") }
    inputs.file(json)
    outputs.file(out)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(json.get().asFile) as Map<String, Any?>
        val licences = (root["licenses"] as? Map<String, Map<String, Any?>>).orEmpty()
        val libraries = (root["libraries"] as? List<Map<String, Any?>>).orEmpty()
            .sortedBy { (it["name"] as? String ?: it["uniqueId"] as String).lowercase() }
        val used = linkedSetOf<String>()
        val text = buildString {
            appendLine("Third-party software in heyarr for desktop")
            appendLine()
            appendLine("This app is built with the libraries below. Each lists its version and licence;")
            appendLine("the licence texts follow the list.")
            appendLine()
            for (lib in libraries) {
                val ids = (lib["licenses"] as? List<String>).orEmpty()
                used += ids
                val names = ids.joinToString { licences[it]?.get("name") as? String ?: it }
                val name = lib["name"] ?: lib["uniqueId"]
                appendLine("$name ${lib["artifactVersion"] ?: ""} (${lib["uniqueId"]}): $names")
            }
            for (id in used) {
                val def = licences[id] ?: continue
                appendLine()
                appendLine("=".repeat(78))
                appendLine("${def["name"] ?: id}${def["url"]?.let { " <$it>" } ?: ""}")
                appendLine("=".repeat(78))
                appendLine((def["content"] as? String)?.trim() ?: "See ${def["url"] ?: "the licence URL above"}.")
            }
        }
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
    }
}

compose.desktop.application.nativeDistributions.appResourcesRootDir.set(noticesRoot)
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(thirdPartyNotices) }

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
    // Fictional fixture posters (scripts/art); absent → procedural placeholder art.
    systemProperty("heyarr.previewArt", layout.projectDirectory.dir("preview-art").asFile.absolutePath)
    args(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}

// The same off-screen renderer pointed at a REAL node (preview/LiveScreenshots.kt): a
// stock-take's "what does the app show with live data" artefact. Reads HEYARR_BASE_URL /
// HEYARR_TOKEN / HEYARR_DETAIL from the environment; writes build/live-screenshots/*.png.
tasks.register<JavaExec>("liveScreenshots") {
    group = "verification"
    description = "Render each screen to build/live-screenshots/*.png against the node in HEYARR_BASE_URL."
    dependsOn("jvmMainClasses")
    classpath = files(
        layout.buildDirectory.dir("classes/kotlin/jvm/main"),
        layout.buildDirectory.dir("processedResources/jvm/main"),
        configurations.getByName("jvmRuntimeClasspath"),
    )
    mainClass.set("one.rarebit.heyarr.desktop.preview.LiveScreenshotsKt")
    systemProperty("java.awt.headless", "true")
    args(layout.buildDirectory.dir("live-screenshots").get().asFile.absolutePath)
}
