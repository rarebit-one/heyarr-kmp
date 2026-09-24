import com.android.build.gradle.LibraryExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Android target is registered only when an SDK is present — see the same note in
// :core/build.gradle.kts. On an SDK-less desktop box this module stays JVM-only and the
// desktop app builds unaffected; CI (setup-android) builds the android variant.
val hasAndroidSdk = extra["hasAndroidSdk"] as Boolean

if (hasAndroidSdk) apply(plugin = "com.android.library")

kotlin {
    // The shared Compose-Multiplatform design layer: the design tokens (`Tokens`) and the
    // media → accent theme table (`MediaThemes`). Compose-typed (Color/Dp), so it lives
    // apart from the pure `:core` — but still cross-platform, ready for the android/ios
    // targets that arrive when heyarr-mobile folds in.
    jvm()

    if (hasAndroidSdk) androidTarget()

    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api` so consumers (the desktop app, later the android app) see the
                // pure MediaType enum the theme table is keyed on without re-declaring it.
                api(project(":core"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

if (hasAndroidSdk) {
    extensions.configure<LibraryExtension>("android") {
        namespace = "one.rarebit.heyarr.ui"
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
