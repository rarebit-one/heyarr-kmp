import com.android.build.gradle.LibraryExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Register the android target only when an SDK is actually available (CI, or a dev box
// with one). Applying `com.android.library` unconditionally makes AGP demand an SDK at
// CONFIGURATION time, which would break plain JVM/desktop builds on an SDK-less machine.
// Detection: ANDROID_HOME / ANDROID_SDK_ROOT env, or a `sdk.dir` line in local.properties.
val hasAndroidSdk =
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    rootProject.file("local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true

if (hasAndroidSdk) apply(plugin = "com.android.library")

kotlin {
    // The shared, PURE domain module: the hand-rolled JSON codec, the HttpTransport seam,
    // the heyarr MCP/REST DTOs + parsers, library-status / search-grouping derivation and
    // the pure MediaType enum. NO Compose, NO platform SDK — so its tests run as fast
    // `commonTest` without a UI or Android harness.
    //
    // Targets: JVM (desktop) always; Android when an SDK is present (see `hasAndroidSdk`).
    // The code all lives in commonMain, so adding a target is a source-set add, not a
    // rewrite. `iosX64()` … arrive the same way later.
    jvm()

    if (hasAndroidSdk) androidTarget()

    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            // kotlinx-coroutines-core comes back when HeyarrApi / the async layer moves in
            // from :composeApp (Gate A).
            dependencies {
                // Gate B: the shared device-auth brain. voidbind-client's device-credential
                // domain (DeviceCredential, DeviceAuthPolicy, DevicePairing, WebLoginClient …)
                // lives in voidbind's OWN commonMain, so :core consumes it in common code;
                // the platform `DeviceKeyStore` actuals ship in voidbind's jvm/android
                // variants and resolve per target. This puts voidbind on :core's classpath,
                // so :composeApp (desktop) now pulls it transitively too → desktop CI needs a
                // read:packages token (see .github/workflows/desktop.yml).
                implementation("one.rarebit.voidbind:voidbind-client:0.7.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // voidbind's crypto (used by the vault codec tests) needs a registered
                // cryptography-kotlin provider at RUNTIME. voidbind-client declares the
                // provider as `implementation`, and :composeApp adds it for the app — but
                // :core's own tests must bring it themselves or every encrypt/decrypt
                // throws at runtime with no compile error.
                implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
            }
        }
    }
}

if (hasAndroidSdk) {
    extensions.configure<LibraryExtension>("android") {
        namespace = "one.rarebit.heyarr.core"
        compileSdk = 35
        defaultConfig {
            minSdk = 33
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

// Print full exception messages (and test stdout) to the build log so a JVM unit-test
// failure is diagnosable from CI without the (un-uploaded) HTML report.
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
        events("failed")
    }
}
