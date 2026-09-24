# heyarr Android client (`:androidApp`)

(Formerly the standalone `heyarr-mobile` repo; now a module of `heyarr-kmp`.)

The first-party **Android client for [heyarr](https://github.com/rarebit-one/heyarr-core)** —
the self-hosted media platform. Voidbind **QR** sign-in, a native **library browse**, and the
seams for **device-side personal state** (decrypt-on-device) that make this the *product*
client rather than a generic Subsonic app.

It wears the **Heyarr Desktop design language** — the same tokens, media-keyed accents
(shared through `:ui` / `:core`), the self-hosted Rubik face and components
as the desktop client (`:composeApp`) in this repo — on a
phone's bottom bar (a rail on a tablet), consumption first with curation one tab away.

## Screens

| Screen | heyarr surface |
|--------|----------------|
| **Home / Discover** — media-mixed spotlight hero, Continue (the node's own unfinished sessions, labelled as such), Starred / Recently played (this phone's decrypted state, labelled as such), per-type rails, Wanted-but-missing, Could-be-better, Following | `GET /works`, `search_content` per type, `GET /consumption/continue`, `get_missing_content`, `get_upgrade_candidates`, `list_followed`; Discover quotes `discover_content`'s refusal when the node has no provider |
| **Search** — one box, every kind at once, sections stream in as each answer lands, type chips, recent searches kept locally and labelled so | `search_content` × {movie, series, music, book} + an untyped call for episodes, in parallel; followed sources matched client-side |
| **Detail** — **Watch**: art, synopsis (the node's, else a keyless public source labelled "via …", else an honest line), seasons + episodes parsed from files with `-thumb.jpg` sidecars, not-held rows with "Look for it", tracks, book files, feed archive. **Curate**: status, held files with verdicts, indexer candidates + Acquire, score a release, health/replicas, captions & artwork, "also catalogued as" | `GET /works/{id}`, `GET /works/{id}/assets`, `get_content_satisfaction`, `GET /desired/{id}/candidates`, `explain_release`, `search_releases`, `acquire_release`, `get_replica_status`, `verify_blob`, `get_external_ids`, `play_here`, `monitor_content`, `want_content`, `POST /desired` (scope=edition) |
| **Player** — in-app ExoPlayer with a persistent now-playing bar across screens (play/pause, ±10 s, seek, title), fullscreen, captions as a menu with language names, cast to a renderer | `/api/v1/blobs/{hash}/content` (Range/206), `POST /playback/plan`, `play_here` |
| **Library** — grid/list, type + status filters, plus **Downloads** (wants in flight: state/phase/detail, and the job queue — the node reports state, not a percentage) and this phone's **Playlists** | `GET /works` + the want index; `GET /desired`, `GET /jobs` |
| **Missing** — bulk search-now / monitor on-off, Want by title; Want a season lives on the series | `get_missing_content`, `get_upgrade_candidates`, `search_releases`, `monitor_content`, `want_content` |
| **Cast** — renderers, live status, transport | `list_renderers`, `playback_status` (polled), `control_playback` |
| **Settings** — connection + telemetry (`/session`, `/providers`, `/capabilities`, peers, libraries, jobs), device enrolment, followed sources, peers, appearance (adaptive accents, reduce motion, public cover art) | `list_followed`, `follow_source`, `unfollow`, `get_peer_status`, `sync_peer`, `GET /quality-profiles` |

**Library status** (In library · Wanted · Missing · Not tracked) derives only from real
want state (`GET /desired`). **Want** is optimistic: the card flips at once and rolls back
with the tool's refusal on failure. Rule codes and refusal text are quoted verbatim and the
tool is named. The MCP surface cannot see personal state; the only personal rows are the
ones this phone decrypts itself (`personalstate/`), and they say so.

## What's here

| Area | State | Where |
|------|-------|-------|
| **Auth** | Two credential shapes — primary `Authorization: Device <cert>~<proof>` and bootstrap `Bearer` (QR session). Header formatting real + tested; the in-enclave proof is phone-gated. | `auth/` |
| **Login** | Voidbind **QR** seam (heyarr's channel). Real initiator `POST /login` + poll `GET /login/{id}` → Bearer token. QR bitmap render + device-side approve are follow-ups. | `login/` |
| **Library** | `LibraryClient` against the native `GET /api/v1/works`, auth header + tolerant parse, wired to a browse list. Subsonic reach is a documented stub. | `library/` |
| **Playback** | A **Media3/ExoPlayer** player streaming `/api/v1/blobs/{hash}/content` over an OkHttp-backed data source with the auth header + native **Range/206** seek (the M10 win); transport controls for audio **and** video; wired to tap-an-item-to-play. The URL/plan/target logic is unit-tested; a real codec decoding on a device is the phone-gated half. | `playback/` |
| **Personal state** | `/api/v1/spaces/{id}/{keys,changes,snapshot}` fetched as **opaque ciphertext**; a fail-closed `Unwrapper` decrypt-on-device seam. **No crypto in this repo.** | `personalstate/` |

## Relationship to the other repos

- **[`rarebit-one/heyarr-core`](https://github.com/rarebit-one/heyarr-core)** (PUBLIC / AGPL) —
  the server this talks to. The **contract** this client builds against is
  `heyarr-core/docs/design/mobile-client.md` (device auth ADR-0048, personal state
  ADR-0049/0051). **No server change is needed** to add this client.
- **[`rarebit-one/voidbind-kmp`](https://github.com/rarebit-one/voidbind-kmp)** — THE Voidbind
  authenticator app (**Cruciform**, `one.rarebit.cruciform`) + the shared `voidbind-client` (`WebLoginClient`, `LoginQr`, `WebLogin`,
  `LoginApproval`). This app delegates login approval to that authenticator.

### voidbind-kmp consumption

This app depends on the **published** shared client,
`one.rarebit.voidbind:voidbind-client` (currently 0.9.0, pinned in `gradle/libs.versions.toml`;
GitHub Packages, private — a token with
`read:packages` is required even for a same-org read). `settings.gradle.kts` reads
`gpr.user` / `gpr.token` from `~/.gradle/gradle.properties`, or `GITHUB_ACTOR` /
`GITHUB_TOKEN` from the environment; CI passes its own workflow token. Locally:

```sh
GITHUB_ACTOR=<your login> GITHUB_TOKEN=$(gh auth token) ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

`login/` is now a thin façade over the library's `WebLoginClient` / `LoginQr`, and the
device credential is real: the library's `auth/{PossessionProof, DeviceCredential,
DeviceAuthPolicy}` mint heyarr's possession proof byte-for-byte (the Go-minted golden
vectors stay pinned in this repo's `PossessionProofTest`) and sign it with the phone's
hardware-sealed key (`device/DeviceKeyring` → voidbind-client `DeviceKeyStore`); the
app reuses one proof for ~1 h (`reuseForSeconds`) and re-mints + retries once on a 401.
Since 0.5.0 (voidbind-kmp ADR-0005) the credential token is this device's **admitting
op** and the app also holds the identity's membership **ops** (its replica): they ride
every Device request as `Voidbind-Membership` (`device/MembershipOps` picks ≤ 64) and
`POST /enrol {…, ops}`, and after a 401 the app re-reads `GET /membership/{usr}` so a
device another member removed shows an honest "removed" state instead of looping.

## Design system

Tokens live in one place — `theme/Tokens.kt` (surfaces, text ramp, radii, spacing,
type scale) — and the media table in `:ui`'s `MediaThemes` (over `:core`'s `MediaType`):

| Media | Accent | Card | CTA |
|-------|--------|------|-----|
| Movie | `#00935E` emerald (app default) | 2:3 | Play |
| Series | `#7C5CFF` violet | 2:3 | Play / Next episode |
| Book | `#E0A458` amber, spine shadow | 2:3 | Read |
| Audiobook | `#2DB3A6` teal | 1:1 | Listen |
| Podcast | `#C13BAD` magenta | 1:1 | Play episode |
| Music | `#FF4D6D` rose | 1:1 | Play |
| Feed / document / unknown | `#7A8598` slate | — | Open |

The accent swaps the CTA gradient, pressed/focus states, the active nav tile, progress
bars and the section underline; surfaces and text stay constant. Wrap any subtree in
`MediaScope(type) { … }` to re-skin it. Fonts are self-hosted (OFL): **Rubik** in every
type slot, shipped once by `:ui` (`ui/src/commonMain/composeResources/font`, exposed as
`HeyarrFonts`). `MediaThemeTest` pins
the table and AA contrast; `preview/Fixtures.kt` carries the live-node shapes the JVM
tests and previews share with the desktop.

## Build / test

```sh
./gradlew :androidApp:testDebugUnitTest   # pure-JVM unit tests (readers, grouping, status, series, theme, routes, credential)
./gradlew :androidApp:assembleDebug       # debug APK
```

Requires JDK 17+ and an Android SDK (API 35). Point `local.properties` at your SDK
(`sdk.dir=…`) — CI provisions it via `android-actions/setup-android`.

## Phone-gated follow-ups (deferred by design)

These need real hardware / a live server and can't be proven in CI:

- **On-device playback acceptance** — the Media3/ExoPlayer pipeline over
  `/api/v1/blobs/{hash}/content` (Range + `Authorization`) now **ships** (`playback/`), but a
  real codec decoding the stream and a scrub issuing live 206 range reads can only be proven on
  a device/emulator. The `POST /api/v1/playback` transcode/remux negotiation is wired
  (`PlaybackClient.plan`) but keyed on an enrolled `device_id`, so it goes live with device auth.
- **Device-cert login** — now **ships**: the sealed-key possession proof, the pairing
  handshake (`device/`, this phone = the NEW device joining a v3 invite that Cruciform
  or `voidbind pair-initiate` rendered) and re-mint-on-401. What is still gated on the
  *server*: `POST /enrol` taking `ops` and `GET /membership/{usr}` (heyarr-core ADR-0068,
  PR #426 — until it lands the app retries `/enrol` without `ops` and treats a 404 on
  `/membership` as "nothing learned"), or an admin registering the op
  (`POST /api/v1/identities/devices`), and write scope for a device (heyarr-core #417 +
  `POST /api/v1/session/management-grants`). Completing a pairing needs a member device
  on the other side, so it is proven on-device, not in CI. Honest key tier on
  the Nothing Phone (2a): **TEE**, not StrongBox.
- **On-device personal-state decrypt** — the real `Unwrapper` (X25519 ECDH in-enclave → HKDF →
  the space key) + AEAD decryption of opaque changes, and the local **Personal MCP** (#372/#387)
  that reads the decrypted state. **No crypto ships in this repo.**
- **Subsonic/OPDS/DLNA reach** — deciding whether to ship the compat adapters at all vs. leaning
  on the native API; `library/SubsonicClient` marks the fork as a decision, not an omission.

## Releases — signed APKs, published per tag

Debug builds are a dead end: they are signed with the throwaway debug key, so they can
never be updated in place by a real release. Every tagged version is therefore built as
a **signed release APK** and attached to a GitHub Release, which is the artefact the
phone installs.

### Cutting a release

```sh
git tag v0.2.1          # `v<major>.<minor>.<patch>`
git push origin v0.2.1
```

`.github/workflows/release.yml` picks the tag up, builds `:app:assembleRelease`, verifies the
APK with `apksigner verify --print-certs`, and publishes the Release with
`heyarr-mobile-0.2.1.apk` attached. `versionName` comes from the tag; `versionCode` is derived from it
(`major*10000 + minor*100 + patch`), so neither is ever hand-edited.

### Signing

`signingConfigs.release` reads four values, from the environment first and gradle
properties second — nothing is committed, and `*.jks` is git-ignored:

| Env | Gradle property | What |
|-----|-----------------|------|
| `RELEASE_KEYSTORE_BASE64` | `release.keystoreBase64` | the keystore, base64 |
| `RELEASE_KEYSTORE_PASSWORD` | `release.keystorePassword` | store password |
| `RELEASE_KEY_ALIAS` | `release.keyAlias` | `one.rarebit.heyarr.mobile` |
| `RELEASE_KEY_PASSWORD` | `release.keyPassword` | key password |

With none of them set the release build type is simply **unsigned** — a local
`assembleRelease` still works. CI supplies them from the repo secrets of the same
names. The keystore itself (RSA 4096, 25 years) lives at
`~/.config/rarebit-android-signing/heyarr-mobile.jks` and in **1Password → Sysadmins**.
Losing it means no user can ever update in place again.

Build a signed APK locally:

```sh
export RELEASE_KEYSTORE_BASE64=$(base64 -i ~/.config/rarebit-android-signing/heyarr-mobile.jks | tr -d '\n')
export RELEASE_KEYSTORE_PASSWORD=$(cat ~/.config/rarebit-android-signing/heyarr-mobile.password)
export RELEASE_KEY_PASSWORD="$RELEASE_KEYSTORE_PASSWORD"
export RELEASE_KEY_ALIAS=one.rarebit.heyarr.mobile
./gradlew :androidApp:assembleRelease -PreleaseVersionName=v0.2.1
```

### Getting it onto a phone

Download the APK from the Release and `adb install` it, or open the Release page on
the phone. There is deliberately no third-party app-store client in the loop.

How this is *meant* to be distributed — a self-hosted install page plus an in-app
updater that checks for and applies a new signed build — is being designed in
[`rarebit-one/heyarr-core#441`](https://github.com/rarebit-one/heyarr-core/issues/441).
Not built yet; this section is the pointer, not the plan.

## License

[GNU AGPL v3](./LICENSE) — matching `rarebit-one/heyarr-core`.
