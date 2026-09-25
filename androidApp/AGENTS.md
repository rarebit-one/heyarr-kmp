# AGENTS.md — `:androidApp` (the heyarr Android client)

The Android module of the `heyarr-kmp` monorepo. It began life as the separate `heyarr-mobile`
repo, whose name survives in the `heyarr-mobile://` deep links, the `one.rarebit.heyarr.mobile`
package and the release APK's file name. The repo-root `AGENTS.md` covers module boundaries,
the monorepo's build commands and the conventions. This file adds only what differs here.

## What this is

The first-party Android client for heyarr. It signs in via **Voidbind QR login**, browses
heyarr's native library, and holds **device-side personal state** (decrypt-on-device). The
server contract is `heyarr-core/docs/design/mobile-client.md` (ADR-0048 device auth,
ADR-0049/0051 personal state). Login approval is delegated to **Cruciform**, the Voidbind
authenticator app in `rarebit-one/voidbind-kmp`, which also ships the `voidbind-client` library.

## Invariants (each with its reason)

- **Never re-derive a Voidbind wire format.** `LoginQr`, `Cert`, `Invite`, `DevicePairing`,
  `RelayClient`, `MembershipOp`, `Admission` and the `Device`-scheme `auth/` trio
  (`PossessionProof`, `DeviceCredential`, `DeviceAuthPolicy`) are the library's. The app only
  calls them and never mints or joins a credential itself. It keeps only the golden vectors
  (`PossessionProofTest`), so a library bump that drifts the wire fails our CI.
- **A 401 is refreshed and retried once, never looped.** heyarr's Device refusals are all an
  undifferentiated 401. `net/DeviceAuthTransport` drives `DeviceAuthPolicy.execute`, and
  before the retry `DeviceEnrolment.refreshMembership` re-reads the membership; a device that is
  no longer a member drops its credential and shows `EnrolUiState.Removed`.
- **The signing key's 1-hour user-auth window is baked in at key creation.** Changing it means
  a new keystore alias (today `heyarr-device.authorising`) and a one-time re-enrol (Path A).
- **Pairing runs in the app-scoped `device/PairingCoordinator`, not the ViewModel.** It lives in
  `HeyarrApp.pairing` with a foreground service, so switching to Cruciform on the same phone
  cannot kill the relay poll. `AppViewModel` only projects its state.
- **One-tap same-phone pairing only for a deep-linked invite.** A scanned or pasted invite keeps
  the human SAS comparison, because there is no local channel to another device.
  `heyarr-mobile://pair-done` carries a session id only: a navigation hint, never evidence.
- **Login is QR, plus the same-phone hand-off.** That is heyarr's channel. Don't copy
  push-approve (FCM/ntfy) wiring from other clients.
- **Personal state is opaque to the node; decrypt happens only on this device** (Invariant 6,
  ADR-0049). The node re-derives each change's content-addressed id and refuses a mismatch, so
  the CRDTs and id framing are pinned byte-for-byte to heyarr-core's parity vectors in
  `androidApp/src/test/resources/personalstate`. Regenerate those in heyarr-core with `-update`
  and re-copy; never hand-edit them.
- **heyarr is reached one way: `heyarr/HeyarrApi`** (MCP tools over `:core`'s `McpClient` plus
  the verified REST reads). Never invent an endpoint. The only personal rows shown are this phone's
  own decrypted state, labelled so.
- **minSdk is 33 because `voidbind-client`'s is.**
- **Unit tests are JUnit4 and pure-JVM** (no Android runtime); see the root `AGENTS.md`.

## Build & test

```sh
./gradlew :androidApp:testDebugUnitTest   # unit tests — the acceptance bar, CI-run
./gradlew :androidApp:assembleDebug       # debug APK
```

- Run from the repo root. `:androidApp` is only in the build when an Android SDK is detected
  (`settings.gradle.kts`).
- CI (`android.yml`) is the acceptance bar. The SDK's `aapt2` is x86-only, so on an aarch64
  machine rely on CI and say so in the PR.
- Anything that needs a real phone (codec playback, Keystore/StrongBox decrypt, device-cert
  login, QR bitmap rendering) can't be CI-proven. Keep it a device-side follow-up and name it
  as unproven; don't let it block a PR.

## Where to read more

- `docs/agents/android-auth-and-pairing.md`: the two credential shapes, QR login and the
  same-phone hand-offs, the pairing pipeline, the `voidbind-client` artifact and enrolment.
- `docs/agents/android-architecture.md`: the sibling repos, personal state, the design system,
  the annotated package layout and what's phone-gated.
- `docs/agents/android-build.md`: build, test and the toolchain.
- `androidApp/README.md`: the screens and the phone-gated follow-ups.
- `androidApp/docs/harmonization-with-desktop.md`: the mobile ↔ desktop convergence plan.
