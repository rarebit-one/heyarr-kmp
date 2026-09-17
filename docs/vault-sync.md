# Vault sync (W4) — desktop designated-folder daemon

Status: **software tier complete** — the desktop daemon builds, resolves custody, and runs
end to end (W4.0–4.6 landed + CI-green); the only remaining step is W4.7, the non-automatable
device enrolment (Cruciform approval on the phone). Cruciform custody still deferred.
Owner: desktop client. Server side (heyarr-core) is complete and on `main`.

## What this is

A Dropbox-style **designated-folder full sync** for the desktop client: one local
folder ⇄ one heyarr **vault** (an admin-blind Tier-3 EncryptedSpace, ADR-0021). Files
are framed + encrypted **on this device** before they ever leave it; the heyarr server
and every peer store only opaque ciphertext. Sync between machines is convergence over a
per-space CRDT, replicated BR⇄Cove by device-supplied placement pins.

This is decision (4) from the personal-drive design: **designated-folder full sync**, no
VFS / on-demand (deferred). Confidentiality is Tier-3 admin-blind (decision 1); namespace
is a path-keyed map CRDT with conflicted-copy (decision 3); version history + trash
(decision 6).

## Where the engine runs — decided

**Kotlin-native, in this repo.** The desktop app is already a device-enrolled client with
its own Kotlin device identity (Ed25519 + X25519 via voidbind `DeviceIdentity`), a
software secret store, and an enrolment flow. The vault device key is *that same*
identity. The engine therefore lives in Kotlin and reaches heyarr-core purely over HTTP —
no Go sidecar, no second device identity, no JNI.

Rejected: a local Go `heyarr vault` sidecar. It would fork device identity on the laptop
and mean shipping/managing a Go binary beside the app.

## Custody now vs later

- **Now (software):** the space key is unwrapped with `VoidbindEncryption.unwrap(wrapped,
  seed)` using the device's software-held X25519 seed, sealed at rest by the existing
  `DesktopSecretStore` (`KeyTier.SOFTWARE`). Admin-blind holds — the key never leaves the
  laptop.
- **Later (cruciform):** custody swaps behind the same seam with **no engine change**.
  Prerequisite (a real gap): voidbind-kmp has **no** `UnwrapWithAgreement` / `AgreementFunc`
  ECDH-injection seam — voidbind-go PR #38 was never ported to Kotlin. Cruciform-on-desktop
  needs that seam added to voidbind-kmp first (mirrors Go #571). Tracked separately; not on
  the W4 critical path.

## What voidbind-kmp already gives us (do NOT reimplement)

All in `commonMain`, JVM-ready, byte-identical to voidbind-go with a Go-generated golden
KAT (`voidbind-client:0.7.0`, already a `:core` dependency):

- `VoidbindEncryption.encryptChange(spaceKey, plaintext)` / `decryptChange(...)` —
  XChaCha20-Poly1305, 24-byte random nonce prepended, **no AAD**. THE per-frame primitive.
- `VoidbindEncryption.seal(spaceKey, recipientPub)` / `unwrap(wrapped, seed)` — X25519 space-key wrap/unwrap.
- `VoidbindEncryption.newSpaceKey()`; `DeviceIdentity` (+`generateEncryptionKey()`).
- `KeyRef` (`x25519:<hex>` / `ed25519:<hex>`), `Base64Url` (Go `RawURLEncoding`).

Space key = a raw 32-byte `ByteArray` (no named type). We wrap it in a thin `SpaceKey`
value class on our side.

## The one new primitive: BLAKE3

There is no BLAKE3 anywhere in Kotlin (voidbind-kmp, heyarr-kmp, or cryptography-kotlin
0.6.0). We must compute it to form `blake3:<hex>` blob hashes and content-addressed
`change_id` / `snapshot_id` (the server re-verifies ids → `ErrIDMismatch` 400 otherwise).

**Decision:** vendor a small, single-file pure-Kotlin BLAKE3 into `:core`
(`core/.../crypto/Blake3.kt`), consistent with the repo's no-library stance (hand-rolled
JSON, no Ktor). KAT-tested against Go-generated golden vectors + the official BLAKE3 test
vectors. Reversible: swap for a maven dep later if desired.

