package one.rarebit.heyarr.mobile.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** UI state for the QR login screen. */
sealed interface LoginUiState {
    data object Idle : LoginUiState

    /** A login has started; [qrTuple] is the `voidbind:login?…` string to render as a QR. */
    data class AwaitingScan(val qrTuple: String) : LoginUiState
    data class Approved(val user: String?) : LoginUiState
    data class Error(val message: String) : LoginUiState
}

/**
 * The Voidbind QR login screen. Tapping "Sign in" begins a login against heyarr's
 * weblogin broker; the app shows the `voidbind:login?…` tuple for the user's
 * authenticator (voidbind-kmp) to scan; on approval the app holds a Bearer session
 * token and shows the library.
 *
 * The tuple is rendered as a real QR bitmap ([QrCode], ZXing) with the raw text kept
 * beneath it as a fallback for copy/paste or a phone that cannot scan.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onSignIn: () -> Unit,
    /**
     * Same-phone hand-off: when the Cruciform authenticator app is installed here, fire the
     * login tuple at it as an `ACTION_VIEW` intent (no second-phone QR dance). Null
     * hides the button — the QR is always there as the fallback.
     */
    onApproveOnThisPhone: ((qrTuple: String) -> Unit)? = null,
    /** Skip the QR session entirely: enrol this phone as a device (needs no login). */
    onEnrolDevice: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("heyarr", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in with Cruciform — scan the code or approve on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        when (state) {
            is LoginUiState.Idle, is LoginUiState.Error -> {
                if (state is LoginUiState.Error) {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                Button(onClick = onSignIn) { Text("Sign in with Cruciform") }
                if (onEnrolDevice != null) {
                    TextButton(onClick = onEnrolDevice, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Enrol this device instead")
                    }
                }
            }

            is LoginUiState.AwaitingScan -> AwaitingScan(state.qrTuple, onApproveOnThisPhone)

            is LoginUiState.Approved -> {
                Text("Signed in${state.user?.let { " as $it" } ?: ""}.")
            }
        }
    }
}

private val QR_SIZE = 240.dp

/** The QR session is open: approve on this phone when Cruciform is here, else scan [qrTuple]. */
@Composable
private fun AwaitingScan(qrTuple: String, onApproveOnThisPhone: ((qrTuple: String) -> Unit)?) {
    CircularProgressIndicator()
    if (onApproveOnThisPhone != null) {
        Button(
            onClick = { onApproveOnThisPhone(qrTuple) },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Approve on this phone") }
    }
    Text(
        if (onApproveOnThisPhone !=
            null
        ) {
            "…or scan this with your authenticator:"
        } else {
            "Scan this with your authenticator:"
        },
        modifier = Modifier.padding(top = 16.dp),
    )
    QrImage(qrTuple, modifier = Modifier.padding(top = 16.dp))
    Text(
        qrTuple,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** The login tuple as a QR bitmap, sized in dp and rendered pixel-sharp. */
@Composable
private fun QrImage(tuple: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val px = with(density) { QR_SIZE.roundToPx() }
    val image = remember(tuple, px) { QrCode.bitmap(tuple, px).asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = "Voidbind login QR code",
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
        modifier = modifier.size(QR_SIZE).background(Color.White),
    )
}
