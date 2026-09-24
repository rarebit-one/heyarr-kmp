package one.rarebit.heyarr.mobile.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.voidbind.Membership
import one.rarebit.voidbind.MembershipOp
import one.rarebit.voidbind.auth.DeviceCredential

/**
 * Device enrolment for the app's ViewModel: this phone's keys (voidbind-client
 * `DeviceKeyStore`), the Enrol screen's state as a projection of the app-scoped
 * [PairingCoordinator], the deep-link invite that waits for a key, adopting the `Device`
 * credential once the node accepts the admission, and the membership re-check a `401`
 * triggers. Split out of `AppViewModel` so the session/config/library half and this half
 * can be read (and changed) apart; `AppViewModel` keeps the public surface and delegates.
 *
 * What it changes on the session side (the credential, the login banner, reloading the
 * library) goes through [Host], in the same order the ViewModel did it inline.
 */
internal class DeviceEnrolment(
    private val scope: CoroutineScope,
    private val pairing: PairingCoordinator,
    /** The bare transport the public, unauthenticated membership read uses. */
    private val rawTransport: HttpTransport,
    private val host: Host,
) {
    /** The session side of the ViewModel that enrolment reads and drives. */
    interface Host {
        /** The node the phone talks to right now. */
        val baseUrl: String

        /** The live `Device` credential (cert + proof in force), once adopted. */
        var deviceCredential: DeviceCredential?

        /** The credential the app presents; null browses as a guest. */
        var credential: Credential?

        fun setLoginState(state: LoginUiState)
        fun loadSessionAuthority()
        fun loadLibrary()
        fun startGuestBrowsing()
        fun signOut()
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

    /** The phone's [DeviceKeyring], once the Activity attached it ([attachDevice]). */
    var keyring: DeviceKeyring? = null
        private set

    private val _enrolState = MutableStateFlow<EnrolUiState>(EnrolUiState.Loading)
    val enrolState: StateFlow<EnrolUiState> = _enrolState.asStateFlow()

    /** This phone's device keys (key, honest tier, cert), once read. */
    private val deviceInfo = MutableStateFlow<DeviceKeyInfo?>(null)

    /** True while [enrolState] is a projection of the coordinator's (non-idle) state. */
    private var showingPairing = false

    /** The admission op this ViewModel already adopted on its own (see reflectPairing). */
    private var autoAdoptedOp: String? = null

    init {
        // The Enrol screen's state is a projection of the app-scoped pairing wherever
        // one is in flight or just ended; the resting states (keys / no keys / adopted)
        // are this ViewModel's own.
        scope.launch { pairing.state.collect { reflectPairing(it) } }
    }

    /** What the Enrol screen shows when no pairing is in flight. */
    private fun restingState(info: DeviceKeyInfo? = deviceInfo.value): EnrolUiState = when {
        info == null -> EnrolUiState.Unprovisioned
        info.certToken != null -> EnrolUiState.Enrolled(info, "This device holds an admission.", needsAdmin = false)
        else -> EnrolUiState.Ready(info)
    }

    private suspend fun reflectPairing(ps: PairingState) {
        val info = deviceInfo.value
        when (ps) {
            PairingState.Idle -> if (showingPairing) {
                showingPairing = false
                _enrolState.value = restingState()
            }

            is PairingState.Joining -> {
                info ?: return
                showingPairing = true
                _enrolState.value = EnrolUiState.Joining(info, ps.inviteQr, ps.sameDevice, ps.deadlineMillis)
            }

            is PairingState.CompareSas -> {
                info ?: return
                showingPairing = true
                _enrolState.value =
                    EnrolUiState.CompareSas(
                        info,
                        ps.sas,
                        ps.sameDevice,
                        ps.deadlineMillis,
                        ps.awaitingAdmission,
                        ps.handedOff,
                    )
            }

            is PairingState.Registering -> {
                info ?: return
                showingPairing = true
                _enrolState.value = EnrolUiState.Registering(info)
            }

            is PairingState.Enrolled -> {
                showingPairing = true
                val ring = keyring
                val fresh = if (ring !=
                    null
                ) {
                    withContext(Dispatchers.IO) { runCatching { ring.info() }.getOrNull() }
                } else {
                    null
                }
                val shown = fresh ?: info ?: return
                deviceInfo.value = shown
                _enrolState.value =
                    EnrolUiState.Enrolled(shown, ps.registration, ps.needsAdmin, retriable = ps.retriable)
                // The node accepted the admission: sign in with it now, no "Continue" to tap.
                // Keyed on the op so a re-reported Enrolled (a recreation) adopts once.
                if (EnrolAdvance.adoptsOnEnrolled(ps.registered, ps.needsAdmin, ps.retriable) &&
                    autoAdoptedOp != ps.op
                ) {
                    autoAdoptedOp = ps.op
                    useDeviceCredential()
                }
            }

            is PairingState.Failed -> {
                showingPairing = true
                _enrolState.value = EnrolUiState.Error(info, ps.message, ps.kind)
            }
        }
    }

    /**
     * An invite that arrived by deep link from Cruciform on this phone
     * (`heyarr-mobile://pair?invite=…`, voidbind-kmp ADR-0006) while this phone could
     * not join it yet — no device key (the user must create one, which prompts for a
     * fingerprint) or the keys still being read. Joined automatically as soon as the
     * phone is [EnrolUiState.Ready]; shown on the Enrol screen meanwhile so the user
     * knows why they are being asked for a key. Cleared on join, forget, or a fresh link.
     */
    private val _parkedInvite = MutableStateFlow<String?>(null)
    val parkedInvite: StateFlow<String?> = _parkedInvite.asStateFlow()

    /**
     * Attach the phone's [DeviceKeyring] (needs an Activity for the biometric prompt).
     * Reads the device keys — provisioning them on first run, which shows the prompt —
     * and, if a cert is already stored, adopts the Device credential straight away so
     * an enrolled phone never falls back to the QR session.
     */
    fun attachDevice(ring: DeviceKeyring) {
        keyring = ring
        if (host.deviceCredential != null) return
        scope.launch {
            // peek(): never provisions, so a fresh install does not open with a biometric prompt.
            val result = withContext(Dispatchers.IO) { runCatching { ring.peek() } }
            result.onSuccess { info ->
                deviceInfo.value = info
                when {
                    // Not enrolled → browse as a guest straight away (the default); the QR/
                    // device flow is the optional "Sign in to save" upgrade on top.
                    info == null -> {
                        _enrolState.value = EnrolUiState.Unprovisioned
                        host.startGuestBrowsing()
                    }

                    info.certToken != null -> adoptDevice(ring, info.certToken)

                    else -> {
                        _enrolState.value = EnrolUiState.Ready(info)
                        // A pairing already in flight / just ended in the app-scoped holder
                        // (a recreation, or a restart reporting an interrupted one) wins.
                        if (pairing.state.value !is PairingState.Idle) reflectPairing(pairing.state.value)
                        continueParkedInvite()
                        host.startGuestBrowsing()
                    }
                }
            }.onFailure {
                // A key-store hiccup still lets the phone browse as a guest.
                _enrolState.value = EnrolUiState.Error(null, "device key unavailable: ${it.message}")
                host.startGuestBrowsing()
            }
        }
    }

    /**
     * An invite handed to us by Cruciform on this phone (the `heyarr-mobile://pair` deep
     * link). Already validated by [one.rarebit.heyarr.mobile.device.PairDeepLink] through
     * the library's parser; re-checked in [joinPairing] regardless. Joins straight away
     * when this phone has an unenrolled device key; otherwise **parks** it — a fresh
     * install first needs its key created (a fingerprint prompt the user must answer,
     * so it is never auto-triggered by a link), and a phone still reading its keys
     * continues when the read lands ([attachDevice]). An already-enrolled phone refuses:
     * it holds an admission, and only the user can choose to forget it.
     */
    fun receiveInviteLink(inviteQr: String) {
        val invite = when (val checked = PairInvite.check(inviteQr)) {
            is PairInvite.Valid -> checked.inviteQr

            is PairInvite.Invalid -> {
                _enrolState.value = EnrolUiState.Error(deviceInfo.value, checked.message)
                return
            }
        }
        // A new link supersedes any in-flight join of an older one; the SAME link (Android
        // re-delivers the launching intent on a recreation) is a no-op in the coordinator.
        // The steps are EnrolAdvance's: the same-phone path asks this app for nothing.
        val state = _enrolState.value
        when (EnrolAdvance.onInvite(state)) {
            EnrolAdvance.OnInvite.JOIN -> {
                _parkedInvite.value = null
                if (state is EnrolUiState.Error) _enrolState.value = EnrolUiState.Ready(state.info!!)
                joinPairing(invite, sameDevice = true)
            }

            EnrolAdvance.OnInvite.PROVISION_THEN_JOIN -> {
                // The fingerprint prompt has its reason on screen (the parked-invite card),
                // so the key is created without a tap; continueParkedInvite() joins after.
                _parkedInvite.value = invite
                provisionDevice()
            }

            EnrolAdvance.OnInvite.PARK -> {
                _parkedInvite.value = invite
                if (state is EnrolUiState.Joining || state is EnrolUiState.CompareSas) {
                    // A pairing in flight: the new link wins (the coordinator dedupes the same one).
                    val info = deviceInfo.value
                    if (info != null) {
                        _parkedInvite.value = null
                        joinPairing(invite, sameDevice = true)
                    }
                }
            }

            EnrolAdvance.OnInvite.REFUSE -> {
                _parkedInvite.value = null
                _enrolState.value = EnrolUiState.Error(
                    (state as EnrolUiState.Enrolled).info,
                    "This phone is already enrolled as a device. Forget the enrolment first if you want " +
                        "to join a new invite.",
                )
            }
        }
    }

    /** Join the parked invite, if any, now that the phone is [EnrolUiState.Ready]. */
    private fun continueParkedInvite() {
        val invite = _parkedInvite.value ?: return
        if (_enrolState.value !is EnrolUiState.Ready) return
        _parkedInvite.value = null
        joinPairing(invite, sameDevice = true)
    }

    /** A `heyarr-mobile://pair` link that was ours but unusable: say so on the Enrol screen. */
    fun rejectInviteLink(message: String) {
        _enrolState.value = EnrolUiState.Error(deviceInfo.value, message)
    }

    /** The user dismissed a parked invite without joining it. */
    fun discardParkedInvite() {
        _parkedInvite.value = null
    }

    /** First run: generate + seal the device keys (shows the user-presence prompt). */
    fun provisionDevice() {
        val ring = keyring ?: return
        scope.launch {
            _enrolState.value = EnrolUiState.Loading
            val result = withContext(Dispatchers.IO) { runCatching { ring.info() } }
            result.onSuccess { info ->
                deviceInfo.value = info
                _enrolState.value = EnrolUiState.Ready(info)
                if (pairing.state.value !is PairingState.Idle) reflectPairing(pairing.state.value)
                continueParkedInvite()
            }.onFailure {
                _enrolState.value = EnrolUiState.Error(null, "could not create the device key: ${it.message}")
            }
        }
    }

    /** Switch the app to the Device credential for [certToken] (the admitting op): mint a proof, load the library. */
    private suspend fun adoptDevice(ring: DeviceKeyring, certToken: String) {
        val adopted = withContext(Dispatchers.IO) {
            runCatching {
                val identity = ring.identity()
                // Short proofs at the library default (PossessionProof.DEFAULT_TTL_SECONDS,
                // 2 min; reused for ttl − skew): the device key's 1-hour user-auth window
                // (DeviceKeyring.USER_AUTH_VALIDITY_SECONDS) lets each re-mint sign silently,
                // so a short proof no longer costs a biometric — heyarr-core#444.
                val live = DeviceCredential(
                    certToken = certToken,
                    signer = identity.asSigner(),
                    clock = { System.currentTimeMillis() / MILLIS_PER_SECOND },
                )
                val first = live.current() // mints the first proof — may prompt
                host.deviceCredential = live
                Credential.Device(first.cert, first.proof)
            }
        }
        adopted.onSuccess { cred ->
            host.credential = cred
            host.setLoginState(LoginUiState.Approved(user = null))
            _enrolState.value = EnrolUiState.Enrolled(
                info = deviceInfo.value ?: withContext(Dispatchers.IO) { ring.info() },
                registration = "Signed in with this device's admission.",
                needsAdmin = false,
            )
            host.loadSessionAuthority()
            host.loadLibrary()
        }.onFailure {
            _enrolState.value =
                EnrolUiState.Error(deviceInfo.value, "could not sign with the device key: ${it.message}")
        }
    }

    /**
     * Join a pairing a member device started — the v3 `voidbind:pair?…` invite Cruciform
     * or the Mac's `voidbind pair-initiate` rendered, scanned with the camera or pasted.
     * (Under ADR-0005 only a member can mint an invite — it names the identity — so
     * this phone, the NEW device, never opens the session itself.) Re-checked here
     * through the library's parser ([PairInvite]) even though the screen already did,
     * so a caller can never push a non-invite into the handshake.
     */
    fun joinPairing(inviteQr: String) = joinPairing(inviteQr, sameDevice = false)

    /**
     * [sameDevice] marks an invite that came from Cruciform on THIS phone (the deep
     * link), so the SAS screen tells the user to switch back to Cruciform to compare
     * and confirm there, rather than to look at "the other device".
     */
    private fun joinPairing(inviteQr: String, sameDevice: Boolean) {
        if (keyring == null) return
        val info = deviceInfo.value ?: return
        val invite = when (val checked = PairInvite.check(inviteQr)) {
            is PairInvite.Valid -> checked.inviteQr

            is PairInvite.Invalid -> {
                _enrolState.value = EnrolUiState.Error(info, checked.message)
                return
            }
        }
        // The pipeline runs in the app-scoped holder, keyed by the invite's session id;
        // this ViewModel's enrolState follows it (reflectPairing).
        pairing.start(invite, sameDevice)
    }

    /**
     * The human saw the SAME code on both screens. The holder then waits — up to the
     * relay session's TTL, SAS still on screen — for the admission Cruciform seals to
     * this device after the human confirms THERE, stores both halves (the op is the
     * credential token, the ops the replica) and registers at the node presenting
     * those ops (`POST /enrol`).
     */
    fun confirmSas() = pairing.confirmMatch()

    /** The codes differ — abort; nothing was signed or received. */
    fun rejectSas() = pairing.rejectMatch()

    /** Cruciform's return leg said it refused this phone's report: stop waiting, say why. */
    fun pairingRefused(session: String, reason: String) = pairing.refuse(session, reason)

    /** Give up on the pairing in flight (the relay wait) and go back to the resting screen. */
    fun cancelPairing() = pairing.cancel()

    /**
     * `POST /enrol` again for a stored admission the node has not accepted (e.g. the proof
     * could not be signed in the background).
     */
    fun registerDevice() = pairing.retryRegister()

    fun retryEnrol() {
        pairing.dismiss()
        showingPairing = false
        _enrolState.value = restingState()
    }

    /**
     * After a `401` on a Device request, before the single re-mint + retry
     * ([DeviceAuthTransport.onUnauthorized]): re-read the identity's membership from
     * the node (`GET /membership/{usr}`, public; a node without it — 404 — teaches
     * nothing and the retry goes ahead), merge it into this device's replica, and
     * evaluate. A device the ops no longer find a member — another member removed
     * it, or its add lapsed — drops its Device credential, moves to the honest
     * [EnrolUiState.Removed] and returns `false`: the 401 stands and nothing loops.
     * Runs on the transport's (IO) thread.
     */
    fun refreshMembership(): Boolean {
        val ring = keyring ?: return true
        val own = ring.certToken() ?: return true
        val usr = ring.userId() ?: return true
        val remote = runCatching { MembershipClient(rawTransport, host.baseUrl).fetch(usr) }.getOrNull() ?: return true
        val merged = Membership.merge(ring.knownOps(), remote)
        runCatching { ring.saveOps(merged) }
        val view = runCatching { Membership.evaluate(usr, merged, nowSeconds()) }.getOrNull() ?: return true
        val self = runCatching { MembershipOp.verify(own).device }.getOrNull() ?: return true
        if (view.isMember(self)) return true

        val why = when {
            self in view.removed -> "Another member of your identity removed this device."

            view.rejected[MembershipOp.hash(own)]?.contains("expired") == true ||
                view.ineffective[MembershipOp.hash(own)]?.contains("expired") == true ->
                "This device's admission has expired."

            else ->
                "This device is no longer a member of the identity " +
                    "(${view.rejected[
                        MembershipOp.hash(
                            own,
                        ),
                    ] ?: view.ineffective[MembershipOp.hash(own)] ?: "not admitted"})."
        }
        host.deviceCredential = null
        host.credential = null
        deviceInfo.value = runCatching { ring.info() }.getOrNull() ?: deviceInfo.value
        _enrolState.value = EnrolUiState.Removed(deviceInfo.value ?: return false, why)
        host.setLoginState(LoginUiState.Error("This device was removed from your Voidbind identity. $why"))
        return false
    }

    /** After enrolment: start using the Device credential now. */
    fun useDeviceCredential() {
        val ring = keyring ?: return
        val cert = deviceInfo.value?.certToken ?: return
        pairing.dismiss()
        showingPairing = false
        scope.launch { adoptDevice(ring, cert) }
    }

    /** Drop the stored admission (keys stay) and fall back to QR login. */
    fun forgetDevice() {
        val ring = keyring ?: return
        pairing.cancel()
        showingPairing = false
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ring.clearCert() } }
            host.deviceCredential = null
            _parkedInvite.value = null
            val info = withContext(Dispatchers.IO) { runCatching { ring.info() }.getOrNull() }
            deviceInfo.value = info
            _enrolState.value = if (info != null) EnrolUiState.Ready(info) else EnrolUiState.Unprovisioned
            host.signOut()
        }
    }
}

private const val MILLIS_PER_SECOND = 1000L
