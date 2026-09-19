# heyarr-kmp

The **Kotlin Multiplatform monorepo** for heyarr's first-party clients — the "hey *arr"
media-library manager: what content exists, what *should* exist, and why. It houses:

- **`:composeApp`** — the desktop client (Compose Multiplatform, JVM; Linux x64 **and**
  aarch64 (Asahi/Omarchy), with macOS/Windows for free via the JVM).
- **`:androidApp`** — the Android client (Media3/Readium).
- **`:core`** — the shared, pure-Kotlin client layer (JSON, MCP client + models,
  `HttpTransport`, `Credential` incl. voidbind device auth, REST models, library/search
  state) consumed by both apps.
- **`:ui`** — the shared Compose design tokens/themes.

The rest of this document describes the desktop client specifically.

It is a real client, not a mockup: every screen has loading skeletons, empty and error
states, an offline banner, keyboard operability with visible focus rings, and semantic
labels for assistive tech — and it is honest about what heyarr can and cannot say.

## What it does

| Screen | heyarr surface |
|--------|----------------|
| **Home / Discover** — spotlight hero + themed rails | `GET /works` (recent), `search_content` per type, `get_missing_content`, `get_upgrade_candidates`, `list_followed`; `discover_content` (quotes the node's refusal when no TVDB provider is configured) |
| **Search** (⌘K / Ctrl-K) — one box, every media kind at once, results grouped by type and streamed in per segment; ↑/↓/Enter; type filter chips; recent searches (local) | `search_content` × {movie, series, music, book} + an untyped call for episodes, in parallel; followed sources matched client-side |
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

Tokens live in one place — `theme/Tokens.kt` (surfaces, text ramp, radii, spacing,
type scale) — and the media table in `theme/MediaType.kt`:

| Media | Accent | Card | CTA |
|-------|--------|------|-----|
| Movie | `#00935E` emerald (app default) | 2:3 | Play |
| Series | `#7C5CFF` violet | 2:3 | Play / Next episode |
| Book | `#E0A458` amber, spine shadow | 2:3 | Read |
| Audiobook | `#2DB3A6` teal | 1:1 | Listen |
| Podcast | `#C13BAD` magenta | 1:1 | Play episode |
| Music | `#FF4D6D` rose | 1:1 | Play |
| Feed / document / unknown | `#7A8598` slate | — | Open |

The accent swaps the CTA gradient, focus rings, active-nav mark, progress bars and
section underline; surfaces and text stay constant. Wrap any subtree in
`MediaScope(type) { … }` to re-skin it. Fonts are self-hosted (OFL): **Inter** for UI
and body, **Montserrat** for display headings — `composeApp/src/jvmMain/resources/fonts`.

## How heyarr is reached

`heyarr/HeyarrApi.kt` is the one typed door. MCP tools go over `mcp/McpClient.kt`: a
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

## Build & run

Nothing builds on the laptop: this repo has a **devcontainer** and a bridge script
that rsyncs the tree to the container host (mac-mini-1 / lamia-1), runs the command
inside the container and syncs reports/screenshots back — the same model as heyarr-core.

```bash
scripts/devcontainer-exec ./gradlew build              # compile + JVM unit tests
scripts/devcontainer-exec ./gradlew :composeApp:screenshots   # renders every screen off-screen → composeApp/build/screenshots/
scripts/devcontainer-exec ./gradlew :composeApp:createDistributable  # Linux app-image with a bundled JRE
```

To run the packaged image on this machine (Hyprland / sway / any non-reparenting WM,
HiDPI Wayland): `scripts/heyarr-desktop`. It sets `_JAVA_AWT_WM_NONREPARENTING=1`
(otherwise AWT never accepts the compositor's resize) and the UI scale comes from
Settings → Appearance, seeded from `HEYARR_UI_SCALE` or `GDK_SCALE` — a JVM under
XWayland reports 1× and ignores `sun.java2d.uiScale` for Compose.

Playback in the app is libmpv in-process (`libmpv.so` / `libmpv.dylib`, part of the
`mpv` package on Arch and Homebrew): mpv decodes into memory frames the UI draws, so
the transport and everything else sit over the picture. "Pop out" runs the `mpv`
command in a window of its own, driven over the same control socket.

Locally, with a JDK 17 on `PATH`:

```bash
./gradlew build
./gradlew :composeApp:run   # needs a display; libmpv for playback in the app, the mpv command for the pop-out
```

The screenshot task drives the real `App` with `preview/Fixtures.kt` — canned answers
in the shapes observed on a live node — through Compose's `ImageComposeScene`, so a
headless CI can show the app. The same fixtures back the JVM tests
(`McpClientTest`, `McpModelsTest`, `SearchGroupingTest`, `LibraryStatusTest`,
`MediaThemeTest`, plus the earlier client/parser tests).

## Keyboard

⌘K / Ctrl-K search · ↑ ↓ move · ↵ open · Esc clear / back · Ctrl-1 Watch · Ctrl-2 Listen · Ctrl-3 Read · Ctrl-4 Missing · Ctrl-5 Cast · ⌘, settings · Tab walks every control, with a 2px accent focus ring.

## Toolchain

| Piece | Version |
|-------|---------|
| Kotlin | 2.3.20 (matches the org) |
| Gradle | 8.9 |
| JDK | 17 (Temurin) |
| AGP | n/a (no Android target yet) |
| Compose Multiplatform | 1.9.3 |
| Compose compiler | bundled with Kotlin (`org.jetbrains.kotlin.plugin.compose:2.3.20`) |

## Project structure

```
composeApp/src/jvmMain/kotlin/one/rarebit/heyarr/desktop/
├── Main.kt                    # entry point; wires concretes
├── mcp/        JsonWrite, McpClient (JSON-RPC over HttpTransport), McpModels (typed readers: Reason, Want, Satisfaction, Explanation …)
├── heyarr/     HeyarrApi (the typed door: one method per tool / verified REST read), RestModels (QualityProfile, DesiredItem, Candidate)
├── theme/      Tokens, MediaType + MediaThemes (the media table), HeyarrTheme (fonts, Material mapping, MediaScope)
├── state/      AppSession (config, connectivity, want index, toasts), SearchController (debounced fan-out), SearchGrouping, LibraryStatus, ArtworkLoader, RecentSearches
├── ui/         App (shell, keys, Want sheet), Route/Nav, components/ (buttons, chips, cards, rail, hero, reasons, side nav), screens/
├── preview/    Fixtures + FakeHeyarrTransport, Screenshots (off-screen renderer), PlaceholderArt
├── net/ library/ music/ books/ feeds/ open/ playback/ settings/ auth/ login/   # the v1 clients and seams, unchanged
└── resources/fonts/           # Inter + Montserrat (OFL)
```

## Login: bearer token today, Voidbind later

Settings takes a pasted bearer token (`heyarr_<id>_<secret>`, ADR-0011), stored in
`~/.config/heyarr-desktop/config.json` with `0600` perms. Voidbind device/QR login is
behind `login/VoidbindLogin.kt` (`LoginProvider`) and stays stubbed until the private
`one.rarebit.voidbind:voidbind-client` artifact is resolvable — that needs a GitHub PAT
with `read:packages`: set `gpr.user` / `gpr.token` (or `GITHUB_ACTOR` / `GITHUB_TOKEN`),
uncomment the GitHub Packages repo in `settings.gradle.kts` and the dependency in
`composeApp/build.gradle.kts`, then replace `VoidbindLoginStub` with a coordinator over
the library's `LoginApproval` / `DevicePairing` / `WebLoginClient`.

## Next steps

- **Secret storage** — move the token from the config file into libsecret / KWallet.
- **Shared KMP module** — extract `net/`, `mcp/`, `heyarr/`, `state/` into a
  `commonMain` shared with heyarr-mobile (the code here already avoids JVM-only APIs
  below the UI, except `JdkHttpTransport`, `FileSettingsStore` and `ArtworkLoader`'s
  Skia decode).
- **Reader** — the EPUB/CBZ surface for books.
- **Personal-state crypto** — the encrypted personal-state sync, once the desktop can hold a device key.

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
