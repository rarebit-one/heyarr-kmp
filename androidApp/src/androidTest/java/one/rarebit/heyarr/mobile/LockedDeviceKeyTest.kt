package one.rarebit.heyarr.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.mobile.device.DeviceKeyInfo
import one.rarebit.heyarr.mobile.device.EnrolAdvance
import one.rarebit.heyarr.mobile.device.EnrolScreen
import one.rarebit.heyarr.mobile.device.EnrolUiState
import one.rarebit.heyarr.mobile.device.KeyTier
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.preview.FakeHeyarrTransport
import one.rarebit.heyarr.mobile.settings.InMemorySettingsStore
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.PhoneExternalMetadata
import one.rarebit.heyarr.mobile.state.RecentSearches
import one.rarebit.heyarr.mobile.theme.HeyarrTheme
import one.rarebit.heyarr.mobile.ui.screens.SettingsScreen
import one.rarebit.heyarr.mobile.ui.screens.SettingsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * An enrolled phone whose device key is locked (heyarr-kmp#243): the fingerprint prompt at
 * launch was dismissed, so the phone browses as a guest. On a device, the Device page and
 * Settings → This device both say it is still enrolled and offer "Unlock", and the button
 * reaches the sign-in again. A real lapsed Keystore window cannot be faked on the emulator
 * (it needs an admitted key behind a secure lock screen), so the state is handed in; the
 * state logic itself is the JVM tests' (DeviceEnrolmentTest, EnrolAdvanceTest).
 */
@RunWith(AndroidJUnit4::class)
class LockedDeviceKeyTest {
    @get:Rule val compose = createComposeRule()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun stopSession() {
        sessionScope.cancel()
    }

    private val enrolled = DeviceKeyInfo("ed25519:" + "aa".repeat(32), "x25519:bb", KeyTier.TEE, certToken = "op.token")

    @Test fun theDevicePageOffersUnlockAndItAsksForTheSignInAgain() {
        var state: EnrolUiState by mutableStateOf(EnrolUiState.Locked(enrolled))
        var retries = 0
        compose.setContent {
            HeyarrTheme {
                EnrolScreen(
                    state = state,
                    onCreateKey = {},
                    onJoinInvite = {},
                    onSasMatches = {},
                    onSasMismatch = {},
                    onRetry = { retries++ },
                    onForget = {},
                    manage = true,
                )
            }
        }
        compose.onNodeWithText("Unlock with your fingerprint to sign in").assertIsDisplayed()
        compose.onNodeWithText("Forget enrolment").assertIsDisplayed()
        compose.onNodeWithText("Unlock").performClick()
        compose.runOnIdle { assertEquals("Unlock runs the sign-in once", 1, retries) }

        // The prompt is up: no second button to stack a second prompt on.
        compose.runOnIdle { state = EnrolUiState.Locked(enrolled, unlocking = true) }
        compose.onNodeWithText("Confirm the prompt to sign in.").assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertDoesNotExist()

        // Cancelled again: still Locked, the button is back.
        compose.runOnIdle { state = EnrolUiState.Locked(enrolled) }
        compose.onNodeWithText("Unlock").performClick()
        compose.runOnIdle { assertEquals(2, retries) }
    }

    @Test fun settingsSaysTheLockedPhoneIsEnrolledAndOffersUnlock() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val session = AppSession(
            api = HeyarrApi(FakeHeyarrTransport(), "https://node.example:7777", Credential.Guest),
            defaultProfile = "everyday",
            settings = InMemorySettingsStore().apply {
                reduceMotion = true
                externalMetadata = false
            },
            external = PhoneExternalMetadata.none(),
            recent = RecentSearches(File(ctx.cacheDir, "locked-recent")),
            scope = sessionScope,
        )
        var unlocks = 0
        var signIns = 0
        val summary = EnrolAdvance.deviceSummary(EnrolUiState.Locked(enrolled))!!
        compose.setContent {
            HeyarrTheme {
                SettingsScreen(
                    session,
                    SettingsState(),
                    HeyarrConfig.resolve(null, null),
                    authority = null,
                    onSaveConnection = { _, _ -> },
                    onResetConnection = {},
                    onSignOut = {},
                    onTelemetry = {},
                    onDevice = {},
                    onSourcesChanged = {},
                    deviceSummary = summary,
                    onUnlockDevice = { unlocks++ },
                    isGuest = true,
                    onSignInToSave = { signIns++ },
                )
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(summary))
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNodeWithText("Unlock").performClick()
        compose.runOnIdle {
            assertEquals("This device's button unlocks", 1, unlocks)
            assertEquals("not a QR sign-in", 0, signIns)
        }
    }
}
