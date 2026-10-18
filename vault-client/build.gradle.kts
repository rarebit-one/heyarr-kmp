import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

// Android target only when an SDK is present — the same rule, and the same reason, as :core
// (settings.gradle.kts detects the SDK once and hands every project `hasAndroidSdk`).
val hasAndroidSdk = extra["hasAndroidSdk"] as Boolean

if (hasAndroidSdk) apply(plugin = "com.android.library")

kotlin {
    // The TRUSTED space plumbing, published for other first-party clients: open an encrypted
    // space with this device's wrapped copy and the key history (ADR-0049, ADR-0103), the
    // snapshot envelope, personal-state ids and VaultSpaceClient. heyarr's media personal state
    // (playlists, starred, history, reading position) needs exactly this. Everything here runs on
    // the device that holds the key; the node and every other service only ever see ciphertext
    // (Invariant 6).
    //
    // The vault codec that used to sit here (the drive CRDT, VaultFrame, VaultRef/VaultObjects,
    // the VaultBlobStore seam and its java.net actual) moved, unchanged, to mnemosyne-kmp's
    // :vault-codec at 0.4.0: heyarr is the media service, Mnemosyne the personal one.
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    if (hasAndroidSdk) {
        androidTarget {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            // Publish only the release variant: what a consuming app resolves (the same
            // choice void-which-binds-kmp makes for its client).
            publishLibraryVariants("release")
        }
    }

    jvmToolchain(21)

    // `jvmAndAndroidMain`: where java.* actuals both JVM targets share go. Empty since the codec
    // moved (its NFC and HttpURLConnection actuals went with it); kept so the next one has a home.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // JsonScan / JsonWrite, the HttpTransport seam, Credential and Blake3 are
                // :core's, and appear in this module's API (VaultSpaceClient takes a transport
                // and a credential), so consumers get :core with it.
                api(project(":core"))
                // VoidbindEncryption: the space-key, change and frame AEADs (never re-derived here).
                implementation(libs.void.which.binds.client)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // voidbind's crypto needs a registered cryptography-kotlin provider at RUNTIME
                // (see :core's commonTest for the same note).
                implementation(libs.cryptography.provider.optimal)
            }
        }
        // The golden-vector tests read their vectors as classpath resources (java.*), so they live
        // in jvmTest — and are compiled into the Android unit tests too, so the frame codec, key
        // chain and drive CRDT are proven on the android variant consumers ship, not only the JVM.
        // (They stay under src/jvmTest so detekt and ktlint treat them as the tests they are.)
        if (hasAndroidSdk) {
            named("androidUnitTest") { kotlin.srcDir("src/jvmTest/kotlin") }
        }
    }
}

if (hasAndroidSdk) {
    extensions.configure<LibraryExtension>("android") {
        namespace = "one.rarebit.heyarr.vault"
        compileSdk = 35
        defaultConfig {
            minSdk = 33
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        // AGP builds the unit-test classpath's Java resources from its own source sets only, so
        // the golden vectors the shared jvmTest sources read are added here too.
        sourceSets.getByName("test").resources.srcDir("src/jvmTest/resources")
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
        events("failed")
    }
}
