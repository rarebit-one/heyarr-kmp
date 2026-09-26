# `:androidApp` build, test and toolchain

Moved verbatim from the former `androidApp/CLAUDE.md`; the router is `androidApp/AGENTS.md`.

## Build / test

```sh
./gradlew :androidApp:testDebugUnitTest   # unit tests — the acceptance bar, CI-run
./gradlew :androidApp:assembleDebug       # debug APK
```

Run from the repo root. `:androidApp` is only part of the build when an Android SDK is
detected (see `settings.gradle.kts`).

Nothing builds on the laptop: push the branch and let CI (`android.yml`) run
`testDebugUnitTest` + `assembleDebug` — that is the acceptance bar. (A native run needs a
JDK 21+, an Android SDK with API 35 and a GitHub token with `read:packages` for the
private `voidbind-client` artifact.)

Toolchain (matches `allthing-android` / `voidbind-kmp`, proven-green): **Gradle 8.9, AGP
8.7.3, Kotlin 2.3.20**, compileSdk 35, minSdk 33, JDK 21. Android bytecode remains Java 17.
`local.properties` (`sdk.dir=…`) is
git-ignored; CI provisions the SDK.
