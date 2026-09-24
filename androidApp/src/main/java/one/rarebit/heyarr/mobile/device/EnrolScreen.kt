package one.rarebit.heyarr.mobile.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** UI state for the "Enrol this device" screen. */
sealed interface EnrolUiState {
    /** Keys are being provisioned / read (may be waiting on the biometric prompt). */
    data object Loading : EnrolUiState

    /** Fresh install: no device key yet. Creating one shows the user-presence prompt. */
    data object Unprovisioned : EnrolUiState

    /** Keys exist; no cert yet. Waiting for the user to start or join a pairing. */
    data class Ready(val info: DeviceKeyInfo) : EnrolUiState

    /**
     * This device **joined** an invite a member device rendered — scanned or pasted —
     * and is connecting to the relay it names to run the handshake. [inviteQr] is the
     * `voidbind:pair?…` tuple (v3: it carries the identity, `usr`).
     */
    data class Joining(
        val info: DeviceKeyInfo,
        val inviteQr: String,
        val sameDevice: Boolean = false,
        /** When the relay session expires (wall-clock millis; 0 = unknown) — the countdown. */
        val deadlineMillis: Long = 0L,
    ) : EnrolUiState

    /**
     * Handshake done: show the SAS for the human to compare on both screens. [sameDevice]
     * when the invite came from Cruciform on THIS phone (voidbind-kmp ADR-0006) — the
     * other screen is one app-switch away, and the confirm happens there. After
     * "codes match", [awaitingAdmission]: the SAS stays up while this phone waits (until
     * [deadlineMillis]) for the admission Cruciform seals once the human confirms there.
     */
    data class CompareSas(
        val info: DeviceKeyInfo,
        val sas: String,
        val sameDevice: Boolean = false,
        val deadlineMillis: Long = 0L,
        val awaitingAdmission: Boolean = false,
        /**
         * Cruciform is on this phone and took our `cruciform://pair-joined` report
         * (voidbind-kmp ADR-0008): the two apps compared the device key and the SAS over
         * the local intent channel, so there is nothing here for the human to compare —
         * they approve once, in Cruciform. The SAS is still held, and the screen reveals
         * it as a fallback if Cruciform has not come back after a while.
         */
        val handedOff: Boolean = false,
    ) : EnrolUiState

    /** The admission is stored; `POST /enrol` is in flight. */
    data class Registering(val info: DeviceKeyInfo) : EnrolUiState

    /**
     * The admission was delivered, verified and stored; [registration] says whether the
     * node knows it yet. [retriable]: registering failed for a reason a second attempt
     * could fix (the node unreachable, or the possession proof could not be signed while
     * the app was in the background), as opposed to a node that [needsAdmin].
     */
    data class Enrolled(
        val info: DeviceKeyInfo,
        val registration: String,
        val needsAdmin: Boolean,
        val retriable: Boolean = false,
    ) : EnrolUiState

    /**
     * The node refused this device's credential and, on re-reading the identity's
     * membership (`GET /membership/{usr}`), this device is no longer a member — another
     * member removed it, or its add lapsed. [message] says which. The admission is kept
     * on disk until the user forgets it; nothing retries in a loop.
     */
    data class Removed(val info: DeviceKeyInfo, val message: String) : EnrolUiState

    /** [kind] classifies a pairing failure (relay unreachable vs. timed out vs. …); null for anything else. */
    data class Error(val info: DeviceKeyInfo?, val message: String, val kind: PairingFailure? = null) : EnrolUiState
}

/** `m:ss` left until [deadlineMillis], floored at zero — the relay session countdown. */
internal fun countdownText(deadlineMillis: Long, nowMillis: Long): String {
    val left = ((deadlineMillis - nowMillis).coerceAtLeast(0L) + 999) / 1000
    return "%d:%02d".format(left / 60, left % 60)
}

/** A title for a pairing failure the human can act on; the message says the rest. */
internal fun failureTitle(kind: PairingFailure?): String = when (kind) {
    PairingFailure.UNREACHABLE -> "Couldn't reach the relay"
    PairingFailure.TIMEOUT -> "Cruciform didn't answer in time"
    PairingFailure.REJECTED -> "The relay refused this invite"
    PairingFailure.PROTOCOL -> "The pairing didn't check out"
    PairingFailure.MISMATCH -> "Codes differed — aborted"
    PairingFailure.INTERRUPTED -> "The pairing was interrupted"
    PairingFailure.EXPIRED -> "The pairing expired"
    PairingFailure.INVALID -> "Not a pairing invite"
    null -> "Something went wrong"
}

