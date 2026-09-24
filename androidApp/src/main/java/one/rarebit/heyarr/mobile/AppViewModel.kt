package one.rarebit.heyarr.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.discovery.DiscoveredServer
import one.rarebit.heyarr.core.discovery.DiscoverySource
import one.rarebit.heyarr.core.discovery.MdnsResolver
import one.rarebit.heyarr.core.discovery.NoMdnsResolver
import one.rarebit.heyarr.core.discovery.NodeDiscovery
import one.rarebit.heyarr.mobile.device.DeviceKeyInfo
import one.rarebit.heyarr.mobile.device.DeviceKeyring
import one.rarebit.heyarr.mobile.device.EnrolClient
import one.rarebit.heyarr.mobile.device.EnrolAdvance
import one.rarebit.heyarr.mobile.device.EnrolUiState
import one.rarebit.heyarr.mobile.device.InMemoryPendingPairingStore
import one.rarebit.heyarr.mobile.device.MembershipClient
import one.rarebit.heyarr.mobile.device.MembershipOps
import one.rarebit.heyarr.mobile.device.PairInvite
import one.rarebit.heyarr.mobile.device.PairingCoordinator
import one.rarebit.heyarr.mobile.device.PairingState
import one.rarebit.heyarr.mobile.device.PairingSteps
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.LibraryUiState
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.login.QrLoginClient
import one.rarebit.heyarr.mobile.login.VoidbindLogin
import one.rarebit.heyarr.mobile.net.DeviceAuthTransport
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.net.OkHttpTransport
import one.rarebit.heyarr.mobile.net.OkHttpVoidbindTransport
import one.rarebit.heyarr.mobile.catalog.ContinueClient
import one.rarebit.heyarr.mobile.consumption.ConsumptionClient
import one.rarebit.heyarr.mobile.consumption.ConsumptionReporter
import one.rarebit.heyarr.mobile.consumption.DeviceIdStore
import one.rarebit.heyarr.mobile.consumption.InMemoryDeviceIdStore
import one.rarebit.heyarr.mobile.playback.AudioPlayer
import one.rarebit.heyarr.mobile.playback.AudioSessionBridge
import one.rarebit.heyarr.mobile.playback.PlaybackCoordinator
import one.rarebit.heyarr.mobile.search.SessionAuthority
import one.rarebit.heyarr.mobile.search.SessionClient
import one.rarebit.heyarr.mobile.settings.InMemorySettingsStore
import one.rarebit.heyarr.mobile.settings.SettingsStore
import one.rarebit.voidbind.Membership
import one.rarebit.voidbind.MembershipOp
import one.rarebit.voidbind.auth.DeviceCredential
import one.rarebit.voidbind.flow.PairingFailureKind
import one.rarebit.voidbind.flow.PairingOutcome

/** The steps of a ViewModel built without the app's holder (tests): every pairing fails honestly. */
private object UnavailablePairingSteps : PairingSteps {
    private val failed = PairingOutcome.Failed(PairingFailureKind.PROTOCOL, "pairing is not available in this build", "")
    override suspend fun handshake(inviteQr: String, deadlineMillis: Long): PairingOutcome<PairingSteps.Handshaked> = failed
    override suspend fun receive(deadlineMillis: Long): PairingOutcome<String> = failed
    override suspend fun register(op: String): EnrolClient.Outcome = EnrolClient.Outcome.Failed("pairing is not available in this build")
}

/**
 * Drives the QR login, holds the resulting Bearer session token as a [Credential],
 * then loads the library with it. Blocking transport calls run on [Dispatchers.IO];
 * the UI observes [loginState] and [libraryState].
 *
 * The effective [config] is resolved from the build default plus the runtime
 * overrides in [settings] ([HeyarrConfig.resolve]); [updateSettings] re-resolves it
 * and, when the base URL changed, signs out (a session token is only good for the
 * node that minted it).
 */
