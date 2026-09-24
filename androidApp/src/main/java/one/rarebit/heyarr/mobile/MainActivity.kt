package one.rarebit.heyarr.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import one.rarebit.heyarr.mobile.device.AndroidBiometricGate
import one.rarebit.heyarr.mobile.device.DeviceKeyring
import one.rarebit.heyarr.mobile.device.EnrolScreen
import one.rarebit.heyarr.mobile.device.HandoffLauncher
import one.rarebit.heyarr.mobile.device.PairDeepLink
import one.rarebit.heyarr.mobile.login.LoginScreen
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.login.VoidbindHandoff
import one.rarebit.heyarr.mobile.nav.HeyarrNavHost
import one.rarebit.heyarr.mobile.playback.MediaCodecCapabilities
import one.rarebit.heyarr.mobile.theme.HeyarrTheme
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.screens.ConnectionFields
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.SectionHeader

/**
 * A pairing invite that arrived by deep link (`heyarr-mobile://pair?invite=…`) — from
 * Cruciform's "Add a device" on this same phone (voidbind-kmp ADR-0006). [seq] makes two
 * identical links distinct so the second re-fires. Either a usable [inviteQr] or a
 * [problem] to show.
 */
private data class LinkedInvite(
    val inviteQr: String?,
    val problem: String?,
    val seq: Int,
    val done: Boolean = false,
    /** Cruciform refused the one-tap report: `(session, its reason)`. */
    val refusal: Pair<String, String>? = null,
)

/**
 * A [FragmentActivity] because `BiometricPrompt` — which gates every use of the
 * hardware-sealed device key — binds to one. `singleTop` so the authenticator's
 * `heyarr-mobile://login` callback (and its `heyarr-mobile://pair` handoff) foregrounds
 * this instance via [onNewIntent] instead of stacking; a cold start routes the launching
 * intent in [onCreate]. Everything draws under [HeyarrTheme]; the signed-in app is
 * [HeyarrNavHost].
 */
@UnstableApi
class MainActivity : FragmentActivity() {

