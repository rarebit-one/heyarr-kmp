import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Published with :vault-client, whose API exposes it (root build.gradle.kts).
    `maven-publish`
}

// Register the android target only when an SDK is actually available (CI, or a dev box
// with one). Applying the Android-KMP library plugin unconditionally makes AGP demand an SDK
// at CONFIGURATION time, which would break plain JVM/desktop builds on an SDK-less machine.
// Detection (ANDROID_HOME / ANDROID_SDK_ROOT env, or a `sdk.dir` line in local.properties)
// happens once, in settings.gradle.kts, which hands every project the result.
val hasAndroidSdk = extra["hasAndroidSdk"] as Boolean

if (hasAndroidSdk) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    // The shared, PURE domain module: the hand-rolled JSON codec, the HttpTransport seam,
    // the heyarr MCP/REST DTOs + parsers, library-status / search-grouping derivation and
    // the pure MediaType enum. NO Compose, NO platform SDK — so its tests run as fast
    // `commonTest` without a UI or Android harness.
    //
    // Targets: JVM (desktop) always; Android when an SDK is present (see `hasAndroidSdk`).
    // The code all lives in commonMain, so adding a target is a source-set add, not a
    // rewrite. `iosX64()` … arrive the same way later.
    jvm {
        // Compile desktop consumers for JDK 21 while using the same toolchain as CI.
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    if (hasAndroidSdk) {
        // The Android-KMP library plugin's target (AGP 9). Configured by type: the plugin is
        // applied conditionally above, so the `android { }` accessor is not generated. It has a
        // single variant, which is what gets published (the old plugin's `release`).
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
            namespace = "one.rarebit.heyarr.core"
            compileSdk = 37
            minSdk = 33
            // AGP 9 would otherwise stamp the published AAR with minCompileSdk = compileSdk,
            // forcing every consumer onto compileSdk 37. Keep the floor the AAR always had.
            aarMetadata { minCompileSdk = 1 }
            // Android artifacts retain the bytecode level supported by the app's minSdk (this
            // sets Kotlin's and Java's target for the android compilations).
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvmToolchain(21)

    // `jvmAndAndroidMain`: code for both JVM-based targets that needs java.* (java.io.File,
    // MessageDigest, URLEncoder) — today the public-metadata cache (`state/ExternalMetadata`),
    // which both apps used to carry a copy of. Everything else stays in commonMain.
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
                // The shared SearchController (state/) exposes its state as a StateFlow and
                // takes the app's CoroutineScope, so coroutines are part of :core's API.
                api(libs.kotlinx.coroutines.core)
                // Gate B: the shared device-auth brain. voidbind-client's device-credential
                // domain (DeviceCredential, DeviceAuthPolicy, DevicePairing, WebLoginClient …)
                // lives in voidbind's OWN commonMain, so :core consumes it in common code;
                // the platform `DeviceKeyStore` actuals ship in voidbind's jvm/android
                // variants and resolve per target. This puts voidbind on :core's classpath,
                // so :composeApp (desktop) now pulls it transitively too → desktop CI needs a
                // read:packages token (see .github/workflows/desktop.yml).
                implementation(libs.void.which.binds.client)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // voidbind's crypto (used by the vault codec tests) needs a registered
                // cryptography-kotlin provider at RUNTIME. voidbind-client declares the
                // provider as `implementation`, and :composeApp adds it for the app — but
                // :core's own tests must bring it themselves or every encrypt/decrypt
                // throws at runtime with no compile error.
                implementation(libs.cryptography.provider.optimal)
            }
        }
    }
}

// The OpenAPI contract test (src/jvmTest/…/contract) reads EVERY module's main Kotlin sources
// as text, to extract the JSON keys each hand-written parser reads. Declare them as inputs, or
// the build cache (org.gradle.caching=true) would replay a pass for a parser change made in
// another module.
tasks.named<Test>("jvmTest") {
    inputs.files(
        listOf("core", "ui", "vault-client", "composeApp", "androidApp").map { module ->
            rootProject.fileTree(rootDir.resolve("$module/src")) {
                include("**/*.kt")
                exclude("*[Tt]est*/**")
            }
        },
    ).withPropertyName("contractParserSources").withPathSensitivity(PathSensitivity.RELATIVE)
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
