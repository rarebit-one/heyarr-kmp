package one.rarebit.heyarr.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.desktop.device.DeviceKeyInfo
import one.rarebit.heyarr.desktop.device.KeyTier
import one.rarebit.heyarr.desktop.device.PairingState
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.screens.Field
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.KeyValue
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * The desktop "Sign in to save" enrol panel — the device-credential upgrade over the
 * guest default (issue #32). It drives the [one.rarebit.heyarr.desktop.device.PairingCoordinator]:
 * paste the `voidbind:pair?…` invite Cruciform shows under "Add a device", join the relay,
 * compare the security code shown on both screens, confirm, and the desktop receives its
 * sealed admission and registers it with the node — after which it presents a real
 * `Credential.Device` on every call, saved across launches.
 *
 * Bearer-token login stays available (the Settings connection panel) as an alternative.
 * When enrolment is not wired (previews / tests, [AppSession.pairing] == null) this shows
 * the guest note and nothing else — exactly the pre-#32 behaviour.
 */
@Composable
fun EnrolPanel(session: AppSession) {
    val coordinator = session.pairing
    val keyring = session.deviceKeyring
    if (coordinator == null || keyring == null) {
        Panel("This device (Voidbind)") {
            Text(
                "Device sign-in runs in the packaged desktop app. You are browsing as a guest; " +
                    "paste a bearer token in the connection panel above to save wants and follows.",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
            )
        }
        return
    }

    val state by coordinator.state.collectAsState()
    // Read the device keys lazily and only once per composition — info() provisions on first
    // call (generates + seals the signing seed), so keep it off the initial render path.
    var info by remember { mutableStateOf<DeviceKeyInfo?>(null) }
    var invite by remember { mutableStateOf("") }

    // When enrolment lands (or is forgotten), rebuild the session so guest-only surfaces flip.
    LaunchedEffect(state) {
        val s = state
        if (s is PairingState.Enrolled && s.registered) session.reauthenticated()
    }

    Panel("Sign in to save — this device", trailing = {
        if (info == null) GhostButton("Show device key", { info = keyring.info() })
    }) {
        Text(
            "Saving wants, follows and your place needs a signed-in device. Add this desktop from " +
                "Cruciform → \"Add a device\": it shows a pairing invite; paste it below, then compare the " +
                "security code on both screens.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )

        info?.let { i ->
            KeyValue("device key", i.deviceKey)
            KeyValue("protection", protectionText(i.tier), valueColor = Tokens.textMuted)
            if (i.isEnrolled) {
                KeyValue("enrolled to", i.userId ?: "an identity", valueColor = Tokens.success)
                SecondaryButton("Forget this device", {
                    keyring.clearCert()
                    coordinator.cancel()
                    info = keyring.info()
                    session.reauthenticated()
                }, compact = true, danger = true)
            }
        }

        when (val s = state) {
            is PairingState.Idle -> {
                Field("Pairing invite (voidbind:pair?…)", invite, placeholder = "voidbind:pair?v=3&relay=…") {
                    invite =
                        it
                }
                PrimaryButton("Join pairing", {
                    coordinator.start(invite.trim())
                }, compact = true, enabled = invite.isNotBlank())
            }

            is PairingState.Joining -> {
                Notice("Joining the pairing relay… keep Cruciform open on the other device.", tone = Tokens.slate)
                GhostButton("Cancel", { coordinator.cancel() })
            }

            is PairingState.CompareSas -> {
                Text("Security code", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
                Text(s.sas, style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary)
                if (s.awaitingAdmission) {
                    Notice(
                        "Codes matched — confirm on Cruciform to finish. Waiting for its approval…",
                        tone = Tokens.slate,
                    )
                    GhostButton("Cancel", { coordinator.cancel() })
                } else {
                    Text(
                        "Confirm this is the SAME code Cruciform shows before continuing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.textMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Codes match", { coordinator.confirmMatch() }, compact = true)
                        SecondaryButton("They differ", { coordinator.rejectMatch() }, compact = true, danger = true)
                    }
                }
            }

            is PairingState.Registering ->
                Notice("Registering this device with the node…", tone = Tokens.slate)

            is PairingState.Enrolled -> {
                Notice(
                    if (s.registered) {
                        "This device is enrolled. You're signed in — wants and follows will be saved."
                    } else {
                        s.registration
                    },
                    tone = if (s.registered) Tokens.success else Tokens.warning,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!s.registered &&
                        s.retriable
                    ) {
                        SecondaryButton("Register again", { coordinator.retryRegister() }, compact = true)
                    }
                    GhostButton("Done", {
                        coordinator.dismiss()
                        info = keyring.info()
                    })
                }
            }

            is PairingState.Failed -> {
                Notice(s.message, tone = Tokens.danger)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Try again", { coordinator.dismiss() }, compact = true)
                }
            }
        }

        Text(
            "The device signing key is generated on this machine and kept in the OS keychain " +
                "where one is available, else sealed at rest under ~/.local/share/heyarr-desktop; " +
                "it never leaves. A possession proof is signed per session.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textDisabled,
        )
    }
}

/** Honest one-line description of how the device seed is protected, by [KeyTier]. */
private fun protectionText(tier: KeyTier): String = when (tier) {
    KeyTier.STRONGBOX -> "hardware-backed key (StrongBox)"
    KeyTier.TEE -> "hardware-backed key (secure element)"
    KeyTier.KEYCHAIN -> "held in the OS keychain (macOS Keychain / libsecret)"
    KeyTier.SOFTWARE -> "software key, sealed at rest (no secure element on desktop)"
}
