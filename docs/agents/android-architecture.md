# `:androidApp` architecture: what it is, personal state, design system, layout

Moved verbatim from the former `androidApp/CLAUDE.md`; the router is `androidApp/AGENTS.md`.

## What this is

The **first-party Android client for heyarr** (the self-hosted media platform). It is the
*product* client (plan `~/.claude-family/plans/voidbind-client-apps-and-push.md` §4); the
Subsonic/OPDS/DLNA compat adapters are *reach*, not the product. It signs in via **Voidbind
QR login**, browses heyarr's native library, and is built to hold **device-side personal
state** (decrypt-on-device) — the differentiator over a generic Subsonic app.

This is a **scaffold**: a buildable, tested foundation. Feature work lands as PRs on top.

## The two sibling repos (read before touching auth or the data client)

- **`rarebit-one/heyarr-core`** — the server (PUBLIC / AGPL). Serves the weblogin broker
  (`POST /login`, `GET /login/{id}`), the library/playback APIs (`/api/v1/works`,
  `/api/v1/playback`, `/api/v1/blobs/{hash}/content`), and the encrypted personal-state sync
  surface (`/api/v1/spaces/{id}/{keys,changes,snapshot}`). The **contract** this client builds
  against is `heyarr-core/docs/design/mobile-client.md` (+ ADR-0048 device auth, ADR-0049/0051
  personal state). Adding this client needs **no server change**.
- **`rarebit-one/voidbind-kmp`** — THE Voidbind authenticator app (**Cruciform**, `one.rarebit.cruciform`) + the shared `voidbind-client`
  (`WebLoginClient`, `LoginQr`, `WebLogin`, `LoginApproval`). This app is a *consumption
  client* that delegates login approval to that authenticator.

## Personal state is opaque to the node; decrypt happens ONLY on-device

