# CLAUDE.md — heyarr-kmp

Guidance for Claude Code working in **heyarr-kmp**, the Kotlin Multiplatform monorepo for
heyarr's first-party clients (part of the `rarebit-one` org). Read the workspace
`~/Workspace/rarebit-one/CLAUDE.md` too. Its Critical Rules (worktree-only, signed commits,
issue hygiene) apply here. `androidApp/CLAUDE.md` is the deep guide to the Android client
(auth, enrolment, pairing, personal state). Read it before touching `:androidApp`.

**This repo is PUBLIC.** Never name a real machine, host, site, person or IP in code, docs,
commit messages or PR bodies.

## Modules and their boundaries

| Module | Plugin | What belongs here |
|--------|--------|-------------------|
| `:core` | `kotlin.multiplatform` (jvm + android when an SDK is present) | The pure client layer: `net/` (`HttpTransport`, `JsonScan`, `JsonEscapes`, `JsonArrays`), `mcp/` (`McpClient`, `JsonWrite`, `McpModels`), `heyarr/` (REST + telemetry models), `auth/` (`Credential`, `ClientMode`, `GuestGate`), `state/` (library status, search grouping), `theme/MediaType`, `crypto/Blake3`, `vault/`, `feeds/`, `discovery/`. |
| `:ui` | `kotlin.multiplatform` + Compose MP | Compose-typed but platform-free design layer: `theme/Tokens`, `theme/MediaThemes` (the media → accent table), shared glyphs. `api(project(":core"))`. |
| `:composeApp` | `kotlin.multiplatform` (jvm only) + Compose MP desktop | The desktop app and the headless vault-sync daemon. JVM-only code (`JdkHttpTransport`, jmdns, JNA/libmpv, OS keychains) lives here. |
| `:androidApp` | `com.android.application` + `kotlin.android` | The Android app. Only included when an Android SDK is detected. |

Rules:

- **`:core` `commonMain` has no Compose and no `java.*` / `android.*` / JVM-only APIs.** It
  must compile for every target. A platform need gets a seam (an interface or
  `expect`/`actual`) with the concrete implementation in the app or a platform source set.
  Its tests are `commonTest` / `jvmTest` with `kotlin.test`.
- **`:ui` holds tokens and themes only**, with no app state and no I/O.
- Dependency direction: apps → `:ui` → `:core`. Never the reverse, and the two apps never
  depend on each other.
- **Shared logic goes in `:core`, not into an app.** `:androidApp` still carries copies of code
  that has not converged yet (its own `HeyarrApi`, `search/FollowedSource`,
  `state/SearchGrouping`, …). When you touch one, prefer moving
  it toward the shared version over editing both copies. Never add a new copy.

## Build & test

JDK 17. Every module resolves the private `one.rarebit.voidbind:voidbind-client` from
GitHub Packages, so builds need `gpr.user`/`gpr.token` in `~/.gradle/gradle.properties` or
`GITHUB_ACTOR`/`GITHUB_TOKEN` (e.g. `GITHUB_TOKEN=$(gh auth token)`).

```sh
./gradlew :core:jvmTest                          # shared layer (fast, pure JVM)
./gradlew :ui:jvmTest
./gradlew :composeApp:jvmTest                    # desktop unit tests
./gradlew :core:build :ui:build :composeApp:build   # exactly what desktop CI runs
./gradlew :composeApp:run                        # desktop app (display + libmpv)
./gradlew :composeApp:screenshots                # off-screen render of every screen
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug   # what android CI runs
```

- Name the tasks you need. A bare `./gradlew build` also builds `:androidApp` when an SDK is present.
- **The Android SDK is optional.** `settings.gradle.kts` detects it once (`ANDROID_HOME`,
  `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`) and hands the result to every
  project as the `hasAndroidSdk` extra property. Without an SDK, `:androidApp` is not in the
  build and `:core`/`:ui` are JVM-only. Read that property rather than probing again.
- The SDK's `aapt2` is x86-only. On an aarch64 machine, `:androidApp` configures and resolves
  dependencies but cannot assemble or run its unit tests. Rely on CI (`android.yml`) for those,
  and say so in the PR instead of claiming they passed.
- On a memory-constrained machine: `--no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m`.

CI: `desktop.yml` (desktop + shared modules), `android.yml` (unit tests + debug APK),
`instrumented.yml` (emulator smoke test), `android-release.yml` (signed APK on `v*` tags).

## Versions

Plugin and library versions live in `gradle/libs.versions.toml`. Build scripts reference
`libs.*` and never hard-code a version. `voidbind-client` has a single version for every
module; bump it there, once. Toolchain: Kotlin 2.3.20, Gradle 8.9, AGP 8.7.3, Compose MP
1.9.3, compileSdk 35 / minSdk 33, JDK 17.

## Conventions

- **Commits / PRs:** Conventional Commits with a module scope: `feat(desktop): …`,
  `fix(android): …`, `fix(playback): …`, `refactor(kmp): …`, `ci(android): …`,
  `build(vault): …`, `docs(vault-sync): …`. PRs are squash-merged. The body explains the
  *why*, states exactly what was verified locally versus left to CI, and names what was
  NOT proven (e.g. "the live round-trip against a real ffmpeg is not yet proven").
- **Wire JSON is hand-read.** Use `JsonScan` for responses and `JsonWrite` for requests.
  There is no serialization library on the wire. `kotlinx-serialization` is used only for
  `:androidApp`'s typed nav-route arguments. `JsonScan` is a tolerant field scanner, not a
  full parser, so keep JSON test fixtures on one line.
- **heyarr is reached through one typed door per app (`HeyarrApi`).** Don't invent
  endpoints. Every MCP tool or REST read used must exist on the node. Refusal text and rule
  codes are shown verbatim and the tool is named. Personal state is never faked.
- **Never re-derive a Voidbind wire format.** Proofs, certs, invites, pairing and
  encryption belong to `voidbind-client`. This repo keeps only golden vectors to pin them.
- **Tests:** `:core`/`:ui`/`:composeApp` use `kotlin.test`. `:androidApp`'s unit tests are
  **JUnit4** (`org.junit.Assert`: message first, a delta for `Double`s). Don't import
  `kotlin.test` there. Parity vectors copied from heyarr-core are pinned byte-for-byte.
- **Match the surrounding style.** Comments explain *why* (the constraint, the incident,
  the ADR), and there are a lot of them. Keep that density when editing.
