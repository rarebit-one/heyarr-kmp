# Vault sync (W4) — desktop designated-folder daemon

Status: **in progress** (software-key custody; cruciform custody deferred).
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

- **W4.1 — frame codec** (`:core` `personalstate/vaultframe`): header + `encryptChange`
  wrapper + manifest + `FrameByteRange`/`OpenRange`. Deps: BLAKE3 (W4.0). Tests:
  cross-decrypt round-trip vectors from Go + byte-pin on header layout.
- **W4.0 — BLAKE3** (`:core` `crypto/Blake3.kt`): vendored, KAT-tested. (Lands first, with W4.1.)
- **W4.2 — drive CRDT** (`:core` `personalstate/crdt`): port + hand-rolled JSON (JsonScan/
  JsonWrite). Tests: byte-equality snapshot golden vectors from Go + reorder-convergence.
- **W4.3 — space-key custody**: unwrap via `DeviceIdentity` + `DesktopSecretStore`; a
  `SpaceKey` value type; `Unwrapper` seam (software impl now, cruciform later).
- **W4.4 — vault HTTP client** (`:composeApp`): typed clients for blob PUT (binary upload
  seam, extends `BlobDownloader`), ranged blob GET (`BlobFetcher`), changes/snapshot/keys/
  placements — shaped like `LibraryClient`/`HeyarrApi`.
- **W4.5 — sync engine**: local folder scan + `java.nio.file.WatchService` (jvmMain) ⇄
  drive CRDT reconcile loop; conflicted-copy materialisation; retention. Modeled on
  `AppSession.startHeartbeat`/`io`.
- **W4.6 — control surface**: a Vault settings screen (folder picker, space, start/stop,
  status, conflicts).
- **W4.7 — enrolment tie-in**: wrap this device into the vault space. Needs owner approval
  on the Cruciform phone — the one non-automatable step.

## Verification

Nothing builds on the laptop and `voidbind-client` needs a `read:packages` token, so
Kotlin verification is **CI-gated** (`desktop.yml`): push → `gh run watch`, like
heyarr-mobile. Go-side golden vectors are generated locally (heyarr-core builds on the
laptop). CI has no live network — every W4 test uses fixtures/vectors, never a live node.

## Config / state

New `FileSettingsStore`-style JSON under `$XDG_CONFIG_HOME/heyarr-desktop/` for the
folder⇄space mapping + sync cursor; secrets through the existing `SecretStore`.
