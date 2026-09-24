# heyarr-kmp

The **Kotlin Multiplatform monorepo** for heyarr's first-party clients. heyarr is the
"hey *arr" media-library manager: what content exists, what *should* exist, and why.

| Module | What it is | Targets |
|--------|------------|---------|
| **`:core`** | The shared, pure-Kotlin client layer: the hand-rolled JSON codec (`JsonScan` / `JsonWrite`), the `HttpTransport` seam, the MCP client + models, REST/telemetry models, `Credential` (guest / bearer / voidbind `Device`), library-status and search-grouping derivation, the `MediaType` enum, BLAKE3 + the vault codec. **No Compose, no platform APIs in `commonMain`.** | JVM; Android when an SDK is present |
| **`:ui`** | The shared Compose design layer: `Tokens`, the media → accent table (`MediaThemes`) the self-hosted fonts (`HeyarrFonts`, shipped once as Compose resources), `HeyarrTheme` + `MediaScope`, and the stateless components both apps draw (buttons, chips, states, toasts, `DataTable`, rule lists), plus shared glyphs. Exposes `:core` as `api`. | JVM; Android when an SDK is present |
| **`:composeApp`** | The **desktop** client (Compose Multiplatform, JVM): Linux x64 **and** aarch64, with macOS/Windows for free via the JVM. Also ships the headless vault-sync daemon ([docs/vault-sync-daemon.md](docs/vault-sync-daemon.md)). | JVM |
| **`:androidApp`** | The **Android** client (Compose, Media3/ExoPlayer, Readium), with voidbind QR login and device enrolment. See [androidApp/README.md](androidApp/README.md). | Android (minSdk 33) |

Both apps depend on `:core` and `:ui`. `:androidApp` still carries some code that has not
converged into the shared modules yet (its own `HeyarrApi`, JSON readers and screens).
New shared logic belongs in `:core` (or `:ui` if it is Compose-typed), not in either app.

## Build & test

JDK 17 is required (the modules declare a JDK 17 toolchain). Everything resolves
`one.rarebit.voidbind:voidbind-client` from the org's GitHub Packages, so every build
needs a token with `read:packages`. Set `gpr.user` / `gpr.token` in
`~/.gradle/gradle.properties`, or pass `GITHUB_ACTOR` / `GITHUB_TOKEN`
(e.g. `GITHUB_TOKEN=$(gh auth token)`).

```bash
./gradlew :core:jvmTest                     # shared layer: fast pure-JVM tests
./gradlew :core:build :ui:build :composeApp:build   # what desktop CI runs
./gradlew :composeApp:run                   # the desktop app (needs a display; libmpv for in-app playback)
./gradlew :composeApp:screenshots           # every screen rendered off-screen → composeApp/build/screenshots/
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug   # what android CI runs
```

**The Android SDK is optional.** `settings.gradle.kts` detects it (`ANDROID_HOME`,
`ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`). Without one, `:androidApp` is
left out of the build and `:core` / `:ui` build JVM-only. A desktop-only machine can
still build and test everything except the phone app.

A devcontainer (`.devcontainer/`) provides JDK 17 and the Skia/X11 libraries for headless
builds and screenshots. `scripts/devcontainer-exec <cmd>` rsyncs the tree to a container
host, runs the command there, and syncs reports and screenshots back.

### CI

| Workflow | Runs | Triggered by |
|----------|------|--------------|
| `desktop.yml` | `:core:build :ui:build :composeApp:build`, the vault-sync uber JAR, screenshots | PRs + `main`, path-filtered |
| `android.yml` | `:androidApp:testDebugUnitTest :androidApp:assembleDebug` | PRs + `main`, path-filtered |
| `instrumented.yml` | an on-device Compose smoke test on an emulator | PRs + `main`, path-filtered |
| `android-release.yml` | the signed release APK, attached to a GitHub Release | `v*` tags |

## Toolchain

| Piece | Version |
|-------|---------|
| Kotlin | 2.3.20 (matches the org) |
| Gradle | 8.9 |
| JDK | 17 (Temurin) |
| AGP | 8.7.3 (compileSdk 35, minSdk 33) |
| Compose Multiplatform | 1.9.3 (desktop, `:ui`) |
| Compose compiler | bundled with Kotlin (`org.jetbrains.kotlin.plugin.compose`) |
| voidbind-client | 0.8.0 (GitHub Packages, private) |

---

# The desktop client (`:composeApp`)

It is a real client, not a mockup: every screen has loading skeletons, empty and error
states, an offline banner, keyboard operability with visible focus rings, and semantic
labels for assistive tech — and it is honest about what heyarr can and cannot say.

## What it does

