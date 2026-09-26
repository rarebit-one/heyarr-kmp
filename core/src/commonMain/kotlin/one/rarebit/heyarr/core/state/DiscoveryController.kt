package one.rarebit.heyarr.core.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.heyarr.ProviderInfo
import one.rarebit.heyarr.core.mcp.DiscoveryHit
import one.rarebit.heyarr.core.mcp.McpError
import one.rarebit.heyarr.core.mcp.McpTransportException

/** The discovery reads shared by the Android and desktop clients. Calls block on I/O. */
interface DiscoveryBackend {
    fun providers(): List<ProviderInfo>

    fun discover(query: String): DiscoveryAnswer
}

/** `discover_content` either returned these hits or the node's verbatim refusal. */
sealed interface DiscoveryAnswer {
    data class Hits(val values: List<DiscoveryHit>) : DiscoveryAnswer

    data class Refused(val error: McpError) : DiscoveryAnswer
}

/** Immutable request and provider state rendered by both clients. */
data class DiscoveryState(
    val generation: Int = -1,
    val query: String = "",
    val asked: String? = null,
    val result: DiscoveryAnswer? = null,
    val busy: Boolean = false,
    val providers: List<ProviderInfo>? = null,
    val providerError: String? = null,
    val error: String? = null,
)

/**
 * Owns provider loading, request cancellation, debounce, stale-response handling and
 * transport errors for provider discovery. Screens only choose when to call [submit]
 * or [searchDebounced] and render [state].
 */
class DiscoveryController(
    private val scope: CoroutineScope,
    private val backend: () -> DiscoveryBackend?,
    private val onTransportFailure: (McpTransportException) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private var providerJob: Job? = null
    private var requestJob: Job? = null
    private var requestGeneration = 0

    /** Reload provider capability and discard results whenever the connected node changes. */
    fun loadForGeneration(generation: Int) {
        if (_state.value.generation == generation) return
        providerJob?.cancel()
        requestJob?.cancel()
        requestGeneration++
        _state.update {
            DiscoveryState(generation = generation, query = it.query)
        }
        val currentBackend = backend()
        if (currentBackend == null) {
            _state.update { it.copy(providerError = "The node is not connected.") }
            return
        }
        providerJob = scope.launch {
            val providers = fetch { currentBackend.providers() }
            if (_state.value.generation == generation) {
                _state.update {
                    it.copy(
                        providers = providers.getOrNull(),
                        providerError = providers.exceptionOrNull()?.let {
                            it.message ?: "Provider details are unavailable."
                        },
                    )
                }
            }
        }
    }

    fun updateQuery(query: String) {
        if (_state.value.query == query) return
        requestJob?.cancel()
        requestGeneration++
        _state.update { it.copy(query = query, asked = null, result = null, busy = false, error = null) }
    }

    /** Start a provider search after a short pause while typing. */
    fun searchDebounced(query: String, debounceMs: Long = 300) = search(query, debounceMs)

    /** Submit the current query immediately, cancelling any older or pending request. */
    fun submit() = search(_state.value.query)

    private fun search(query: String, debounceMs: Long = 0) {
        val normalized = query.trim()
        requestJob?.cancel()
        val generation = ++requestGeneration
        if (normalized.isBlank()) {
            _state.update { it.copy(query = query, asked = null, result = null, busy = false, error = null) }
            return
        }
        _state.update {
            it.copy(query = query, asked = normalized, result = null, busy = true, error = null)
        }
        val currentBackend = backend()
        if (currentBackend == null) {
            _state.update { it.copy(busy = false, error = "The node is not connected.") }
            return
        }
        requestJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val result = fetch { currentBackend.discover(normalized) }
            if (generation == requestGeneration) {
                result.fold(
                    onSuccess = { answer -> _state.update { it.copy(result = answer, busy = false) } },
                    onFailure = { failure ->
                        _state.update {
                            it.copy(error = failure.message ?: "Discovery could not reach the node.", busy = false)
                        }
                    },
                )
            }
        }
    }

    private suspend fun <T> fetch(block: () -> T): Result<T> {
        val result = withContext(ioDispatcher) { runCatching(block) }
        return result.onFailure { failure ->
            if (failure is CancellationException) throw failure
            if (failure is McpTransportException) onTransportFailure(failure)
        }
    }
}
