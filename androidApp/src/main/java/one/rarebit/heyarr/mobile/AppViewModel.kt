package one.rarebit.heyarr.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.discovery.DiscoveredServer
import one.rarebit.heyarr.core.discovery.DiscoverySource
import one.rarebit.heyarr.core.discovery.MdnsResolver
import one.rarebit.heyarr.core.discovery.NoMdnsResolver
import one.rarebit.heyarr.core.discovery.NodeDiscovery
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.device.DeviceEnrolment
import one.rarebit.heyarr.mobile.device.DeviceKeyring
import one.rarebit.heyarr.mobile.device.EnrolClient
import one.rarebit.heyarr.mobile.device.EnrolUiState
import one.rarebit.heyarr.mobile.device.InMemoryPendingPairingStore
import one.rarebit.heyarr.mobile.device.MembershipOps
import one.rarebit.heyarr.mobile.device.PairingCoordinator
import one.rarebit.heyarr.mobile.device.PairingSteps
import one.rarebit.heyarr.mobile.login.InMemoryPendingLoginStore
import one.rarebit.heyarr.mobile.login.LoginCoordinator
import one.rarebit.heyarr.mobile.login.LoginFlow
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.login.QrLoginClient
import one.rarebit.heyarr.mobile.net.DeviceAuthTransport
import one.rarebit.heyarr.mobile.net.OkHttpTransport
import one.rarebit.heyarr.mobile.playback.PlaybackCoordinator
import one.rarebit.heyarr.mobile.search.SessionAuthority
import one.rarebit.heyarr.mobile.search.SessionClient
import one.rarebit.heyarr.mobile.settings.InMemorySettingsStore
import one.rarebit.heyarr.mobile.settings.SettingsStore
import one.rarebit.voidwhichbinds.auth.DeviceCredential
import one.rarebit.voidwhichbinds.flow.PairingFailureKind
import one.rarebit.voidwhichbinds.flow.PairingOutcome

/** The steps of a ViewModel built without the app's holder (tests): every pairing fails honestly. */
private object UnavailablePairingSteps : PairingSteps {
    private val failed = PairingOutcome.Failed(
        PairingFailureKind.PROTOCOL,
        "pairing is not available in this build",
        "",
    )
    override suspend fun handshake(inviteQr: String, deadlineMillis: Long): PairingOutcome<PairingSteps.Handshaked> =
        failed
    override suspend fun receive(deadlineMillis: Long): PairingOutcome<String> = failed
    override suspend fun register(op: String): EnrolClient.Outcome =
        EnrolClient.Outcome.Failed("pairing is not available in this build")
}

/**
 * Drives the QR login, holds the resulting Bearer session token as a [Credential].
 * Blocking transport calls run on [Dispatchers.IO]; the UI observes [loginState].
 *
 * The effective [config] is resolved from the build default plus the runtime
 * overrides in [settings] ([HeyarrConfig.resolve]); [updateSettings] re-resolves it
 * and, when the base URL changed, ends the QR session (a session token is only good for
 * the node that minted it) but keeps a live enrolled device's credential, which is
 * node-independent ([switchNode], heyarr-kmp#185).
 */
