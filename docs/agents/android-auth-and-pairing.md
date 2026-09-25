# `:androidApp` auth, login, pairing and enrolment

Moved verbatim from the former `androidApp/CLAUDE.md`; the router is `androidApp/AGENTS.md`.

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
works, never pretending. **Server notes:** `POST /enrol` taking `ops`
and `GET /membership/{usr}` landed in heyarr-core PR #426 (ADR-0068, merged 2026-09-02); the
app's fallbacks (retry without `ops`; 404 = nothing learned) still cover older nodes; a device is
read-scoped until an admin grants its key (`POST /api/v1/session/management-grants
{device_key}`, ADR-0065); `POST /api/v1/devices` is a playback profile, not identity.
