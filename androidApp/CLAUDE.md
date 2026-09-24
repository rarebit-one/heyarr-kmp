# CLAUDE.md — `:androidApp` (the heyarr Android client)

Guidance for Claude Code working in **`:androidApp`**, the Android module of the
`heyarr-kmp` monorepo (it began life as the separate `heyarr-mobile` repo, whose name
survives in the `heyarr-mobile://` deep links and the release APK's file name).
Read the repo-root `CLAUDE.md` for module boundaries and the monorepo's build commands,
and the workspace `~/Workspace/rarebit-one/CLAUDE.md` too — its Critical Rules
(worktree-only, signed commits, autonomous-merge on green CI, issue hygiene) apply here.

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

## Two credential shapes (mobile-client contract, ADR-0048)

- **Primary — `Authorization: Device <cert>~<proof>`** (`auth/Credential.Device`, rendered
  through voidbind-client's `one.rarebit.voidbind.auth.DeviceCredential`): an enrolled
  device's user-signed cert + a fresh **possession proof**, joined by `~`. Since
  voidbind-client **0.4.0** the proof, the `~` join and the re-mint policy are the
  **library's** (`auth/{PossessionProof, DeviceCredential, DeviceAuthPolicy}`) — the
  app's own port was deleted, and **nothing under `auth/` here mints or joins anything**.
  The proof is a byte-exact port of voidbind-go v0.5.0 `enrolment.SignPossession` (what
  heyarr-core vendors): `base64url({"v":2,"crt":b64url(sha256(cert)),"iat","exp"}) + "." +
  base64url(ed25519 sig)`, no padding, no domain label — still pinned **in this repo** by
  the two Go-minted golden vectors in `PossessionProofTest`, so a library bump that drifts
  the wire fails our CI. The signature comes from the phone's **hardware-sealed** Ed25519
  key (`DeviceIdentity.asSigner()` over voidbind-client `DeviceKeyStore`, voidbind-kmp
  ADR-0001: software seed sealed by a non-extractable, user-presence-gated AES key —
  StrongBox where present, **TEE on the Nothing Phone**; `device/DeviceKeyring` reports
  the honest tier). Since voidbind-client **0.6.0** the signing key is provisioned with a
  **1-hour user-auth window** (`DeviceKeyring.USER_AUTH_VALIDITY_SECONDS`, the library's
  `getOrCreate(alias, userAuthValiditySeconds)`), so one biometric authorises an hour of
  silent signing and `AppViewModel` builds the `DeviceCredential` at the **library default
  short ttl** (`PossessionProof.DEFAULT_TTL_SECONDS`, 2 min, reused for ttl − skew) —
  restoring heyarr-core#444's short-proof cadence. The window is baked in at key creation,
  so this is a **new alias** and the phone re-enrols once (Path A, see below);
  `net/DeviceAuthTransport` drives `DeviceAuthPolicy.execute` — refresh + retry **once**
  on a 401 (heyarr's Device refusals are all an undifferentiated 401) — and owns the
  `Voidbind-Membership` header (`MEMBERSHIP_HEADER`): since voidbind-client **0.5.0**
  (ADR-0005 / heyarr-core ADR-0068) the credential token is this device's **admitting
  op** (a v3 membership op; a v1/v2 cert IS a genesis add and still works) and the
  header carries the membership **ops** the device knows — `device/MembershipOps`
  picks ≤ 64, the justifying closure of the device's own admission first. After a
  401, BEFORE the retry, `DeviceEnrolment.refreshMembership` re-reads
  `GET /membership/{usr}` (`device/MembershipClient`, 404 tolerated), merges it into
  the replica and evaluates; a device no longer a member drops its credential and
  shows `EnrolUiState.Removed` — no retry, no loop.
- **Bootstrap — `Authorization: Bearer <token>`** (`auth/Credential.Session`): a
  short-lived session token from a **QR** web-login, how a fresh install reaches the
  library before it enrols as a device.

## Login is QR (heyarr's channel) — plus the same-phone hand-off

Per the plan's DECISIONS LOG: **heyarr login channel = QR**. The app is the RP/initiator
(`POST /login` → render the `voidbind:login?rp=&id=` tuple as a QR → poll `GET /login/{id}`
→ Bearer token), driven through voidbind-client's `WebLoginClient` (`login/QrLoginClient`
is the app's state machine over it). When Cruciform (the Voidbind authenticator app) is installed on the
**same phone**, an "Approve on this phone" button fires the identical tuple as an
`ACTION_VIEW` intent (`login/VoidbindHandoff`, with a `callback=heyarr-mobile://login`
so it can foreground us) — no second-phone QR dance; the RP is still polled. (Contrast
`allthing-android`, whose channel is push-approve — do **not** copy FCM/ntfy wiring.)

The **reverse** handoff exists too (voidbind-kmp ADR-0006): Cruciform's "Add a device"
on the same phone fires **`heyarr-mobile://pair?invite=<percent-encoded voidbind:pair
tuple>`** (manifest filter; `device/PairDeepLink` routes it — pure Kotlin, unit-tested)
into the SAME join path a scan takes (`PairInvite.check` → `AppViewModel.receiveInviteLink`
→ `DevicePairing.begin`). An unprovisioned phone **parks** the invite (`parkedInvite`)
and joins it automatically after "Create device key" — a link never triggers the
fingerprint prompt by itself; a still-loading keyring continues when the read lands. The
SAS is shown large with "switch back to Cruciform" copy; the confirm happens on Cruciform.

**Same-phone pairing is ONE TAP (voidbind-kmp ADR-0008).** When the invite arrived by that
deep link — and only then — this app, the moment its relay commit is posted and the SAS is
derived, fires **`cruciform://pair-joined?session=&dev=<our ed25519 device key>&sas=<our
SAS>`** (`device/CruciformPairCallback` + the `CruciformAnnouncer` seam on
`PairingCoordinator`). That is a LOCAL intent the relay cannot touch, so Cruciform can
compare our key + SAS against what the relay revealed and settle the man-in-the-middle
check **between the apps** — it then asks one question behind its biometric and there is no
code for the human to read. This side goes straight to awaiting the admission
(`CompareSas.handedOff`), keeps the SAS in state, and the Enrol screen **reveals it as a
fallback after ~20 s** if Cruciform never comes back (an older build, a refused launch) —
a report nothing takes leaves the flow exactly as it was. Cruciform then opens
**`heyarr-mobile://pair-done?session=…`** to land the user back on the Device tab, enrolled;
that link carries the session id only and is a navigation hint, never evidence. **A scanned
or pasted invite keeps the human SAS comparison** — there is no local channel to another
device.

**The pipeline runs in an app-scoped holder, not the ViewModel.** `device/PairingCoordinator`
(pure Kotlin, unit-tested state machine, keyed by the invite's relay **session id**) lives in
`HeyarrApp.pairing` on an app-wide scope and drives join → SAS → human gate → admission →
`POST /enrol` through the `PairingSteps` seam (`device/DevicePairingSteps` = the library's
`DevicePairing` + `DeviceKeyring.saveAdmission` + `EnrolClient`); `AppViewModel.enrolState`
only *projects* it. While a session is live a **foreground service** ("Pairing with
Cruciform…", `device/PairingForegroundService`, `dataSync`) keeps the process alive, so the
same-phone dance — the user switching to Cruciform to create the key / compare / confirm —
cannot kill the relay poll. The library's `RelayClient` gives up on a peer slot after a fixed
**60 s** (the 401 polls at 150 ms the node's relay log showed); `device/PatientRelayTransport`
stretches each poll to the relay session **TTL (10 min)** and surfaces the deadline as the
library's own `RelayTimeout`, so `TIMEOUT` stays distinct from `UNREACHABLE` / `REJECTED` /
`PROTOCOL` (`PairingFailure`, titled on the screen). The Enrol screen shows a countdown while
Joining and keeps the SAS up (with the countdown) while awaiting the admission after "Codes
match". A `PendingPairing` record (invite tuple only — the handshake state is not
serialisable and relay slots are write-once) is persisted for the life of the session, so a
return after a **process death** reports INTERRUPTED / EXPIRED ("start again in Cruciform")
instead of re-joining a dead session; re-firing the same link while live is a no-op.

## voidbind-kmp is consumed as the published `voidbind-client` artifact

`one.rarebit.voidbind:voidbind-client` (currently **0.9.0**, pinned once for every module in
`gradle/libs.versions.toml`) from GitHub Packages (private; needs a
`read:packages` token — `settings.gradle.kts` reads `gpr.user`/`gpr.token` gradle
properties or `GITHUB_ACTOR`/`GITHUB_TOKEN`; CI passes its own token). The library's
minSdk is 33, so ours is too. **Do not re-derive any Voidbind wire format here** —
`LoginQr`, `Cert`, `Invite`, `DevicePairing`, `RelayClient`, `MiniJson`, `Base64Url`,
(since 0.5.0) `MembershipOp` / `Membership.{evaluate,merge}` / `Admission`, and (since
0.4.0) the `Device`-scheme `auth/` trio — `PossessionProof`, `DeviceCredential`,
`DeviceAuthPolicy` — are the library's; the app keeps only the golden vectors. For a local
composite build against an unpublished voidbind-kmp change, see the commented
`includeBuild` in `settings.gradle.kts`.

## Enrolment (device/) — this phone is the NEW device, joining a member's invite

`device/DeviceKeyring` owns the keys: the sealed Ed25519 signer (`DeviceKeyStore`, alias
`heyarr-device.authorising` — the 1-hour-window key of heyarr-core#444, a distinct alias
from the original `heyarr-device` so a phone with the old key **re-enrols** once, Path A;
biometric-gated via `device/BiometricGate` — hence `MainActivity` is a
`FragmentActivity`), the X25519 enc key sealed at rest by `device/SealedSecretStore`, and
the stored **admission** — `cert.<alias>.token` (the admitting op = credential token) plus
`ops.<alias>.json` (the replica; `knownOps()` always folds the own op back in). Under
voidbind-client 0.5.0 (ADR-0005) a pairing invite is **v3** — `voidbind:pair?v=3&…&usr=` —
and only a *member* device can mint one (the responder judges the initiator's membership
under `usr` before any SAS exists), so this phone never opens the relay session itself:
`device/EnrolScreen` + `AppViewModel.joinPairing` **join** the invite Cruciform's "Add a
device" (another phone) or the Mac's `voidbind pair-initiate` rendered — **scanned with the
camera** (`device/QrScanner`: CameraX + ML Kit, the same stack as voidbind-kmp's
androidApp; CAMERA runtime permission) or pasted — gated by `device/PairInvite` (the
library's `Invite.decode`, never a re-derived parser; non-invites are refused with a reason
and the scanner keeps looking). `DevicePairing(http, identity, clock)` runs the handshake,
the screen shows the 7-digit SAS, and on "codes match" `confirm` yields an
`Admission{op, ops}` — **both persisted** (`DeviceKeyring.saveAdmission`). Then
`device/EnrolClient` posts `POST /enrol {cert: <op>, proof, name, ops}` (heyarr-core
ADR-0067/0068 — `ops` = `MembershipOps.presentable`; a node that still refuses the field
with a 400 is retried once without it) and, if the route is absent,
`POST /api/v1/identities/devices` (admin) — surfacing the op for an operator when neither
works, never pretending. **Known server gaps:** `POST /enrol` taking `ops`
and `GET /membership/{usr}` are heyarr-core PR #426 (ADR-0068) — until it merges the
app's fallbacks (retry without `ops`; 404 = nothing learned) carry it; a device is
read-scoped until an admin grants its key (`POST /api/v1/session/management-grants
{device_key}`, ADR-0065); `POST /api/v1/devices` is a playback profile, not identity.

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
pinned **byte-for-byte** to heyarr-core's parity vectors (copied into `app/src/test/resources/`;
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
app/src/main/java/one/rarebit/heyarr/mobile/
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
app/src/test/…  pure-JVM unit tests (no Android runtime) — including the desktop's MediaThemeTest (table + AA contrast),
                McpClientTest, McpModelsTest (rule codes verbatim, the typed door over the fixtures), FollowedSourceFeedMappingTest,
                LibraryStatusTest, SeriesTest, VariantsTest, DecisionsTest, RoutesTest (SearchGrouping/ExternalParsers are tested in `:core`)
.github/workflows/android.yml   CI: testDebugUnitTest + assembleDebug on ubuntu-latest
```

## Build / test

```sh
./gradlew :androidApp:testDebugUnitTest   # unit tests — the acceptance bar, CI-run
./gradlew :androidApp:assembleDebug       # debug APK
```

Run from the repo root. `:androidApp` is only part of the build when an Android SDK is
detected (see `settings.gradle.kts`).

Nothing builds on the laptop: push the branch and let CI (`android.yml`) run
`testDebugUnitTest` + `assembleDebug` — that is the acceptance bar. (A native run needs a
JDK 17+, an Android SDK with API 35 and a GitHub token with `read:packages` for the
private `voidbind-client` artifact.)

Toolchain (matches `allthing-android` / `voidbind-kmp`, proven-green): **Gradle 8.9, AGP
8.7.3, Kotlin 2.3.20**, compileSdk 35, minSdk 33, JDK 17. `local.properties` (`sdk.dir=…`) is
git-ignored; CI provisions the SDK.

## What's phone-gated (deferred, can't be CI-proven)

On-device playback **acceptance** — the Media3/ExoPlayer player against `/blobs/.../content`
now ships (`playback/`), but a real codec decoding and a scrub's live 206 range reads only
prove out on a device; the `/playback` negotiation (`PlaybackClient.plan`) is wired but keyed
on an enrolled `device_id` — on-device personal-state **decrypt** (Keystore/StrongBox X25519 unwrap + AEAD) and the local
Personal MCP, **device-cert login** (in-enclave Ed25519 possession proof + enrolment), QR
**bitmap** rendering, and choosing whether to ship the Subsonic reach. See the README's
follow-ups. **Keep CI green** — unit tests + `assembleDebug` are the bar; anything needing a
real device stays a device-side follow-up, not a scaffold blocker.
