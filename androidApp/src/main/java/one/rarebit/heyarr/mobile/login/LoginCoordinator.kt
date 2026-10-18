package one.rarebit.heyarr.mobile.login

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * A QR login this phone has open, persisted (in [PendingLoginStore]) from the moment
 * the node hands out its id until the login ends. Unlike a pairing, a login IS
 * resumable: its outcome is read by polling `GET /login/{id}`, so after a process
 * death the poll simply picks up where it stopped, for what is left of the window.
 */
data class PendingLogin(
    /** The node the login was opened on; its approval is only good for that node. */
    val rpBase: String,
    val loginId: String,
    /** The `void-which-binds:login?…` tuple the QR shows and Cruciform opens. */
    val qrTuple: String,
    /** Wall-clock millis when the node opened the login. */
    val startedAtMillis: Long,
    /** The user handed the login to Cruciform on THIS phone ("Approve on this phone"). */
    val handedOff: Boolean = false,
)

/** Where the pending login lives: SharedPreferences on the phone, memory in tests. */
interface PendingLoginStore {
    var pending: PendingLogin?
}

class InMemoryPendingLoginStore : PendingLoginStore {
    override var pending: PendingLogin? = null
}

/** The state of the app's one QR login, observed by the ViewModel. */
sealed interface LoginFlow {
    data object Idle : LoginFlow

    /** Opening the login on the node (`POST /login`). */
    data class Starting(val rpBase: String) : LoginFlow

    /** Open and polled until approved, declined or expired. */
    data class Awaiting(val pending: PendingLogin) : LoginFlow

    /**
     * Approved: the Bearer session token for [rpBase]. Applied, then [LoginCoordinator.consume]d;
     * until then the open login stays persisted, and a handed-off one keeps the foreground service.
     */
    data class Approved(
        val rpBase: String,
        val sessionToken: String,
        val user: String?,
        val handedOff: Boolean = false,
    ) : LoginFlow

    data class Failed(val message: String) : LoginFlow
}

/**
 * Whether the login needs the process held up (the foreground service): handed off to Cruciform
 * and still waiting, or approved there and not yet applied by the app.
 */
val LoginFlow.holdsProcess: Boolean
    get() = (this is LoginFlow.Awaiting && pending.handedOff) || (this is LoginFlow.Approved && handedOff)

/**
 * The app-scoped holder for the QR login and its poll (heyarr-kmp#172). It lives in the
 * `Application` ([one.rarebit.heyarr.mobile.HeyarrApp]) on an app-wide [scope], NOT a
 * ViewModel: "Approve on this phone" sends the user to Cruciform, and while Cruciform is
 * in front Android may freeze or kill this backgrounded process. A ViewModel-scoped poll
 * died with it, and the restored Activity showed a fresh "Sign in" while the node held an
 * approval nobody collected. Here the poll outlives the Activity; a foreground service
 * keeps the process up while the login is handed off; and the pending login is persisted
 * so a restart after a process death resumes the poll rather than losing the approval.
 */
