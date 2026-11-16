import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

// Android target only when an SDK is present — the same rule, and the same reason, as :core
// (settings.gradle.kts detects the SDK once and hands every project `hasAndroidSdk`).
val hasAndroidSdk = extra["hasAndroidSdk"] as Boolean

if (hasAndroidSdk) apply(plugin = "com.android.kotlin.multiplatform.library")

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
        // The Android-KMP library plugin's target (AGP 9), configured by type because the plugin
        // is applied conditionally (see :core). Its single variant is what gets published, as
        // the old plugin's `release` was.
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
            namespace = "one.rarebit.heyarr.vault"
            compileSdk = 37
            minSdk = 33
            // Keep the published AAR's minCompileSdk at 1 (AGP 9 defaults it to compileSdk).
            aarMetadata { minCompileSdk = 1 }
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            // The golden vectors also run as Android host (unit) tests, which this plugin
            // leaves off unless asked for.
            withHostTest {}
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
                // withAndroidTarget() matches only the legacy com.android.library target; the
                // Android-KMP plugin's target is picked out by its platform type instead.
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
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
        // in jvmTest — and are compiled, with those resources, into the Android host tests too, so the frame codec, key
        // chain and drive CRDT are proven on the android variant consumers ship, not only the JVM.
        // (They stay under src/jvmTest so detekt and ktlint treat them as the tests they are.)
        if (hasAndroidSdk) {
            named("androidHostTest") {
                kotlin.srcDir("src/jvmTest/kotlin")
                resources.srcDir("src/jvmTest/resources")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
        events("failed")
    }
}
