# Harmonizing heyarr-mobile and heyarr-desktop (toward KMP)

**Status:** Living document · **Started:** 2026-09-12
**Audience:** anyone working on either client, and future-us doing the KMP merge.

## Why this exists

The two first-party clients — `heyarr-mobile` (Android/Media3) and `heyarr-desktop`
(Compose Multiplatform / JVM, libmpv) — were built to eventually **share a Kotlin
Multiplatform `commonMain`**. `heyarr-mobile`'s state layer, API door, MCP client,
design tokens and much of the playback-planning code were **ported from
`heyarr-desktop` on purpose**, so the two are already close. This doc records the
*real* divergence map (not the aspirational READMEs), the parity gaps worth closing,
and the concrete `commonMain` extraction plan — so feature work lands in the shared
shape instead of drifting further apart.

## The premise, corrected

"Mobile hasn't been kept up to date with desktop" is only half true. The two have
**diverged feature-by-feature**, not lagged uniformly:

| Ahead on mobile | Ahead on desktop |
|---|---|
| Device enrolment + Cruciform pairing (same-phone one-tap) | Buffered range on the player scrubber ✅ *(closed this session)* |
| On-device personal-state CRDT engine (playlists, starred, history, reading positions), decrypt-on-device | ADR-0089: a series *want* is a *follow* (one door) |
| A real Readium reader with encrypted position sync | (that's the whole list) |
| Re-plan on a live Media3 codec failure (`onIssue` → re-`plan()` once) | |
| Playlists / starred / history UI | |

Desktop is the **newer** repo (scaffolded 2026-09-07; ~14 commits) that got a burst
of playback-UI polish on 2026-09-11. Mobile is the **more mature product** (56 merged
PRs) but missed that last polish batch. So the work is *bidirectional*: mobile catches
the polish; desktop should later catch mobile's personal-state / reader maturity.

## Feature parity matrix (the Sep-11 desktop batch)

| Desktop change | On mobile? | Action |
|---|---|---|
| #11 playback **plan / transcode** (`POST /playback/plan` → direct\|stream, ADR-0069) | **Yes, and better** — `PlaybackClient.plan()` + `PlaybackCoordinator`, plus re-plan on codec failure | none — desktop should adopt *mobile's* re-plan |
| #13 pin scrubber to source runtime for a transcode stream | Partial — `PlaybackTarget` carries restart-seek; duration basis is ExoPlayer's | verify the fMP4 duration basis; low priority |
| Buffered range on the scrubber | **Was absent** | **Closed** — PR #57 (this session) |
| #9 download progress (bytes) on a want | Absent by design — honest "no percentage" notice | **Do not port.** See "Download progress" below |
| #12 series **want is a follow** (ADR-0089) | Absent — mobile shows three separate doors | **Recommended** — see below |
| #8 a want's real scope (not hardcoded "work") | **Already present** — `DesiredItem.scope` (`work`/`edition`) | none |
| #7 placeholder e-book reader | **Mobile has a *real* reader** (Readium 3) | none — desktop should catch up |
| #6 external subtitle sidecars | **Present** (#56) | none |

### Download progress — a finding, not a gap

Desktop #9 parses `acquisition.bytes_total` / `bytes_done` from `GET /desired` and shows
`N%`. But heyarr-core's `GET /desired` acquisition view
(`internal/api/resources/desired.go`, `acquisitionSelect = a.phase, a.managed,
a.content, a.placement, a.detail`) **does not surface bytes** — they live in the
downloads/transfers layer and are not joined in. So desktop's percent is inert on the
current server, and **mobile's "the node reports state/phase, not a percentage" notice
is the honest, correct state.** Do not port #9. If per-want progress is wanted, the fix
is server-side first (join transfer bytes into the desired view), then *both* clients
adopt it together.

## Already harmonized (keep it that way)

- **Design tokens** (`theme/Tokens.kt`) and the **media-accent table** (`theme/MediaType.kt`)
  are **identical** across both repos (verified: every accent hex matches; only expected
  platform layout constants differ — desktop `navWidth 92dp` / `compactBreakpoint 900dp`
  vs mobile `railBreakpoint 600dp`). Both self-host Rubik (from `:ui`).
- **The API door** `heyarr/HeyarrApi.kt` shares **45 identical method names**. The one
  divergence: desktop has `playbackTarget(...)` (plan resolution inlined in the API);
  mobile factors plan resolution into `playback/PlaybackClient` + `PlaybackCoordinator`
  (the richer design). **Mobile's is the better `commonMain` home** — desktop should
  adopt it.
- **MCP over plain HTTP**, stateless `tools/call` to `/api/v1/mcp`, no `initialize`
  handshake — same in both.
- **Hand-rolled JSON** (`net/JsonScan` read, `mcp/JsonWrite` write; `JsonWrite` drops
  nulls so an unknown attribute reads as *undetermined*). The org's deliberate
  no-serialization-library stance. `kotlinx.serialization` is used *only* for typed nav
  routes on mobile. Keep this stance aligned across both.
- **`HttpTransport`** interface shape is identical and platform-neutral (desktop's
  `JdkHttpTransport` vs mobile's `OkHttpTransport` are the only actuals).
- **Refusals-as-values**: `McpResult.Ok/Refused`, rule codes quoted verbatim, tool named
  in the toast — same contract in both.

## The real divergences to reconcile for KMP

1. **Playback-plan location.** Desktop: `HeyarrApi.playbackTarget()`. Mobile:
   `PlaybackClient`/`PlaybackCoordinator` (+ capability declaration, +restart-seek,
   +re-plan-on-issue). → **Converge on mobile's shape** in the shared module; delete
   desktop's inline version.
2. **Series want vs follow (ADR-0089).** Desktop collapsed them (want-on-a-series =
   follow, detected via item-scoped wants). Mobile keeps three explicit doors. →
   Adopt ADR-0089 on mobile (see backlog).
3. **State layering.** Desktop: plain-Compose `AppSession` + per-screen state holders.
   Mobile: the *same* plain-Compose `AppSession`, wrapped in a `SessionHolder`
   `ViewModel` keyed on `(node, credential)` for config-change survival, under an
   `AppViewModel` for the pre-session (login/enrol) flows. → The `AppSession` core is
   already shared-shaped; in KMP the ViewModel wrapper stays `androidMain`, the desktop
   uses the raw holder. **No reconciliation needed beyond keeping `AppSession` pure.**
4. **Player backend.** libmpv/JNA (desktop) vs Media3/ExoPlayer (mobile). Irreducible.
   Share the `Player`/`PlaybackTarget`/plan seam; keep the backends per-platform.

## KMP `commonMain` extraction tiers

From a full audit of both trees (JVM/Android-specific APIs flagged):

**Tier A — `commonMain` now** (pure Kotlin over the `HttpTransport` interface; only
small `expect/actual` needed for URL-encode + an `IOException` alias):
`net/` (transport interface + `JsonScan`/`JsonWrite`/`JsonEscapes`), `mcp/`
(`McpClient`, `McpModels`, errors), `heyarr/` (`HeyarrApi`, `RestModels`, `Telemetry`),
most `library/` / `music/` / `search/` models, `theme/Tokens` + `theme/MediaType`,
`state/LibraryStatus`, `state/SearchGrouping`, and the `Player` / `BlobDownloader` /
`ExternalOpener` / `SettingsStore` **interfaces**.

**Tier B — `commonMain` with a dispatcher injected** (they already take a
`CoroutineScope`; swap `Dispatchers.IO`/`runInterruptible`/`System.nanoTime` for
injected/`expect` equivalents): `state/AppSession`, `SearchController` (done: it
lives in `:core`'s `state/` with an injected I/O dispatcher), the playback-plan coordinator.

**Tier C — per-platform `actual`s:** HTTP transport (`java.net.http` vs OkHttp),
blob downloader, settings store (file+XDG vs SharedPreferences), artwork decode+cache
(Skia vs Android), external-open (`xdg-open` vs Intent).

**Tier D — stays platform-only, no sharing:** the libmpv/JNA embedded player +
`VideoSurface` Skia draw + AWT key/window handling (desktop `jvmMain`); Media3
`VideoSession`/`SessionAudioPlayer`/`HeyarrDataSource`, the Readium reader, and the
device keystore/biometric stack (mobile `androidMain`, wire formats already delegated
to the shared `voidbind-client` KMP artifact).

**Recommended first extraction:** Tier A into a new `:shared` KMP module consumed by
both `composeApp` and `app`. It is the lowest-risk, highest-symbol-count win and both
copies are already pure and unit-tested against the same fixtures. Do it as a *move*,
not a rewrite; the hand-rolled JSON + `HttpTransport` seam make it mechanical.

## Backlog (prioritized)

1. **[shipped]** Buffered range on the mobile scrubber — PR #57.
2. **ADR-0089 on mobile — series want is a follow.** In `DetailScreen.WantSeasonsPanel`:
   detect "following" from **item-scoped** wants (`wants.any { it.scope == "item" }`,
   desktop's proven signal); reframe "Want the whole series" so its copy says it
   establishes a standing subscription (which `want_content` on a work-scoped series now
   does server-side, ADR-0089); decide whether to keep the explicit "Follow on TVDB"
   button (it's a direct-id path that skips `Discover`; desktop dropped it, but it's a
   useful fallback on a node with no metadata provider — **a UX call for Jaryl**).
   Extract the follow-state decision into a pure, unit-tested helper (also good KMP prep).
3. **Extract Tier A into a `:shared` KMP module** (the real assimilation step).
4. **Converge playback-plan** on mobile's `PlaybackClient` shape; delete desktop's
   inline `playbackTarget`.
5. **Buffered band on the *audio* scrubbers** (`NowPlayingBar`, `AudioQueueScreen`) —
   the same treatment, audio path (deferred from PR #57 to keep it scoped).
6. **Desktop catches mobile:** real reader (vs placeholder), and the re-plan-on-codec
   -failure behaviour.

## Guardrails for new work

- A screen/feature that exists on both apps should be **the same shape** — same tokens,
  same `HeyarrApi` method, same refusal handling. If you must diverge, note it here.
- Put new below-the-UI logic in the Tier-A/B shape (pure, over `HttpTransport`,
  hand-rolled JSON) so it moves to `commonMain` unchanged.
- Never invent an endpoint; if the server doesn't surface it, fix the server first and
  adopt it on both clients together (see the download-progress finding).