    private var linkedInvite by mutableStateOf<LinkedInvite?>(null)
    private var linkSeq = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    /** Ask for POST_NOTIFICATIONS once a pairing starts, so its foreground notice can show. */
    private fun ensureNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeLink(intent)?.let { linkedInvite = it }
    }

    /** The `heyarr-mobile://pair` handoff, if this intent is one; null for anything else. */
    private fun routeLink(intent: Intent?): LinkedInvite? = when (val r = PairDeepLink.route(intent?.action, intent?.dataString)) {
        is PairDeepLink.Invite -> LinkedInvite(r.inviteQr, null, ++linkSeq)

        is PairDeepLink.Invalid -> LinkedInvite(null, r.message, ++linkSeq)

        // The one-tap return leg (voidbind-kmp ADR-0008): nothing to join, nothing to
        // trust — just bring the human back to the Device screen, where the app-scoped
        // pairing has (or is about to have) reached Enrolled on its own. A refusal is
        // the one thing it can add: Cruciform's verdict, so the wait ends now.
        is PairDeepLink.Done -> LinkedInvite(
            null,
            null,
            ++linkSeq,
            done = true,
            refusal = if (r.refused && r.session != null) r.session to (r.reason ?: "the report did not match the relay.") else null,
        )

        null -> null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge on every SDK (35+ forces it anyway); the shell keeps its
        // content inside the safe-drawing insets.
        enableEdgeToEdge()
        // Only a fresh launch routes the launching intent. Android re-delivers it on every
        // recreation (rotation, process death), and re-routing a consumed invite put an
        // already-enrolled phone on "This phone is already enrolled" after a rotation.
        if (savedInstanceState == null) linkedInvite = routeLink(intent)
        val appContext = applicationContext
        // This phone's device keys, biometric-gated through this activity. Attached
        // once per activity; the ViewModel outlives rotations and keeps the session.
        val keyring = DeviceKeyring(this, AndroidBiometricGate(this))
        val app = application as HeyarrApp
        app.deviceKeyring = keyring
        app.deviceName = "heyarr-mobile on ${android.os.Build.MODEL}"
        val voidbindInstalled = HandoffLauncher.canOpen(this, "voidbind:login?id=probe&rp=probe")
        setContent {
            HeyarrTheme {
                val vm: AppViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            AppViewModel(
                                settings = app.graph.settings,
                                pairing = app.pairing,
                                rawTransport = app.graph.rawTransport,
                                deviceIds = app.graph.deviceIds,
                                spaceRegistry = app.graph.spaceRegistry,
                                mdns = app.graph.mdns,
                            )
                        }
                    },
                )
                LaunchedEffect(vm) {
                    vm.deviceName = app.deviceName
                    app.credentialProvider = { vm.credentialOrNull() }
                    // Posters and range reads go out through the shared client and pick
                    // up the live credential here, without ever holding it themselves.
                    app.graph.authHeader.provider = { vm.liveAuthorizationHeader() }
                    vm.attachDevice(keyring)
                    // Auto-discovery: if an `_heyarr._tcp` node is on this LAN, browse against
                    // it (guest-as-default lands on the right node with no typing).
                    vm.autoDiscover()
                    // What this phone can decode, for the playback planner (#432).
                    vm.playback.capabilities = MediaCodecCapabilities.probe(appContext)
                    vm.attachAudio(app.graph.audio)
                    app.reporter = vm.progressReporter
                    app.readingPositionSync = vm.readingPositionSync
                }
                val enrolState by vm.enrolState.collectAsStateWithLifecycle()
                val loginState by vm.loginState.collectAsStateWithLifecycle()
                // Ask for notifications once the phone is signed in and settled, not in the
                // middle of the pairing hand-off where it landed on top of the fingerprint
                // prompt and the app switch to Cruciform.
                LaunchedEffect(loginState is LoginUiState.Approved) {
                    if (loginState is LoginUiState.Approved) ensureNotificationPermission()
                }
                val config by vm.configState.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showEnrol by rememberSaveable { mutableStateOf(false) }
                // The "Sign in to save" upgrade: guests browse in the shell by default; this
                // flag raises the QR/device sign-in over it when a gated affordance is tapped.
                var showLogin by rememberSaveable { mutableStateOf(false) }
                val parkedInvite by vm.parkedInvite.collectAsStateWithLifecycle()
                val context = LocalContext.current

                // Cruciform handed us an invite (or a broken link): route it into the same
                // join path a scan takes, and put the Enrol screen in front — the standalone
                // one before sign-in, the Device route once signed in.
                // Not saveable on purpose: a restored seq would re-navigate to the Device
                // route after process death, while the link it came from is gone.
                var focusDevice by remember { mutableStateOf(0) }
                val link = linkedInvite
                LaunchedEffect(link) {
                    link ?: return@LaunchedEffect
                    when {
                        link.refusal != null -> vm.pairingRefused(link.refusal.first, link.refusal.second)
                        link.done -> Unit
                        link.inviteQr != null -> vm.receiveInviteLink(link.inviteQr)
                        else -> vm.rejectInviteLink(link.problem ?: "bad invite link")
                    }
                    showSettings = false
                    showEnrol = true
                    focusDevice = link.seq
                }
                // A registered admission signs the phone in by itself (EnrolAdvance): drop the
                // enrol/login frames and the deep-link focus so the shell opens on Home, not Device.
                LaunchedEffect(loginState is LoginUiState.Approved) {
                    if (loginState is LoginUiState.Approved) {
                        showEnrol = false
                        showLogin = false
                        focusDevice = 0
                    }
                }

                Box(Modifier.fillMaxSize().background(Tokens.bgBase)) {
                    when {
                        showSettings -> {
                            BackHandler { showSettings = false }
                            PreLoginScreen(subtitle = config.baseUrl, onSettings = null) {
                                SectionHeader("Settings")
                                Panel("heyarr connection") {
                                    ConnectionFields(config, onSave = { url, profile ->
                                        vm.updateSettings(url, profile)
                                        showSettings = false
                                    }, onReset = {
                                        vm.resetSettings()
                                        showSettings = false
                                    })
                                }
                                GhostButton("Close", { showSettings = false })
                            }
                        }

                        // The "Sign in to save" upgrade raised over the guest shell.
                        showLogin && loginState !is LoginUiState.Approved -> {
                            BackHandler { showLogin = false }
                            PreLoginScreen(subtitle = config.baseUrl, onSettings = { showSettings = true }) {
                                LoginScreen(
                                    state = loginState,
                                    onSignIn = vm::signIn,
                                    onApproveOnThisPhone = if (voidbindInstalled) {
                                        { tuple -> HandoffLauncher.open(context, VoidbindHandoff.loginUri(tuple)) }
                                    } else {
                                        null
                                    },
                                    onEnrolDevice = {
                                        showLogin = false
                                        showEnrol = true
                                    },
                                    modifier = Modifier,
                                )
                                GhostButton("Keep browsing as guest", { showLogin = false })
                            }
                        }

                        showEnrol -> {
                            // Enrolment needs no session: pairing runs over the relay, and an enrolled
                            // phone then signs in with its cert instead of a QR login.
                            BackHandler { showEnrol = false }
                            PreLoginScreen(subtitle = config.baseUrl, onSettings = null, scroll = false) {
                                EnrolScreen(
                                    state = enrolState,
                                    onCreateKey = vm::provisionDevice,
                                    onJoinInvite = vm::joinPairing,
                                    onSasMatches = vm::confirmSas,
                                    onSasMismatch = vm::rejectSas,
                                    onRetry = vm::retryEnrol,
                                    onForget = vm::forgetDevice,
                                    modifier = Modifier,
                                    parkedInvite = parkedInvite,
                                    onDiscardParked = vm::discardParkedInvite,
                                    onCancelPairing = vm::cancelPairing,
                                    onRegister = vm::registerDevice,
                                )
                            }
                        }

                        // Guest-as-default: the browsing shell is what the app opens on, whether
                        // signed in or browsing anonymously. "Sign in to save" raises showLogin.
                        else -> HeyarrNavHost(vm = vm, graph = app.graph, focusDevice = focusDevice, onSignInToSave = { showLogin = true })
                    }
                }
            }
        }
    }
}

/** The pre-sign-in frame: a wordmark, the node we point at, and a gear for the connection settings. */
@Composable
private fun PreLoginScreen(
    subtitle: String,
    onSettings: (() -> Unit)?,
    /**
     * Whether the frame scrolls its content. False for content that scrolls itself
     * (EnrolScreen): a vertically scrolling column inside another one is measured with
     * an infinite height and Compose throws, which killed the app the moment Cruciform
     * handed over an invite. Such content gets the remaining height instead.
     */
    scroll: Boolean = true,
    content: @Composable () -> Unit,
) {
    val frame = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
    Column(
        (if (scroll) frame.verticalScroll(rememberScrollState()) else frame).padding(horizontal = Tokens.screenPadding, vertical = Tokens.s3),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("heyarr", style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onSettings != null) IconButtonRound(Icons.Rounded.Settings, "Connection settings", onSettings, size = 40.dp)
        }
        if (scroll) {
            content()
            Spacer(Modifier.padding(8.dp))
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}