/** `<prefix>m:ss<suffix>` — the relay-session countdown, ticking with [now]. */
@Composable
private fun CountdownLine(prefix: String, deadlineMillis: Long, now: Long, suffix: String) {
    Text(
        prefix + countdownText(deadlineMillis, now) + suffix,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * How long the one-tap hand-off gets before this screen shows the SAS anyway (ADR-0008).
 * Long enough that the ordinary path — Cruciform foregrounding and putting its "Allow"
 * question up — never trips it, short enough that a phone left staring at a spinner
 * because Cruciform is an older build gets its human path back quickly.
 */
private const val SAS_FALLBACK_MILLIS = 20_000L

/** Ticks once a second while composed, for the countdowns. */
@Composable
private fun rememberNowMillis(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The "Enrol this device" screen. This phone is the **new device** — voidbind-client's
 * `DevicePairing`, the relay *responder*: it shows its device key + honest hardware
 * tier, **joins** the `voidbind:pair?…` invite a member device rendered (Cruciform's
 * "Add a device" on another phone, or `voidbind pair-initiate` on the Mac — v3
 * invites name the identity, so only a member can mint one; ADR-0005), scanned with
 * the camera or pasted — both through [PairInvite] (the library's parser) — runs the
 * commit-before-reveal handshake, judges the initiator's membership before any SAS
 * exists, shows the SAS for the human to compare, and — only after they match —
 * receives the member-signed **add op** plus the ops that authorise it, sealed to
 * this device's X25519 key.
 *
 * Until an admission exists the app keeps using the QR web-login session (read-only).
 */
@Composable
fun EnrolScreen(
    state: EnrolUiState,
    onCreateKey: () -> Unit,
    onJoinInvite: (inviteQr: String) -> Unit,
    onSasMatches: () -> Unit,
    onSasMismatch: () -> Unit,
    onRetry: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
    /** An invite from Cruciform on this phone waiting for the device key (see [AppViewModel.receiveInviteLink]). */
    parkedInvite: String? = null,
    onDiscardParked: () -> Unit = {},
    /** Give up on the relay wait in flight (Joining, or awaiting the admission). */
    onCancelPairing: () -> Unit = {},
    /** `POST /enrol` again for a stored admission the node has not accepted yet. */
    onRegister: () -> Unit = {},
    /**
     * Show the device's key material and the "Forget enrolment" control — the Device page
     * under Settings. The pre-sign-in host hides both: an enrolment in progress needs
     * neither, and a registered admission signs the phone in by itself (EnrolAdvance).
     */
    manage: Boolean = false,
) {
    val now = rememberNowMillis()
    // When the one-tap hand-off to Cruciform started, so the SAS fallback can appear if
    // it never comes back (ADR-0008). Reset per session, not per recomposition.
    val handedOffSession = (state as? EnrolUiState.CompareSas)?.takeIf { it.handedOff }?.sas
    val handedOffSince = remember(handedOffSession) { System.currentTimeMillis() }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (manage) "This device" else "Enrol this device", style = MaterialTheme.typography.headlineMedium)
        val info = when (state) {
            is EnrolUiState.Ready -> state.info
            is EnrolUiState.Joining -> state.info
            is EnrolUiState.CompareSas -> state.info
            is EnrolUiState.Registering -> state.info
            is EnrolUiState.Enrolled -> state.info
            is EnrolUiState.Removed -> state.info
            is EnrolUiState.Error -> state.info
            EnrolUiState.Loading, EnrolUiState.Unprovisioned -> null
        }
        if (info != null) DeviceKeyLine(info, full = manage)
        if (parkedInvite != null) ParkedInviteCard(parkedInvite, onDiscardParked)
        when (state) {
            EnrolUiState.Loading -> {
                CircularProgressIndicator()
                Text("Creating this phone's device key… confirm the prompt if asked.")
            }

            is EnrolUiState.Registering -> {
                CircularProgressIndicator()
                Text("Admission received. Registering with the node… confirm the prompt if asked.")
            }

            EnrolUiState.Unprovisioned -> {
                // With a parked invite the key is being created without a tap; the Loading
                // state follows at once, so this branch only matters on the manual path.
                Text("No device key yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This phone gets its own Ed25519 device key, sealed by the secure hardware. " +
                        "You confirm it's you once.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onCreateKey) { Text("Create device key") }
            }

            is EnrolUiState.Ready -> {
                Text("Pair with your identity", style = MaterialTheme.typography.titleMedium)
                Text(
                    "In Cruciform, open \"Add a device\" and choose \"Send to heyarr\" — this phone joins " +
                        "the invite and you allow it there, with your fingerprint.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OtherDeviceEntry(onJoinInvite)
            }

            is EnrolUiState.Joining -> {
                Text(
                    if (state.sameDevice) "Joining Cruciform's invite…" else "Joining the pairing…",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (state.sameDevice) {
                        "Cruciform will come back to the front on its own and ask you to allow this phone."
                    } else {
                        "The other device must prove it is a member of your identity first; then you'll " +
                            "compare a security code with the one on its screen."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                if (state.deadlineMillis > 0) {
                    CountdownLine(
                        (if (state.sameDevice) "Cruciform" else "The other device") + " has ",
                        state.deadlineMillis,
                        now,
                        " left to answer — this keeps waiting even if you switch apps.",
                    )
                }
                OutlinedButton(onClick = onCancelPairing) { Text("Cancel pairing") }
            }

            is EnrolUiState.CompareSas -> if (state.handedOff) {
                // ADR-0008: the apps compared. One question, in Cruciform, behind its
                // fingerprint — no code on this screen unless it goes quiet.
                Text("Allow it in Cruciform", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The two apps checked each other's keys directly, so there is no code to compare. " +
                        "Confirm in Cruciform and you'll land back here, signed in.",
                    style = MaterialTheme.typography.bodySmall,
                )
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                if (state.deadlineMillis > 0) {
                    CountdownLine("", state.deadlineMillis, now, " left before the relay session expires.")
                }
                // The fallback: if Cruciform never puts the question up (an old build, a
                // refused launch), the human path is still there — show the code and say
                // what to do with it, rather than spinning forever with nothing to act on.
                if (now - handedOffSince >= SAS_FALLBACK_MILLIS) {
                    Text(
                        "Cruciform hasn't come back yet. If it is showing a security code instead of " +
                            "an \"Allow\" question, compare it with this one and confirm there:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SasCard(state.sas)
                }
                OutlinedButton(onClick = onCancelPairing) { Text("Cancel pairing") }
            } else {
                Text("Compare the security code", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.sameDevice) {
                        "Cruciform on this phone is showing a code too. Switch back to it (recent apps) " +
                            "and compare — you approve there, with your fingerprint. Come back here after."
                    } else {
                        "The other device — Cruciform, or the Mac's terminal — is showing a code too."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                SasCard(state.sas)
                if (state.awaitingAdmission) {
                    Text(
                        if (state.sameDevice) {
                            "Waiting for Cruciform to approve — this finishes on its own, even while you're over there."
                        } else {
                            "Waiting for the other device to approve — confirm there."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    if (state.deadlineMillis > 0) {
                        CountdownLine("", state.deadlineMillis, now, " left before the relay session expires.")
                    }
                    OutlinedButton(onClick = onCancelPairing) { Text("Cancel pairing") }
                } else {
                    Text(
                        "Tap \"Codes match\" only if both show the SAME number — that comparison " +
                            "is what stops an attacker on the network from standing in the middle.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onSasMatches) { Text("Codes match") }
                        OutlinedButton(onClick = onSasMismatch) { Text("They differ") }
                    }
                    if (state.deadlineMillis > 0) {
                        CountdownLine("", state.deadlineMillis, now, " left before the relay session expires.")
                    }
                }
            }

            is EnrolUiState.Enrolled -> {
                val settled = !state.needsAdmin && !state.retriable
                if (settled && !manage) {
                    // The registered admission signs the phone in by itself (EnrolAdvance);
                    // this is the beat before the shell appears.
                    Text("Enrolled — signing in…", style = MaterialTheme.typography.titleMedium)
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Text(if (settled) "Enrolled" else "Admission stored", style = MaterialTheme.typography.titleMedium)
                    Text(state.registration, style = MaterialTheme.typography.bodySmall,
                        color = if (state.needsAdmin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.needsAdmin && info?.certToken != null) {
                        Text("Admitting op to register:", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer {
                            Text(info.certToken, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (settled) {
                        Text(
                            "This device signs in with its own admission (${info?.knownOps?.size ?: 0} membership " +
                                "op(s) known). Whether it can also manage follows depends on the grant an admin gives its key.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.retriable) Button(onClick = onRegister) { Text("Register again") }
                        if (manage || !settled) OutlinedButton(onClick = onForget) { Text("Forget enrolment") }
                    }
                }
            }

            is EnrolUiState.Removed -> {
                Text("This device was removed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Its credential is no longer honoured, so nothing here retries. Forget the enrolment " +
                        "to go back to the QR sign-in, and have a member device admit this phone again if " +
                        "that was a mistake — only the recovery secret can re-add a removed device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onForget) { Text("Forget enrolment") }
            }

            is EnrolUiState.Error -> {
                Text(failureTitle(state.kind), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onRetry) { Text("Try again") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The cross-device path, folded behind one line until it is wanted. */
@Composable
private fun OtherDeviceEntry(onJoin: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("Pair from another device instead") }
    } else {
        Text("Pair from another device", style = MaterialTheme.typography.titleSmall)
        InviteEntry(onJoin)
    }
}

/**
 * An invite Cruciform on this phone handed us before this phone could join it — no
 * device key yet (creating one asks for a fingerprint, so a link never triggers that
 * by itself), or the keys still loading. It joins automatically once the key exists.
 */
@Composable
private fun ParkedInviteCard(inviteQr: String, onDiscard: () -> Unit) {
    val parsed = PairInvite.check(inviteQr) as? PairInvite.Valid
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Invite from Cruciform on this phone", style = MaterialTheme.typography.titleSmall)
            Text(
                "Cruciform's \"Add a device\" sent this phone an invite. Confirm the fingerprint prompt " +
                    "to create this phone's key; the invite is joined on its own after that.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Identity: " + (parsed?.user?.take(24)?.plus("…") ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            OutlinedButton(onClick = onDiscard) { Text("Discard invite") }
        }
    }
}

@Composable
private fun DeviceKeyLine(info: DeviceKeyInfo, full: Boolean) {
    val tier = when (info.tier) {
        KeyTier.STRONGBOX -> "StrongBox"
        KeyTier.TEE -> "TEE"
        KeyTier.SOFTWARE -> "software only — NOT hardware-backed"
    }
    val status = if (info.isEnrolled) "enrolled" else "not enrolled yet"
    if (!full) {
        Text(
            "Device key ${info.deviceKey.removePrefix("ed25519:").take(8)}… · $tier · $status",
            style = MaterialTheme.typography.bodySmall,
            color = if (info.tier == KeyTier.SOFTWARE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("This device's key", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(info.deviceKey, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text(
                "Key storage: $tier (hardware-sealed, user-presence gated)".takeIf { info.tier != KeyTier.SOFTWARE }
                    ?: "Key storage: $tier",
                style = MaterialTheme.typography.bodySmall,
                color = if (info.tier == KeyTier.SOFTWARE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Status: $status", style = MaterialTheme.typography.bodySmall)
            if (info.userId != null) {
                SelectionContainer {
                    Text("Identity: " + info.userId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/**
 * The short authentication string, big enough to read across the room and compare
 * digit-by-digit against the Mac's terminal. Monospace + letter-spacing so `1`/`7` and
 * `0`/`8` don't blur together; a single line that never wraps.
 */
@Composable
private fun SasCard(sas: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp)) {
            Text(
                "SECURITY CODE",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                sas,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/**
 * Join an invite a member device rendered: **scan** its QR with the camera, or
 * **paste** its text. Both go through [PairInvite.check] — the library's parser — and
 * anything that isn't a `voidbind:pair?…` invite is refused inline with a reason, while
 * the scanner keeps looking so the user can just point at the right code.
 */
@Composable
private fun InviteEntry(onJoin: (String) -> Unit) {
    var invite by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }

    /** Returns true when the text was a valid invite and the join was started. */
    fun submit(raw: String): Boolean = when (val r = PairInvite.check(raw)) {
        is PairInvite.Valid -> {
            problem = null
            scanning = false
            onJoin(r.inviteQr)
            true
        }

        is PairInvite.Invalid -> {
            problem = r.message
            false
        }
    }

    Text(
        "Cruciform on another phone, or `voidbind pair-initiate` on your Mac, shows an invite QR. " +
            "Scan it here, or paste the voidbind:pair?… text.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (scanning) {
        QrScanner(
            onQr = { raw -> submit(raw) },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            noPermission = {
                Text(
                    "Camera permission is needed to scan the invite — allow it, or paste the invite below.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
        OutlinedButton(onClick = { scanning = false }) { Text("Stop scanning") }
    } else {
        Button(onClick = {
            problem = null
            scanning = true
        }) { Text("Scan invite QR") }
    }
    OutlinedTextField(
        value = invite,
        onValueChange = { invite = it },
        label = { Text("voidbind:pair?v=3&relay=…&session=…&salt=…&usr=…") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { submit(invite) }, enabled = invite.isNotBlank()) {
        Text("Join pairing")
    }
    problem?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