| Screen | heyarr surface |
|--------|----------------|
| **Home / Discover** — spotlight hero + themed rails | `GET /works` (recent), `search_content` per type, `get_missing_content`, `get_upgrade_candidates`, `list_followed`; `discover_content` (quotes the node's refusal when no TVDB provider is configured) |
| **Search** (Ctrl+F) — one box, every media kind at once, results grouped by type and streamed in per segment; ↑/↓/Enter; type filter chips; recent searches (local) | `search_content` × {movie, series, music, book} + an untyped call for episodes, in parallel; followed sources matched client-side |
| **Detail** — one adaptive template re-skinned per media type: hero art + scrim, the type's own CTA, *Why this release* with rule codes verbatim, releases, health | `GET /works/{id}`, `GET /works/{id}/assets`, `get_content_satisfaction`, `GET /desired/{id}/candidates`, `explain_release`, `search_releases`, `acquire_release`, `get_replica_status`, `verify_blob`, `get_external_ids`, `play_here`, `monitor_content` |
| **Library** — everything catalogued, filter by type + status, grid/list | `GET /works` + the want index |
| **Missing / Wanted** — bulk search-now / monitor on-off; Want by title | `get_missing_content`, `get_upgrade_candidates`, `search_releases`, `monitor_content`, `want_content` |
| **Now playing** — pick a renderer, live status, transport | `list_renderers`, `playback_status` (polled), `control_playback` |
| **Settings** — connection, followed sources, peers, appearance | `list_followed`, `follow_source`, `unfollow`, `get_peer_status`, `sync_peer`, `GET /quality-profiles` |

**Library status** (In library · Wanted · Missing · Not tracked) is derived only from
real want state (`GET /desired` → acquisition state per work). **Want** is optimistic:
the card flips at once and rolls back with the tool's refusal on failure.

### What it deliberately does not do

heyarr's MCP surface **cannot see personal state** — playlists, ratings, reading or
playback positions, history are encrypted controller-side. So there is no "Continue
watching", no star ratings, no resume position. Now-playing shows only what the
renderer reports live; recent searches are kept on this machine and labelled as such.

### Rule codes are quoted, never paraphrased

Every verdict heyarr returns (`explain_release`, `get_content_satisfaction`, the
candidates list) carries stable rule codes — `resolution.gte`, `source.nin`,
`size_bytes.lte` … — with a section (accept / prefer / terminal) and a result
(pass / fail / bonus / miss / **undetermined**). The UI renders the code, the result
and the server's own detail text verbatim. A refused action shows the refusal's text
and names the tool in a toast.

## Design system

Tokens live in one place — `:ui`'s `theme/Tokens.kt` (surfaces, text ramp, radii,
spacing, type scale) — and the media table in `:ui`'s `theme/MediaThemes.kt`, keyed on
`:core`'s `MediaType`:

| Media | Accent | Card | CTA |
|-------|--------|------|-----|
| Movie | `#00935E` emerald (app default) | 16:9 | Play |
| Series | `#7C5CFF` violet | 16:9 | Play / Next episode |
| Book | `#E0A458` amber, spine shadow | 2:3 | Read |
| Audiobook | `#2DB3A6` teal | 1:1 | Listen |
| Podcast | `#C13BAD` magenta | 1:1 | Play episode |
| Music | `#FF4D6D` rose | 1:1 | Play |
| Feed / document / unknown | `#7A8598` slate | — | Open |

The accent swaps the CTA gradient, focus rings, active-nav mark, progress bars and
section underline; surfaces and text stay constant. Wrap any subtree in
`MediaScope(type) { … }` to re-skin it. Fonts are self-hosted (OFL): **Rubik** fills
every type slot, from headings and body to the compact technical labels — shipped once,
for both apps, from `:ui` (`ui/src/commonMain/composeResources/font`, exposed as
`HeyarrFonts`).

## How heyarr is reached

`heyarr/HeyarrApi.kt` is the one typed door. MCP tools go over `:core`'s `mcp/McpClient.kt`: a
stateless JSON-RPC 2.0 `tools/call` POST to `/api/v1/mcp` with the same bearer as the
REST reads (verified: no `initialize` handshake needed). Result text is read with the
hand-rolled `JsonScan` (the org's no-serialization-library stance; the request side is
the tiny `JsonWrite`, which **drops null arguments** so an unknown release attribute
reads as *undetermined* rather than as a claim).

Three REST reads have no tool equivalent and were verified live before use:
`GET /quality-profiles`, `GET /desired` (paged), `GET /desired/{id}/candidates`.
Artwork streams from `GET /blobs/{hash}/content` through `state/ArtworkLoader.kt`
(authenticated, decoded off-thread, memory LRU + XDG disk cache, lazy per card,
blur-up on arrival).

## Running it