class LoginCoordinator(
    private val scope: CoroutineScope,
    private val store: PendingLoginStore,
    private val login: (rpBase: String) -> VoidbindLogin,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = LOGIN_TIMEOUT_MILLIS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<LoginFlow>(LoginFlow.Idle)
    val state: StateFlow<LoginFlow> = _state.asStateFlow()

    private var job: Job? = null

    /** Bumped by every start, resume and cancel: a poll from an older one is ignored. */
    private var generation = 0L

    init {
        store.pending?.let(::resume)
    }

    /**
     * Open a login on [rpBase] and poll it. A login already opening or open on the same
     * node is left alone (a second tap, or a recomposition); anything else is cancelled.
     */
    fun start(rpBase: String) {
        when (val current = _state.value) {
            is LoginFlow.Starting -> if (current.rpBase == rpBase && job?.isActive == true) return
            is LoginFlow.Awaiting -> if (current.pending.rpBase == rpBase && job?.isActive == true) return
            else -> Unit
        }
        cancelLive()
        val gen = generation
        val client = login(rpBase)
        _state.value = LoginFlow.Starting(rpBase)
        job = scope.launch {
            val opened = runCatching { runInterruptible(io) { client.begin() } }
            if (gen != generation) return@launch
            val begun = opened.getOrElse {
                _state.value = LoginFlow.Failed(it.message ?: "could not start the login")
                return@launch
            }
            val pending = PendingLogin(rpBase, begun.loginId, begun.qrTuple, clock())
            store.pending = pending
            _state.value = LoginFlow.Awaiting(pending)
            poll(gen, client, pending, begun, timeoutMillis)
        }
    }

    /** A login left by a previous process: poll it for what is left of its window. */
    private fun resume(p: PendingLogin) {
        val remaining = p.startedAtMillis + timeoutMillis - clock()
        val begun = runCatching {
            VoidbindLogin.Pending(p.loginId, LoginTuple.decode(p.qrTuple), p.qrTuple)
        }.getOrNull()
        if (remaining <= 0 || begun == null) {
            store.pending = null
            _state.value = LoginFlow.Failed("The sign-in request expired while heyarr was closed. Sign in again.")
            return
        }
        val gen = ++generation
        _state.value = LoginFlow.Awaiting(p)
        job = scope.launch { poll(gen, login(p.rpBase), p, begun, remaining) }
    }

    private suspend fun poll(
        gen: Long,
        client: VoidbindLogin,
        pending: PendingLogin,
        begun: VoidbindLogin.Pending,
        timeoutMs: Long,
    ) {
        val result = runCatching { runInterruptible(io) { client.awaitApproval(begun, timeoutMs) } }
            .getOrElse { VoidbindLogin.Result.Failed(it.message ?: "login error") }
        if (gen != generation) return
        _state.value = when (result) {
            // The open login stays persisted until consume(): a process death before the
            // approval is applied re-polls it on restart instead of losing it.
            is VoidbindLogin.Result.Approved -> LoginFlow.Approved(
                pending.rpBase,
                result.sessionToken,
                result.user,
                handedOff = store.pending?.handedOff ?: pending.handedOff,
            )

            is VoidbindLogin.Result.Denied -> LoginFlow.Failed(result.reason).also { store.pending = null }

            is VoidbindLogin.Result.Failed -> LoginFlow.Failed(result.error).also { store.pending = null }
        }
    }

    /**
     * The user tapped "Approve on this phone": the app is about to go behind Cruciform.
     * Recorded so the foreground service holds the process while it waits.
     */
    fun handedOff() {
        val a = _state.value as? LoginFlow.Awaiting ?: return
        if (a.pending.handedOff) return
        val p = a.pending.copy(handedOff = true)
        store.pending = p
        _state.value = LoginFlow.Awaiting(p)
    }

    /**
     * Cruciform did not open after all (disabled or uninstalled since the button was drawn,
     * or Android refused the launch): the app stays in front, so drop the hand-off and
     * the foreground service it holds; the login stays open for a scan.
     */
    fun handOffFailed() {
        val a = _state.value as? LoginFlow.Awaiting ?: return
        if (!a.pending.handedOff) return
        val p = a.pending.copy(handedOff = false)
        store.pending = p
        _state.value = LoginFlow.Awaiting(p)
    }

    /** Acknowledge an outcome (approved or failed) once it has been applied; forget the login. */
    fun consume() {
        when (_state.value) {
            is LoginFlow.Approved, is LoginFlow.Failed -> {
                store.pending = null
                _state.value = LoginFlow.Idle
            }

            else -> Unit
        }
    }

    /** Abandon the login (signed out, or the node changed). */
    fun cancel() {
        cancelLive()
        store.pending = null
        _state.value = LoginFlow.Idle
    }

    private fun cancelLive() {
        generation++
        job?.cancel()
        job = null
    }

    companion object {
        /** How long the node keeps a login open for approval ([VoidbindLogin.awaitApproval]'s default). */
        const val LOGIN_TIMEOUT_MILLIS = 120_000L
    }
}