## Wire contracts (heyarr-core, verified on `main`)

Frame codec (mirror of `internal/personalstate/vaultframe`, ADR-0097):
- Version 1; `FrameSize = 1<<20` (1 MiB plaintext/frame).
- 21-byte header per frame = `version(1) ‖ file_id(16) ‖ frame_index(uint32 BE, 4)`.
- Sealed frame = `encryptChange(spaceKey, header ‖ data)` → `nonce(24) ‖ ct ‖ tag(16)`.
  Content blob = the sealed frames concatenated; its `blake3:<hex>` is the blob id.
- Manifest JSON: `{version, file_id(hex), frame_size, frame_count, plaintext_size,
  content:"blake3:<hex>"}`, itself sealed via `encryptChange` → its own blob.
- `FrameByteRange(i)`: full sealed frame = 24 + 21 + 1MiB + 16 bytes; last is shorter.

Drive CRDT (mirror of `internal/personalstate/crdt/drive.go`):
- `DriveChange{op,path,blob?,size?,mtime?,at,writer,base}` — exported JSON is the
  cross-language contract. `path` normalised NFC / forward-slash / cleaned / no leading
  slash / case-sensitive. Total order = `(at, writer)`.
- `Drive`: `Put/Delete → DriveChange`, `Apply` (skips invalid), `Resolved()` (winner +
  relocated conflicted copies — the tree we render), `Retain(policy)` (reclaimable blob
  ids), `Snapshot()` (deterministic canonical JSON — content-addressable).

HTTP endpoints (all `/api/v1`, bearer/Device auth):
- `PUT /vault/blobs/{hash}` — octet-stream ciphertext body → 201 `{hash,size}` (write scope).
- `GET/HEAD /blobs/{hash}/content` — ranged reads (206/Range) for frame fetch.
- `POST/DELETE /vault/placements` — `{blob_hash,peer_id}` cross-site replication pins.
- `GET /spaces/{id}/snapshot`, `GET /spaces/{id}/changes`, `POST /spaces/{id}/changes`
  (`{space_id,change_id,parents,ciphertext(b64)}`), `POST /spaces/{id}/snapshots`,
  `GET/POST /spaces/{id}/keys`, `POST /spaces` — the encrypted state-sync pipe.
- Encrypted change/snapshot ids are `blake3:<hex>` of the ciphertext (client computes them).

## Build order (each a CI-verified PR)

- **W4.0 — BLAKE3** (`:core` `crypto/Blake3.kt`): DONE — vendored pure-Kotlin port of the
  reference_impl, KAT-tested vs Go across single/multi-chunk sizes.
- **W4.1 — frame codec** (`:core` `vault/VaultFrame.kt`): DONE — header + `encryptChange`
  wrapper + manifest + `frameByteRange`/`openRange`/`openAll`, seal names blobs via BLAKE3.
  Proven on Go golden vectors. Repeated same-frame / random-access decrypt works since
  the voidbind-client 0.8.0 JVM fix (see "Resolved" below).
- **W4.2 — drive CRDT** (`:core` `vault/DriveCrdt.kt`): DONE — merge, heads/versions,
  byte-identical snapshot, NFC paths, conflicted-copy relocation (`resolved()`), and the
  retention/GC view (`retain()`). Proven on Go vectors (tree, convergence, snapshot,
  resolved trees, retention). Still deferred, as in Go: a dotted version vector for
  multi-way put-vs-delete.
- **W4.4 — vault HTTP client** (`:composeApp`): DONE — `VaultSpaceClient` (encrypted
  changes/snapshot/keys/pin/unpin over `HttpTransport`, ids via BLAKE3) + `VaultBlobStore`
  (binary PUT + ranged GET, `fetchFor` → `VaultFrame.Fetch`). Fake-transport tested. Added
  a `delete`-with-body overload to `HttpTransport` for unpin.