To run the packaged image (`./gradlew :composeApp:createDistributable` builds a Linux
app-image with a bundled JRE) on Hyprland / sway / any non-reparenting WM with HiDPI
Wayland, use `scripts/heyarr-desktop`. It sets `_JAVA_AWT_WM_NONREPARENTING=1`
(otherwise AWT never accepts the compositor's resize). The UI scale comes from
Settings → Appearance, seeded from `HEYARR_UI_SCALE` or `GDK_SCALE`, because a JVM under
XWayland reports 1× and ignores `sun.java2d.uiScale` for Compose.
`scripts/install-desktop.sh` installs a versioned build locally.

Playback in the app is libmpv in-process (`libmpv.so` / `libmpv.dylib`, part of the
`mpv` package on Arch and Homebrew): mpv decodes into memory frames the UI draws, so
the transport and everything else sit over the picture. "Pop out" runs the `mpv`
command in a window of its own, driven over the same control socket.

The screenshot task drives the real `App` with `preview/Fixtures.kt` — canned answers
in the shapes observed on a live node — through Compose's `ImageComposeScene`, so a
headless CI can show the app. The same fixtures back the JVM tests.

## Keyboard

Ctrl+F search · ↑ ↓ move · ↵ open · Esc clear / back · Ctrl-1 Watch · Ctrl-2 Listen · Ctrl-3 Read · Ctrl-4 Missing · Ctrl-5 Cast · ⌘, settings · Tab walks every control, with a 2px accent focus ring.

## Project structure

```
composeApp/src/jvmMain/kotlin/one/rarebit/heyarr/desktop/
├── Main.kt       # entry point; wires concretes
├── heyarr/       HeyarrApi (the typed door: one method per tool / verified REST read)
├── state/        AppSession (config, connectivity, want index, toasts), SearchController (debounced fan-out),
│                 ArtworkLoader, RecentSearches, PlaybackSession, VaultSyncController
├── ui/           App (shell, keys, Want sheet), Route/Nav, EnrolPanel, components/, screens/
├── device/       device enrolment: the keyring (OS keychain → libsecret → sealed file), pairing coordinator
├── vault/        vault sync engine + the headless daemon (daemon/)
├── preview/      Fixtures + FakeHeyarrTransport, Screenshots (off-screen renderer), PlaceholderArt
└── net/ discovery/ library/ catalog/ music/ books/ feeds/ open/ playback/ settings/ login/
```

The JSON codec, MCP client + models, REST/telemetry models, `Credential`, library status
and search grouping live in `:core` (`one.rarebit.heyarr.core.*`).

## Signing in: guest by default, then bearer token or device enrolment

The desktop connects as a **guest** by default. It finds the node over mDNS
(`_heyarr._tcp`), and on a trusted network it can browse and play with no credential.
"Sign in to save" is the upgrade, and it offers two credentials (`login/VoidbindLogin.kt`):

- **Device enrolment** (preferred): the desktop pairs in as a voidbind device over the
  relay (`device/`, voidbind-client's `DevicePairing`). It keeps its Ed25519 seed in
  the OS keychain (macOS Keychain, else libsecret, else a sealed file) and presents
  `Authorization: Device <cert>~<proof>`.
- **Bearer token**: a pasted `heyarr_<id>_<secret>` (ADR-0011), stored in
  `~/.config/heyarr-desktop/config.json` with `0600` perms.

## Next steps

- **Secret storage for the bearer token**: move it from the config file into the same
  keychain the device key already uses.
- **Reader**: the EPUB/CBZ surface for books (the desktop reader is still a placeholder).

### Archive interface

Desktop opens on **Watch**, with **Listen** and **Read** shelves alongside it.
**Manage** opens the existing search and library tools. These shelves group the
catalogue; metadata-provider coverage is unchanged.

Audio remains active while browsing or reading. On windows at least 1100 dp wide,
a full-height player shows the actual cover, transport, volume and upcoming tracks;
narrower windows use the compact transport. Album “Queue all” uses this same
session player, including automatic advance at the end of a track.

Desktop and Android share the forest palette, compact Rubik typography and Archive
mark. Navigation labels sit below square icon tiles; selection and keyboard focus
enclose the icon only. Cover images still come from the existing artwork pipeline.
The desktop buffering glyph respects the reduced-motion preference.
Android retains its existing navigation and playback behavior in this iteration.

Archive controls use flat fills, square borders and uppercase labels. Media titles
and reading text retain their original casing. Desktop search uses **Ctrl+F**,
including on Linux; the app does not bind Super/Command+K or Super/Command+F.

Media cards use wide 16:9 video frames, square audio covers and tall 2:3 books.
Artwork fits inside the frame without cropping the source image. Desktop Search
now includes Library and Discover views sharing one query; Discover asks the
metadata provider for TV series to add, even when local results already exist.