class AppViewModel internal constructor(
    private val settings: SettingsStore = InMemorySettingsStore(),
    private val loginFactory: (baseUrl: String) -> VoidbindLogin = { base ->
        QrLoginClient(http = OkHttpTransport(), rpBase = base)
    },
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
     * The raw transport — the shared OkHttp client on the phone (AppGraph), a bare one
     * in tests. What the public, unauthenticated membership read uses, and what
     * [transport] wraps for Device auth.
     */
    private val rawTransport: HttpTransport = OkHttpTransport(),
    /** Where this phone's node-issued device ids live (SharedPreferences on the phone). */
    private val deviceIds: DeviceIdStore = InMemoryDeviceIdStore(),
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
     * membership ops this device knows (`Voidbind-Membership`, ADR-0005) and re-mints +
     * retries once on a 401 (mobile-client constraint 2) — after [refreshMembership]
     * has had its say: a device that learns it was removed does not retry.
     */
    val transport: HttpTransport = DeviceAuthTransport(
        rawTransport,
        credential = { deviceCredential },
        membership = { keyring?.let { MembershipOps.headerValue(it.knownOps(), it.certToken()) } },
        onUnauthorized = ::refreshMembership,
    )

    /**
     * A [one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator] for the
     * given node + credential, or null when this device is not enrolled (no X25519 key
     * to unwrap a space key). Encrypted personal state — playlists, starred, play
     * history, reading positions — decrypts ONLY on this device (Invariant 6). The
     * coordinator is cheap and stateless; build one per use.
     */
    internal fun personalState(
        baseUrl: String,
        cred: Credential,
    ): one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator? {
        val ring = keyring?.takeIf { it.isProvisioned() } ?: return null
        return one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator(
            one.rarebit.heyarr.mobile.personalstate.SpaceSession(
                one.rarebit.heyarr.mobile.personalstate.PersonalStateClient(transport, baseUrl, cred),
                one.rarebit.heyarr.mobile.personalstate.KeyringDeviceEncKey(ring),
                additionalRecipients = { memberEncRecipients(ring) + recoveryRecipients(ring) },
            ),
            spaceRegistry,
        )
    }

    /**
     * The X25519 enc keys of the OTHER authorised member devices, so a space this phone
     * creates is wrapped for them too and they can decrypt it (ADR-0049) — the peer half
     * of the gateway acceptance. Derived from the membership this device already holds
     * (each add op carries the device's `denc`); the recovery key is not obtainable on
     * the phone (paper-secret only), so it stays out until it can be provisioned.
     */
    private fun memberEncRecipients(ring: DeviceKeyring): List<ByteArray> {
        val usr = ring.userId() ?: return emptyList()
        val view = runCatching { Membership.evaluate(usr, ring.knownOps(), nowSeconds()) }.getOrNull() ?: return emptyList()
        val self = ring.peek()?.deviceEncKey
        return view.members.values
            .map { it.deviceEnc }
            .filter { it.isNotEmpty() && it != self }
            .distinct()
            .mapNotNull { one.rarebit.heyarr.mobile.personalstate.parseX25519Recipient(it) }
    }

    /**
     * The identity's recovery encryption **public** key, if enrolment provisioned one
     * (issue #41 part 2, Option A): a `x25519:<hex>` recipient a new space is also wrapped
     * for, so state survives losing every device. Empty (the honest degraded default)
     * until enrolment carries the key, or on a node/identity that has none.
     */
    private fun recoveryRecipients(ring: DeviceKeyring): List<ByteArray> =
        ring.recoveryRecipient()
            ?.let { one.rarebit.heyarr.mobile.personalstate.parseX25519Recipient(it) }
            ?.let { listOf(it) }
            ?: emptyList()

    /** The coordinator for the current node + credential (null before enrolment). */
    private fun currentPersonalState(): one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator? =
        credentialOrNull()?.let { personalState(config.baseUrl, it) }

    /** Syncs a reader's exact locator through the encrypted reading-position space (§45). */
    val readingPositionSync: one.rarebit.heyarr.mobile.reader.ReadingPositionSync by lazy {
        one.rarebit.heyarr.mobile.reader.CoordinatorReadingPositionSync({ currentPersonalState() }, viewModelScope)
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
            reporter = reporter,
            resumeAt = { assetId ->
                val cred = credential ?: return@PlaybackCoordinator null
                val rail = ContinueClient(transport, config.baseUrl, cred).rail() as? ContinueClient.Outcome.Rail
                rail?.entries?.firstOrNull { it.assetId == assetId }?.positionSeconds
            },
        )
    }

    /**
     * Tells the node where playback reached (§67): silent until this credential can
     * write, i.e. an enrolled, authorised device. The device registers itself once per
     * node under its enrolled key.
     */
    private val reporter: ConsumptionReporter by lazy {
        ConsumptionReporter(
            client = ConsumptionClient(transport, { config.baseUrl }, { credential }),
            store = deviceIds,
            scope = viewModelScope,
            baseUrl = { config.baseUrl },
            canWrite = { _sessionAuthority.value?.canWrite == true },
            enrolledDeviceKey = { _sessionAuthority.value?.deviceKey },
            deviceName = { deviceName },
            capabilities = { playback.capabilities },
        )
    }

    /** The audio queue reports too: a track is a `listen` session. Attach once from the Activity. */
    fun attachAudio(audio: AudioPlayer) {
        if (audioBridge != null) return
        audioBridge = AudioSessionBridge(audio, reporter, viewModelScope)
    }

    /** The reporter, for the reader activity to share (it runs outside this ViewModel). */
    val progressReporter: one.rarebit.heyarr.mobile.consumption.ProgressReporter get() = reporter

    private var audioBridge: AudioSessionBridge? = null

    private val _config = MutableStateFlow(resolveConfig())
    val configState: StateFlow<HeyarrConfig> = _config.asStateFlow()

    /** The effective config right now (build default + saved overrides). */
    val config: HeyarrConfig get() = _config.value

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _libraryState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()

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

    private val discovery = NodeDiscovery(mdns)

    /** A node discovered on THIS network, applied for the session when the user set no override. */
    @Volatile
    private var discoveredBaseUrl: String? = null

    /** Resolve the server to default to, using the current effective URL as the manual fallback. Off `Dispatchers.IO`. */
    suspend fun discoverServer(): DiscoveredServer =
        withContext(Dispatchers.IO) { discovery.discover(config.baseUrl) }

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
     * override") and re-resolve [config]. A changed base URL signs out.
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
        if (baseChanged) signOut()
    }

    /** Drop the credential and fall back to browsing as a guest (the new default), not a login wall. */
    fun signOut() {
        credential = null
        deviceCredential = null
        _sessionAuthority.value = null
        playback.stop()
        _libraryState.value = LibraryUiState.Loading
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
        loadLibrary()
    }

    fun signIn() {
        if (_loginState.value is LoginUiState.AwaitingScan) return
        val login = loginFactory(config.baseUrl)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pending = login.begin()
                    _loginState.value = LoginUiState.AwaitingScan(pending.qrTuple)
                    login.awaitApproval(pending)
                }.getOrElse { VoidbindLogin.Result.Failed(it.message ?: "login error") }
            }
            when (result) {
                is VoidbindLogin.Result.Approved -> {
                    // The QR bootstrap yields a Bearer session token (auth/Credential.Session).
                    credential = Credential.Session(result.sessionToken)
                    _loginState.value = LoginUiState.Approved(result.user)
                    loadSessionAuthority()
                    loadLibrary()
                }
                is VoidbindLogin.Result.Denied -> _loginState.value = LoginUiState.Error(result.reason)
                is VoidbindLogin.Result.Failed -> _loginState.value = LoginUiState.Error(result.error)
            }
        }
    }

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

    private var keyring: DeviceKeyring? = null

    private val _enrolState = MutableStateFlow<EnrolUiState>(EnrolUiState.Loading)
    val enrolState: StateFlow<EnrolUiState> = _enrolState.asStateFlow()

    /** This phone's device keys (key, honest tier, cert), once read. */
    private val _deviceInfo = MutableStateFlow<DeviceKeyInfo?>(null)

    /** True while [enrolState] is a projection of the coordinator's (non-idle) state. */
    private var showingPairing = false
    /** The admission op this ViewModel already adopted on its own (see reflectPairing). */
    private var autoAdoptedOp: String? = null

    init {
        // The Enrol screen's state is a projection of the app-scoped pairing wherever
        // one is in flight or just ended; the resting states (keys / no keys / adopted)
        // are this ViewModel's own.
        viewModelScope.launch { pairing.state.collect { reflectPairing(it) } }
    }

    /** What the Enrol screen shows when no pairing is in flight. */
    private fun restingState(info: DeviceKeyInfo? = _deviceInfo.value): EnrolUiState = when {
        info == null -> EnrolUiState.Unprovisioned
        info.certToken != null -> EnrolUiState.Enrolled(info, "This device holds an admission.", needsAdmin = false)
        else -> EnrolUiState.Ready(info)
    }

    private suspend fun reflectPairing(ps: PairingState) {
        val info = _deviceInfo.value
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
                _enrolState.value = EnrolUiState.CompareSas(info, ps.sas, ps.sameDevice, ps.deadlineMillis, ps.awaitingAdmission, ps.handedOff)
            }
            is PairingState.Registering -> {
                info ?: return
                showingPairing = true
                _enrolState.value = EnrolUiState.Registering(info)
            }
            is PairingState.Enrolled -> {
                showingPairing = true
                val ring = keyring
                val fresh = if (ring != null) withContext(Dispatchers.IO) { runCatching { ring.info() }.getOrNull() } else null
                val shown = fresh ?: info ?: return
                _deviceInfo.value = shown
                _enrolState.value = EnrolUiState.Enrolled(shown, ps.registration, ps.needsAdmin, retriable = ps.retriable)
                // The node accepted the admission: sign in with it now, no "Continue" to tap.
                // Keyed on the op so a re-reported Enrolled (a recreation) adopts once.
                if (EnrolAdvance.adoptsOnEnrolled(ps.registered, ps.needsAdmin, ps.retriable) && autoAdoptedOp != ps.op) {
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

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /**
     * Attach the phone's [DeviceKeyring] (needs an Activity for the biometric prompt).
     * Reads the device keys — provisioning them on first run, which shows the prompt —
     * and, if a cert is already stored, adopts the Device credential straight away so
     * an enrolled phone never falls back to the QR session.
     */
    fun attachDevice(ring: DeviceKeyring) {
        keyring = ring
        if (deviceCredential != null) return
        viewModelScope.launch {
            // peek(): never provisions, so a fresh install does not open with a biometric prompt.
            val result = withContext(Dispatchers.IO) { runCatching { ring.peek() } }
            result.onSuccess { info ->
                _deviceInfo.value = info
                when {
                    // Not enrolled → browse as a guest straight away (the default); the QR/
                    // device flow is the optional "Sign in to save" upgrade on top.
                    info == null -> { _enrolState.value = EnrolUiState.Unprovisioned; startGuestBrowsing() }
                    info.certToken != null -> adoptDevice(ring, info.certToken)
                    else -> {
                        _enrolState.value = EnrolUiState.Ready(info)
                        // A pairing already in flight / just ended in the app-scoped holder
                        // (a recreation, or a restart reporting an interrupted one) wins.
                        if (pairing.state.value !is PairingState.Idle) reflectPairing(pairing.state.value)
                        continueParkedInvite()
                        startGuestBrowsing()
                    }
                }
            }.onFailure {
                // A key-store hiccup still lets the phone browse as a guest.
                _enrolState.value = EnrolUiState.Error(null, "device key unavailable: ${it.message}")
                startGuestBrowsing()
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
                _enrolState.value = EnrolUiState.Error(_deviceInfo.value, checked.message)
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
                    val info = _deviceInfo.value
                    if (info != null) { _parkedInvite.value = null; joinPairing(invite, sameDevice = true) }
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
        _enrolState.value = EnrolUiState.Error(_deviceInfo.value, message)
    }

    /** The user dismissed a parked invite without joining it. */
    fun discardParkedInvite() {
        _parkedInvite.value = null
    }

    /** First run: generate + seal the device keys (shows the user-presence prompt). */
    fun provisionDevice() {
        val ring = keyring ?: return
        viewModelScope.launch {
            _enrolState.value = EnrolUiState.Loading
            val result = withContext(Dispatchers.IO) { runCatching { ring.info() } }
            result.onSuccess { info ->
                _deviceInfo.value = info
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
                    clock = { System.currentTimeMillis() / 1000 },
                )
                val first = live.current() // mints the first proof — may prompt
                deviceCredential = live
                Credential.Device(first.cert, first.proof)
            }
        }
        adopted.onSuccess { cred ->
            credential = cred
            _loginState.value = LoginUiState.Approved(user = null)
            _enrolState.value = EnrolUiState.Enrolled(
                info = _deviceInfo.value ?: withContext(Dispatchers.IO) { ring.info() },
                registration = "Signed in with this device's admission.",
                needsAdmin = false,
            )
            loadSessionAuthority()
            loadLibrary()
        }.onFailure {
            _enrolState.value = EnrolUiState.Error(_deviceInfo.value, "could not sign with the device key: ${it.message}")
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
        val info = _deviceInfo.value ?: return
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

    /** `POST /enrol` again for a stored admission the node has not accepted (e.g. the proof could not be signed in the background). */
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
    private fun refreshMembership(): Boolean {
        val ring = keyring ?: return true
        val own = ring.certToken() ?: return true
        val usr = ring.userId() ?: return true
        val remote = runCatching { MembershipClient(rawTransport, config.baseUrl).fetch(usr) }.getOrNull() ?: return true
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
            else -> "This device is no longer a member of the identity " +
                "(${view.rejected[MembershipOp.hash(own)] ?: view.ineffective[MembershipOp.hash(own)] ?: "not admitted"})."
        }
        deviceCredential = null
        credential = null
        _deviceInfo.value = runCatching { ring.info() }.getOrNull() ?: _deviceInfo.value
        _enrolState.value = EnrolUiState.Removed(_deviceInfo.value ?: return false, why)
        _loginState.value = LoginUiState.Error("This device was removed from your Voidbind identity. $why")
        return false
    }

    /** After enrolment: start using the Device credential now. */
    fun useDeviceCredential() {
        val ring = keyring ?: return
        val cert = _deviceInfo.value?.certToken ?: return
        pairing.dismiss()
        showingPairing = false
        viewModelScope.launch { adoptDevice(ring, cert) }
    }

    /** Drop the stored admission (keys stay) and fall back to QR login. */
    fun forgetDevice() {
        val ring = keyring ?: return
        pairing.cancel()
        showingPairing = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { ring.clearCert() } }
            deviceCredential = null
            _parkedInvite.value = null
            val info = withContext(Dispatchers.IO) { runCatching { ring.info() }.getOrNull() }
            _deviceInfo.value = info
            _enrolState.value = if (info != null) EnrolUiState.Ready(info) else EnrolUiState.Unprovisioned
            signOut()
        }
    }

    /** A human-readable name for the node's device registry. */
    var deviceName: String = "heyarr-mobile"

    private fun loadLibrary() {
        // Guest-as-default: browse with Credential.Guest when nothing is enrolled.
        val cred = effectiveCredential()
        _libraryState.value = LibraryUiState.Loading
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                runCatching {
                    val works = LibraryClient(transport, config.baseUrl, cred).listWorks()
                    LibraryUiState.Loaded(works)
                }.getOrElse { LibraryUiState.Error(it.message ?: "failed to load library") }
            }
            _libraryState.value = state
        }
    }
}