- **W4.5 — sync engine** (`:composeApp`): DONE. Decided semantics: safe deletes
  (index-proven), conflicted copies written to disk, hybrid mtime/size→hash detection;
  `WatchService` + periodic-scan safety net, atomic temp-then-rename writes, frontier
  cursor. The pure `reconcile()` core (`:core`), `LocalScanner` (streaming BLAKE3 +
  hybrid), `SyncIndex` store, and the `syncOnce` capstone (two-device round-trip test)
  landed in W4.5a–d. **W4.5e** added the daemon orchestration: `VaultSyncController` (one
  cancellable Job — `syncOnce` off `Dispatchers.IO` via `runInterruptible`, parked on a
  `SyncChanges` folder-watch OR the periodic tick, a failed pass → `Error` and the loop
  keeps going, passes serialised by a `Mutex`), the `VaultSync` seam, and `WatchedFolder`/
  `PeriodicOnlyChanges`. Also fixed a scanner bug the daemon would make live — `.sync-trash/`
  is now ignored so a trashed file isn't re-uploaded.
- **W4.3 — space-key custody**: DONE (software tier). `VaultCustody.openOrBootstrap` unwraps
  the wrapped space key via the desktop keyring (`DeviceIdentity.encPrivateKey` →
  `VoidbindEncryption.unwrap`), and self-bootstraps the first device — mint the space key,
  wrap for this device + the recovery recipient (when enrolment provisioned one), `POST
  /spaces` (kind `personal`; the server's closed kind set has no dedicated vault/drive kind).
  A configured-but-unreadable space is reported "not ready" and never re-minted. Added
  `VaultSpaceClient.createSpace`, a `VaultKeys` seam, and
  `DesktopDeviceKeyring.recoveryRecipient()` (persisted from `/enrol`). Cruciform custody
  swaps in later behind the same seam (still gated on the voidbind-kmp `UnwrapWithAgreement`
  port — see "Custody now vs later").
- **W4.6 — control surface**: DONE. `VaultService` (session-scoped) resolves custody off the
  UI thread with retry, then drives the controller; a `VaultPhase` (Off / Preparing / Running)
  fronts the per-pass `SyncStatus`. `AppSession` owns `vault`, `App.kt` resumes on launch and
  shuts the daemon down on teardown, and the Settings **Vault panel** has a folder picker,
  start/stop, and live status (phase, last ↑↓⌫ stats, error, space id).
- **W4.7 — enrolment tie-in**: REMAINING — the one non-automatable step. Custody resolves
  against a real node only once this device is voidbind-enrolled (Cruciform approval on the
  phone); until then the panel sits in "getting ready". Everything else runs the moment the
  device is enrolled.

## Verification

Nothing builds on the laptop and `voidbind-client` needs a `read:packages` token, so
Kotlin verification is **CI-gated** (`desktop.yml`): push → `gh run watch`, like
heyarr-mobile. Go-side golden vectors are generated locally (heyarr-core builds on the
laptop). CI has no live network — every W4 test uses fixtures/vectors, never a live node.

## Config / state

New `FileSettingsStore`-style JSON under `$XDG_CONFIG_HOME/heyarr-desktop/` for the
folder⇄space mapping + sync cursor; secrets through the existing `SecretStore`.

## Resolved: JDK ChaCha20 nonce-reuse guard (desktop JVM)

Previously, `voidbind-client:0.7.0` built XChaCha20-Poly1305 on cryptography-kotlin
0.6.0, whose JVM provider pools the underlying `javax.crypto.Cipher`; SunJCE's ChaCha20
then refused to re-`init` it with the SAME (key, nonce) as the immediately preceding op
(`InvalidKeyException: Matching key and nonce from previous initialization`), so
decrypting the same frame twice in a row — random-access / VFS reads — threw. Full-file
`openAll` (each frame once) was unaffected.

**Fixed in `voidbind-client:0.8.0`** (voidbind-kmp #57): an `expect/actual` AEAD seam
whose JVM actual uses a fresh `javax.crypto.Cipher("ChaCha20-Poly1305")` per op,
sidestepping the pooled cipher and the per-instance guard; android/iOS keep the
cryptography-kotlin path; the wire stays byte-identical to Go (goSealKat KAT). heyarr
bumped to 0.8.0, and `VaultFrameVectorsTest.repeatedSameFrameDecryptWorks` (byte-by-byte
`openRange`) confirms random-access decryption now works on desktop JVM.