`personalstate/` is the **device-side M9 engine** (Phase F). It fetches
`/api/v1/spaces/{id}/{keys,changes,snapshot}` as **opaque ciphertext** — the peer never
decrypts (Invariant 6, ADR-0049) — and does the decrypt-and-fold and the mint on THIS device:
`SpaceSession` finds the wrapped key sealed for this device, unwraps it with the phone's X25519
key (`SpaceCrypto` over voidbind-client's `VoidbindEncryption`, since **0.7.0** — X25519 wrap + XChaCha20,
KAT-proven; **no wire format is re-derived here**), decrypts the snapshot + changes, and folds
them through the four CRDT ports (`Playlist`/`StarSet`/`ReadingPositions`/`PlayLog`). A write is
minted at the current heads, encrypted, and pushed; the node re-derives the content-addressed id
(`:core`'s pure-Kotlin `Blake3` + `PersonalStateId`) and refuses a mismatch, so every CRDT + the id framing are
pinned **byte-for-byte** to heyarr-core's parity vectors (copied into `androidApp/src/test/resources/`;
regenerate in heyarr-core with `-update` and re-copy). `PersonalStateCoordinator` is the app-facing
façade; `SpaceRegistry` is the device-side role map the gateway keeps as `SpaceRoles` (every
openable non-role space is a playlist). A read-only credential views only. `playlist/` is the UI.
A local **Personal MCP** (#372/#387) is still a device-gated follow-up.

## Design system (ported from heyarr-desktop, PR #3)

The UI is the **Heyarr Desktop design language**, ported verbatim where the platform
allows: tokens in `theme/Tokens.kt` (bg #080709, surfaces #131116/#1B1922/#232029,
border #2A2833, text #F5F5F4/#A09F9D/#6E6D72, rating gold #F5C518, default accent emerald
#00935E→#21C063; radii 14/10/999; 4-px spacing) and the media → theme table
(`:core`'s `MediaType` + `:ui`'s `MediaThemes`) (Movie emerald · Series violet · Book amber with a spine shadow ·
Audiobook teal · Podcast magenta with light-safe CTA · Music rose · feeds/documents/unknown
slate). The accent drives the CTA gradient, pressed/focus states, the active nav tile,
progress bars and the section underline; surfaces and text never change.
`MediaScope(type) { … }` re-skins a subtree; `MediaThemeTest` pins the table and AA contrast.
Fonts are self-hosted OFL TTFs shipped once by `:ui` (`ui/src/commonMain/composeResources/font`,
licences under `composeResources/files/licenses`, typed families in `HeyarrFonts`): **Rubik**
fills every type slot (headings, body and the technical voice — nav captions, rule-code chips,
key/value labels, badges). Inter and Montserrat were dropped once nothing used them.

Nav is a bottom bar on a phone and a left rail from 600 dp (`Tokens.railBreakpoint`):
Home · Discover · Search · Library (Works / Downloads / Playlists tabs) · Missing · Cast ·
Settings. No Forum. Every screen has skeletons, empty/error/offline states and TalkBack
descriptions; Want is optimistic with rollback; rule codes and refusal text are quoted
verbatim and the tool is named in the toast.

**heyarr is reached one way.** `heyarr/HeyarrApi` is the typed door: MCP tools over
`mcp/McpClient` (a stateless JSON-RPC `tools/call` POST to `/api/v1/mcp`, same credential
as REST, refusals kept as values with the server's wording) plus the verified REST reads
this app already had (`library/`, `catalog/`, `search/` clients). Never invent an endpoint.
Personal state (history, ratings, positions) is NOT on the node's surface; the only
personal rows shown (Starred, Recently played, Playlists) are this phone's own
decrypted state and are labelled so. The Continue rail is the node's consumption
sessions (`GET /consumption/continue`), labelled as such.

## Layout

```
androidApp/src/main/java/one/rarebit/heyarr/mobile/
  MainActivity.kt (edge-to-edge under HeyarrTheme; login/enrol/pre-login settings frames; the signed-in shell is
                   nav/HeyarrNavHost) · HeyarrApp.kt (Application: the app-scoped pairing holder + Coil ImageLoaderFactory) ·
  AppGraph.kt (by-hand object graph: settings, ONE OkHttp client + AuthInterceptor, AuthHeaderSource, the audio queue
                controller, the VideoSession, the public-metadata cache, recent searches — no DI container) ·
  AppViewModel.kt (session/config/login/library; enrolment lives in device/DeviceEnrolment, personal-state wiring in
                personalstate/DevicePersonalState, playback planning in playback/PlaybackCoordinator) · HeyarrConfig.kt ·
  SessionText.kt (the "signed in as … · scope" line)
  theme/        Tokens (the phone's design tokens over `:ui`'s) · HeyarrTheme (`:ui`'s theme with `PhonePlatform`: the
                phone's type ramp, touch conventions, pinned content colour; LocalMediaTheme/MediaScope are `:ui`'s)
  ui/components/ Cards (Artwork blur-up over Coil, MediaCard with long-press actions, MediaRow, Rail, Hero + scrim,
                skeletons; StatusPill/RailState are `:ui`'s) · Nav (NavSection, HeyarrBottomBar, HeyarrNavRail) · NowPlayingBar (one bar for the
                video session and the audio queue) · Cover (rememberCover: node art, else a labelled public cover). The
                primitives (buttons, chips, Field, states, OfflineBanner, ToastCard), DataTable/Cell/Section and the
                Reasons set are `:ui`'s `components/`, shared with the desktop
  ui/screens/   HomeScreen (spotlight hero, Continue, Starred/Recently played when decrypted here, per-type rails, wanted/upgrade
                rails, Following; Discover = the same with the discover_content notice) · SearchScreen (universal search over
                state/SearchController, per-type sections streaming in, local recent searches) · DetailScreen (the shell, hero and cast picker; the tabs in DetailWatch.kt / DetailCurate.kt — Watch tab: art,
                synopsis, seasons/episodes with -thumb sidecars, tracks, book files, feed archive; Curate tab: wants & status,
                held files with verdicts, indexer candidates + Acquire, score a release, health/replicas, captions & artwork,
                "also catalogued as", identifiers, all files — as tables) · LibraryScreen (+ DownloadsScreen: wants in flight
                and the job queue) · MissingScreen (bulk search-now / monitor, Want by title) · CastScreen (list_renderers,
                live playback_status, control_playback) · SettingsScreen (connection, telemetry link, device, followed sources,
                peers, appearance) · TelemetryScreen (/session, /providers, /capabilities, peers, libraries, jobs) ·
                PlayerScreen (the in-app ExoPlayer: transport, captions menu with language names, cast, up next, fullscreen) ·
                AudioQueueScreen · WantSheet (a bottom sheet: profile, monitor, reason)
  state/        AppSession (per node+credential: HeyarrApi, heartbeat/connection, the want-derived LibraryIndex, quality
                profiles, appearance prefs, toasts, optimistic want; LibraryStatus/LibraryIndex come from `:core`) · SearchController (a thin Compose adapter over `:core`'s shared SearchController, which owns the fan-out; followed sources cross via `asFeedSource()` in its SearchBackend) ·
                RecentSearches (a local file, labelled local) · PhoneExternalMetadata (the OkHttp fetcher + User-Agent for
                `:core`'s shared ExternalMetadata: keyless public covers/synopses, disk-cached; ExternalParsers pure)
  mcp/          McpClient (JSON-RPC tools/call → Ok text | Refused error, transport failures thrown) · McpModels (Reason,
                Want, Satisfaction, Explanation, Renderer, PlaybackStatus, Peer, Replica, SearchHit/EpisodeHit, …)
  heyarr/       HeyarrApi (the one typed door: every MCP tool + the REST reads) · RestModels (QualityProfile, DesiredItem,
                Candidate); the Telemetry reads (SessionInfo, ProviderInfo, …) are `:core`'s
  nav/          Routes (typed, @Serializable — ids and display hints ONLY; Player is argless) · HeyarrNavHost (the shell) ·
                SessionHolder (a ViewModel keyed on ApiEnv holding AppSession + every screen's state) · Decisions (pure:
                player content, bar visibility, nav section, album → queue) · ApiEnv
  preview/      Fixtures + FakeHeyarrTransport (canned live-node shapes shared with the tests)
  catalog/      CatalogClient (GET /works pages with the embeds) · Artwork (poster URL) · ContinueClient (GET /consumption/continue)
  library/      LibraryClient (GET /works?include=artwork,primary_asset, paged) + WorksJson (Work now carries string
                attributes) · WorkDetailClient + WorkDetailJson (assets, wants, the management writes) · SeriesTypes
                (`Episode`/`Season` over `:core`'s shared `library/Series`: files → seasons → episodes with sidecars, gaps,
                quality tags; `:core`'s `Variants` folds download-folder works, heyarr-core#470) · LibraryUiState
  music/        MusicClient (GET /artists) + MusicJson · Track (WorkAsset audio/primary-role/title helpers, Tracks.playable)
  search/       AcquireClient · FollowedSource(s)Json · FollowedSourceClient +
                FollowedItem · SessionClient + SessionJson · DiscoverClient — the REST clients the typed door composes
  playback/     PlaybackCoordinator (plan against real capabilities, blob fallback, ONE re-plan) · PlaybackClient ·
                PlaybackTarget · HeyarrDataSource · VideoSession (the app-scoped ExoPlayer the now-playing bar carries
                between screens: transport, captions, restart-seek for streams, up-next queue, fullscreen flag) ·
                PlaybackProgress · AudioPlayer seam + SessionAudioPlayer (MediaController over PlaybackService) ·
                AudioSessionBridge · PlaybackDiagnostics · Subtitles · MediaMime · ClientCapabilities
  reader/       ReaderActivity (Readium 3: EPUB / PDF / comic) + ReaderHttp + ReadingPositionStore/Sync · ReaderAsset (formats)
  consumption/  ConsumptionClient · DeviceIdStore · ProgressReporter + ConsumptionReporter
  personalstate/ the M9 engine (ids via `:core`'s PersonalStateId, the four CRDTs, SpaceCrypto, PersonalStateClient, SpaceSession,
                SpaceRegistry, PersonalStateCoordinator)
  playlist/     PersonalActionsViewModel (star / add-to-playlist / record play + the Home rows) · PlaylistScreens (restyled)
                + PlaylistViewModels + AddToPlaylistDialog
  settings/     SettingsStore (base URL, profile, appearance prefs; in-memory for tests)
  auth/ device/ login/ net/  unchanged: Credential · DeviceKeyring + pairing · QR login · HttpTransport/OkHttp, JsonScan +
                JsonEscapes + JsonWrite (the hand-rolled JSON stance — no serialization library on the wire)
androidApp/src/test/… pure-JVM unit tests (no Android runtime) — including the desktop's MediaThemeTest (table + AA contrast),
                McpClientTest, McpModelsTest (rule codes verbatim, the typed door over the fixtures), FollowedSourceFeedMappingTest,
                LibraryStatusTest, SeriesTest, VariantsTest, DecisionsTest, RoutesTest (SearchGrouping/ExternalParsers are tested in `:core`)
.github/workflows/android.yml   CI: testDebugUnitTest + assembleDebug on ubuntu-latest
```

## What's phone-gated (deferred, can't be CI-proven)

On-device playback **acceptance** — the Media3/ExoPlayer player against `/blobs/.../content`
now ships (`playback/`), but a real codec decoding and a scrub's live 206 range reads only
prove out on a device; the `/playback` negotiation (`PlaybackClient.plan`) is wired but keyed
on an enrolled `device_id` — on-device personal-state **decrypt** (Keystore/StrongBox X25519 unwrap + AEAD) and the local
Personal MCP, **device-cert login** (in-enclave Ed25519 possession proof + enrolment), QR
**bitmap** rendering, and choosing whether to ship the Subsonic reach. See `androidApp/README.md`'s
follow-ups. **Keep CI green** — unit tests + `assembleDebug` are the bar; anything needing a
real device stays a device-side follow-up, not a scaffold blocker.