class AppViewModel internal constructor(
    private val settings: SettingsStore = InMemorySettingsStore(),
    /**
     * The app-scoped pairing holder ([one.rarebit.heyarr.mobile.HeyarrApp.pairing] on
     * the phone): the join → SAS → admission → `/enrol` pipeline outlives this
     * ViewModel; it only observes and drives it. The default cannot pair (tests).
     */
    private val pairing: PairingCoordinator = PairingCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        store = InMemoryPendingPairingStore(),
        steps = { UnavailablePairingSteps },
    ),
    /**
     * The app-scoped QR login ([one.rarebit.heyarr.mobile.HeyarrApp.login] on the phone):
     * its `GET /login/{id}` poll outlives this ViewModel, so a login approved in Cruciform
     * while this app was frozen or killed behind it is still collected (#172). This
     * ViewModel starts it and applies its outcome. The default polls a real QR login on a
     * scope of its own.
     */
    private val login: LoginCoordinator = LoginCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        store = InMemoryPendingLoginStore(),
        login = { base -> QrLoginClient(http = OkHttpTransport(), rpBase = base) },
    ),
    /**
     * The raw transport — the shared OkHttp client on the phone (AppGraph), a bare one
     * in tests. What the public, unauthenticated membership read uses, and what
     * [transport] wraps for Device auth.
     */
    private val rawTransport: HttpTransport = OkHttpTransport(),
    /** The device-side personal-state role map (SharedPreferences on the phone; in-memory in tests). */
    private val spaceRegistry: one.rarebit.heyarr.mobile.personalstate.SpaceRegistry =
        one.rarebit.heyarr.mobile.personalstate.InMemorySpaceRegistry(),
    /**
     * The LAN mDNS browser for auto-discovery (`NsdMdnsResolver` on the phone, the
     * no-op default in tests/previews). Feeds `:core`'s [NodeDiscovery] fallback chain
     * (mDNS → split-horizon DNS → the saved/default node).
     */
    private val mdns: MdnsResolver = NoMdnsResolver,
) : ViewModel() {

    /**
     * Once this phone is enrolled, its live `Device` credential (cert + the possession
     * proof in force). Null while signed in with a QR session, or before enrolment.
     */
    @Volatile
    private var deviceCredential: DeviceCredential? = null

    /**
     * The app's transport: every `/api/v1` request an enrolled device makes goes through
     * [DeviceAuthTransport], which keeps the `Device` credential fresh, presents the
     * membership ops this device knows (`Void-Which-Binds-Membership`, ADR-0005) and re-mints +
     * retries once on a 401 (mobile-client constraint 2) — after [refreshMembership]
     * has had its say: a device that learns it was removed does not retry.
     */
    val transport: HttpTransport = DeviceAuthTransport(
        rawTransport,
        credential = { deviceCredential },
        membership = { keyring?.let { MembershipOps.headerValue(it.knownOps(), it.certToken()) } },
        // After a 401, before the one retry: a device that learns it was removed does not retry.
        onUnauthorized = { enrolment.refreshMembership() },
    )

    /**
     * Encrypted personal state for this device (see
     * [one.rarebit.heyarr.mobile.personalstate.DevicePersonalState]): null until the phone
     * holds its keys.
     */
    private val devicePersonalState = one.rarebit.heyarr.mobile.personalstate.DevicePersonalState(
        transport = transport,
        spaceRegistry = spaceRegistry,
        keyring = { keyring },
    )

    /**
     * A [one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator] for the
     * given node + credential, or null when this device is not enrolled (no X25519 key
     * to unwrap a space key). Build one per use.
     */
    internal fun personalState(
        baseUrl: String,
        cred: Credential,
    ): one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator? {
        val coordinator = devicePersonalState.coordinator(baseUrl, cred)
        return coordinator
    }

    /**
     * What is playing and how it came to be: planning, fallback and the one re-plan
     * (playback/PlaybackCoordinator). Reads the credential and node per call, so it
     * follows a sign-in, an enrolment and a Settings change without being rebuilt.
     */
    val playback: PlaybackCoordinator by lazy {
        PlaybackCoordinator(
            transport = transport,
            baseUrl = { config.baseUrl },
            credential = { credential },
            scope = viewModelScope,
        )
    }

    private val _config = MutableStateFlow(resolveConfig())
    val configState: StateFlow<HeyarrConfig> = _config.asStateFlow()

    /** The effective config right now (build default + saved overrides). */
    val config: HeyarrConfig get() = _config.value

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    init {
        // Mirror the app-scoped login, and apply its outcome exactly once, including one
        // collected by a poll resumed after a process death (#172).
        //
        // Dispatched on Dispatchers.Main, not viewModelScope's Main.immediate: an approval
        // already waiting when this ViewModel is built would otherwise be applied right here,
        // before `credential`'s own initializer further down runs and resets it.
        //
        // An approval is applied only once the config points at the node that approved it
        // (the token is only good there). After a restart an mDNS-discovered node is only
        // re-found by autoDiscover(), so the approval waits for it rather than being dropped;
        // a sign-out or a new sign-in cancels the login, and with it a stale approval.
        viewModelScope.launch(Dispatchers.Main) {
            combine(login.state, _config) { flow, cfg -> flow to cfg.baseUrl }.collect { (flow, base) ->
                when (flow) {
                    LoginFlow.Idle, is LoginFlow.Starting -> Unit

                    is LoginFlow.Awaiting -> _loginState.value = LoginUiState.AwaitingScan(flow.pending.qrTuple)

                    is LoginFlow.Approved -> if (flow.rpBase == base) {
                        // The QR bootstrap yields a Bearer session token (auth/Credential.Session).
                        credential = Credential.Session(flow.sessionToken)
                        _loginState.value = LoginUiState.Approved(flow.user)
                        loadSessionAuthority()
                        login.consume()
                    } else if (_loginState.value is LoginUiState.AwaitingScan) {
                        // Not this node (yet): offer "Sign in" again rather than a QR that
                        // already completed. A new sign-in replaces the waiting approval.
                        _loginState.value = LoginUiState.Idle
                    }

                    is LoginFlow.Failed -> {
                        _loginState.value = LoginUiState.Error(flow.message)
                        login.consume()
                    }
                }
            }
        }
    }

    private val _sessionAuthority = MutableStateFlow<SessionAuthority?>(null)
    val sessionAuthority: StateFlow<SessionAuthority?> = _sessionAuthority.asStateFlow()

    private var credential: Credential? = null

    /**
     * The credential established by QR login (a Bearer session, later a device cert),
     * or null before sign-in. Exposed so the search/acquire/following features can be
     * driven with the same authenticated identity that browses the library, and so the
     * poster/range-read header source sends NOTHING for a guest (null → no header).
     */
    fun credentialOrNull(): Credential? = credential

    /**
     * The credential the client presents right now — never null. When nothing is
     * enrolled/signed in this is [Credential.Guest]: it sends NO `Authorization` header,
     * so heyarr's trusted-network guest path applies (browse/play/subtitle). Guest is the
     * new default in front of the QR/device "Sign in to save" upgrade. The shell keys its
     * session on this, so adopting a real credential rebuilds it enrolled.
     */
    fun effectiveCredential(): Credential = credential ?: Credential.Guest

    /** True while the phone is browsing as an anonymous guest (no login / no enrolment). */
    val isGuest: Boolean get() = credential == null

    // ── auto-discovery (mDNS → split-horizon DNS → saved/default node) ─────────────

    private val discovery = NodeDiscovery(mdns, dnsHost = BuildConfig.HEYARR_DNS_HOST)

    /** A node discovered on THIS network, applied for the session when the user set no override. */
    @Volatile
    private var discoveredBaseUrl: String? = null

    /** Resolve the server to default to, using the current effective URL as the manual fallback. Off `Dispatchers.IO`. */
    suspend fun discoverServer(): DiscoveredServer = withContext(Dispatchers.IO) { discovery.discover(config.baseUrl) }

    /**
     * On launch, before any node was hand-picked: if an `_heyarr._tcp` advertiser is
     * actually on this network, browse against IT instead of the build default. Only mDNS
     * (a node truly on THIS LAN) auto-applies; the split-horizon DNS name is already the
     * build default, and a user override is never overridden. No sign-out — a guest holds
     * nothing to invalidate; the guest library simply reloads against the found node.
     */
    fun autoDiscover() {
        if (settings.baseUrlOverride != null || credential != null) return
        viewModelScope.launch {
            val found = runCatching { discoverServer() }.getOrNull() ?: return@launch
            if (found.source == DiscoverySource.MDNS && found.baseUrl != _config.value.baseUrl) {
                discoveredBaseUrl = found.baseUrl
                _config.value = resolveConfig()
                if (credential == null) startGuestBrowsing()
            }
        }
    }

    /**
     * The Settings "Discover" button: run the chain now and, when it names a node (mDNS or
     * the DNS fallback), save it as the connection so it sticks. [onResult] reports what was
     * found so the screen can toast it.
     */
    fun discoverAndSave(onResult: (DiscoveredServer) -> Unit = {}) {
        viewModelScope.launch {
            val found = runCatching { discoverServer() }.getOrNull() ?: return@launch
            if (found.source != DiscoverySource.MANUAL) updateSettings(found.baseUrl, config.defaultQualityProfile)
            onResult(found)
        }
    }

    /**
     * The `Authorization` value in force right now, for fetches that go around the API
     * clients (posters, range reads — net/AuthInterceptor): the live Device proof when
     * enrolled (the library reuses it for its window and re-mints when it lapses),
     * else the session token, else nothing. No wire format is derived here.
     */
    fun liveAuthorizationHeader(): String? = deviceCredential?.headerValue() ?: credential?.headerValue()

    // A user-saved override always wins; otherwise a node auto-discovered on this LAN
    // (mDNS) is preferred over the build default so a guest lands on the right node.
    private fun resolveConfig(): HeyarrConfig =
        HeyarrConfig.resolve(settings.baseUrlOverride ?: discoveredBaseUrl, settings.qualityProfileOverride)

    /**
     * Persist new overrides (a value equal to the build default is stored as "no
     * override") and re-resolve [config]. A changed base URL ends the per-node session
     * ([switchNode]); an enrolled device stays signed in.
     */
    fun updateSettings(baseUrl: String, qualityProfile: String) {
        val normalized = HeyarrConfig.normalizeBaseUrl(baseUrl)
        settings.baseUrlOverride = normalized?.takeIf { it != HeyarrConfig.DEFAULT_BASE_URL }
        settings.qualityProfileOverride =
            qualityProfile.trim().takeIf { it.isNotEmpty() && it != HeyarrConfig.DEFAULT_QUALITY_PROFILE }
        applyConfig(resolveConfig())
    }

    /** Clear all overrides back to the build defaults. */
    fun resetSettings() {
        settings.baseUrlOverride = null
        settings.qualityProfileOverride = null
        applyConfig(resolveConfig())
    }

    private fun applyConfig(next: HeyarrConfig) {
        val baseChanged = next.baseUrl != _config.value.baseUrl
        _config.value = next
        if (baseChanged) switchNode()
    }

    /**
     * The node changed (Settings, Discover). What was per node goes: the session authority
     * read from the old node, whatever was playing from it and the QR login in flight
     * there. Which credential survives is [DeviceEnrolment.onNodeChanged]'s call: a
     * QR/web-login session token (only good at the node that minted it) is dropped, but a
     * live `Device` credential is kept — it is node-independent, and dropping
     * it left an enrolled phone browsing as a guest until the app restarted (heyarr-kmp#185).
     *
     * Not [signOut]: that is the user's explicit "Sign out" and still drops both.
     */
    private fun switchNode() {
        _sessionAuthority.value = null
        playback.stop()
        login.cancel()
        enrolment.onNodeChanged()
    }

    /** Drop the credential and fall back to browsing as a guest (the new default), not a login wall. */
    fun signOut() {
        credential = null
        deviceCredential = null
        _sessionAuthority.value = null
        playback.stop()
        login.cancel()
        _loginState.value = LoginUiState.Idle
        startGuestBrowsing()
    }

    /**
     * Browse as an anonymous guest: no credential ([Credential.Guest] sends no header, so
     * the node's trusted-network guest path applies), load the library and read the guest
     * session so the shell has content before any login. A no-op once a real credential is
     * held. This is what makes guest the default on a trusted network.
     */
    fun startGuestBrowsing() {
        if (credential != null) return
        loadSessionAuthority()
    }

    /**
     * Open a QR login on the current node. It runs in the app-scoped [LoginCoordinator],
     * not here, so going behind Cruciform cannot lose it; [loginState] mirrors it.
     */
    fun signIn() {
        if (_loginState.value is LoginUiState.AwaitingScan) return
        login.start(config.baseUrl)
    }

    /** "Approve on this phone": the app goes behind Cruciform, so hold the process up. */
    fun loginHandedOff() = login.handedOff()

    /** Cruciform did not open after "Approve on this phone": undo [loginHandedOff]. */
    fun loginHandOffFailed() = login.handOffFailed()

    /** Introspect the session (`GET /api/v1/session`) for the signed-in / guest / read-only banner. */
    fun loadSessionAuthority() {
        val cred = effectiveCredential()
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching { SessionClient(transport, config.baseUrl, cred).authority() }.getOrNull()
            }
            _sessionAuthority.value = next
        }
    }

    // ── Device enrolment (voidbind-client DeviceKeyStore + DevicePairing) ─────────
    // The keys, the pairing projection and adopting the Device credential live in
    // device/DeviceEnrolment; this ViewModel keeps the public surface the screens bind to.

    private val enrolment = DeviceEnrolment(
        scope = viewModelScope,
        pairing = pairing,
        rawTransport = rawTransport,
        host = object : DeviceEnrolment.Host {
            override val baseUrl: String get() = config.baseUrl
            override var deviceCredential: DeviceCredential?
                get() = this@AppViewModel.deviceCredential
                set(value) {
                    this@AppViewModel.deviceCredential = value
                }
            override var credential: Credential?
                get() = this@AppViewModel.credential
                set(value) {
                    this@AppViewModel.credential = value
                }

            override fun setLoginState(state: LoginUiState) {
                _loginState.value = state
            }

            override fun loadSessionAuthority() = this@AppViewModel.loadSessionAuthority()
            override fun startGuestBrowsing() = this@AppViewModel.startGuestBrowsing()
            override fun signOut() = this@AppViewModel.signOut()
        },
    )

    /** This phone's keyring, once attached (see [attachDevice]). */
    private val keyring: DeviceKeyring? get() = enrolment.keyring

    val enrolState: StateFlow<EnrolUiState> get() = enrolment.enrolState

    /** An invite from Cruciform on this phone waiting for the device key ([DeviceEnrolment.receiveInviteLink]). */
    val parkedInvite: StateFlow<String?> get() = enrolment.parkedInvite

    /**
     * Attach the phone's [DeviceKeyring] (needs an Activity for the biometric prompt); see
     * [DeviceEnrolment.attachDevice].
     */
    fun attachDevice(ring: DeviceKeyring) = enrolment.attachDevice(ring)

    /** An invite handed to us by Cruciform on this phone (the `heyarr-mobile://pair` deep link). */
    fun receiveInviteLink(inviteQr: String) = enrolment.receiveInviteLink(inviteQr)

    /** A `heyarr-mobile://pair` link that was ours but unusable: say so on the Enrol screen. */
    fun rejectInviteLink(message: String) = enrolment.rejectInviteLink(message)

    /** The user dismissed a parked invite without joining it. */
    fun discardParkedInvite() = enrolment.discardParkedInvite()

    /** First run: generate + seal the device keys (shows the user-presence prompt). */
    fun provisionDevice() = enrolment.provisionDevice()

    /** Join a pairing a member device started (a scanned or pasted invite). */
    fun joinPairing(inviteQr: String) = enrolment.joinPairing(inviteQr)

    /** The human saw the SAME code on both screens. */
    fun confirmSas() = enrolment.confirmSas()

    /** The codes differ — abort; nothing was signed or received. */
    fun rejectSas() = enrolment.rejectSas()

    /** Cruciform's return leg said it refused this phone's report: stop waiting, say why. */
    fun pairingRefused(session: String, reason: String) = enrolment.pairingRefused(session, reason)

    /** Give up on the pairing in flight (the relay wait) and go back to the resting screen. */
    fun cancelPairing() = enrolment.cancelPairing()

    /** `POST /enrol` again for a stored admission the node has not accepted. */
    fun registerDevice() = enrolment.registerDevice()

    fun retryEnrol() = enrolment.retryEnrol()

    /** After enrolment: start using the Device credential now. */
    fun useDeviceCredential() = enrolment.useDeviceCredential()

    /** Drop the stored admission (keys stay) and fall back to guest browsing. */
    fun forgetDevice() = enrolment.forgetDevice()

    /** A human-readable name for the node's device registry. */
    var deviceName: String = "heyarr-mobile"
}
