package one.rarebit.heyarr.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.rarebit.heyarr.core.heyarr.ProviderInfo
import one.rarebit.heyarr.core.mcp.McpError
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.state.DiscoveryAnswer
import one.rarebit.heyarr.core.state.DiscoveryBackend
import one.rarebit.heyarr.core.state.DiscoveryController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryControllerTest {
    private class FakeBackend : DiscoveryBackend {
        val discoveryQueries = mutableListOf<String>()
        var providerCalls = 0
        var failure: Throwable? = null
        var answer: DiscoveryAnswer = DiscoveryAnswer.Refused(McpError(0, "none", "discover_content"))

        override fun providers(): List<ProviderInfo> {
            providerCalls++
            failure?.let { throw it }
            return listOf(ProviderInfo("catalogue", listOf("metadata"), true, null, null, null))
        }

        override fun discover(query: String): DiscoveryAnswer {
            discoveryQueries += query
            failure?.let { throw it }
            return answer
        }
    }

    private fun TestScope.controller(
        backend: DiscoveryBackend?,
        failures: MutableList<McpTransportException> = mutableListOf(),
    ) = DiscoveryController(
        scope = this,
        backend = { backend },
        onTransportFailure = { failures += it },
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun providersReloadOnceForEachNodeGeneration() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend)

        controller.loadForGeneration(1)
        advanceUntilIdle()
        controller.loadForGeneration(1)
        advanceUntilIdle()
        assertEquals(1, backend.providerCalls)
        assertEquals("catalogue", controller.state.value.providers?.single()?.name)

        controller.loadForGeneration(2)
        advanceUntilIdle()
        assertEquals(2, backend.providerCalls)
        assertEquals(2, controller.state.value.generation)
    }

    @Test
    fun queryChangesCancelPendingSearchAndDebounceTheLatestText() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend)
        controller.searchDebounced("old", debounceMs = 300)
        advanceTimeBy(150)
        controller.updateQuery("new")
        controller.searchDebounced("new", debounceMs = 300)
        advanceTimeBy(299)
        runCurrent()
        assertTrue(backend.discoveryQueries.isEmpty())

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(listOf("new"), backend.discoveryQueries)
        assertEquals("new", controller.state.value.asked)
    }

    @Test
    fun submitReturnsTheNodeAnswerAndBlankQueryDoesNothing() = runTest {
        val backend = FakeBackend().apply {
            answer = DiscoveryAnswer.Refused(McpError(403, "provider is disabled", "discover_content"))
        }
        val controller = controller(backend)
        controller.submit()
        advanceUntilIdle()
        assertTrue(backend.discoveryQueries.isEmpty())

        controller.updateQuery("  Dune  ")
        controller.submit()
        advanceUntilIdle()
        val result = assertIs<DiscoveryAnswer.Refused>(controller.state.value.result)
        assertEquals("provider is disabled", result.error.message)
        assertEquals(listOf("Dune"), backend.discoveryQueries)
        assertTrue(!controller.state.value.busy)
    }

    @Test
    fun transportFailureIsShownAndReportedToTheSession() = runTest {
        val failure = McpTransportException("offline", null)
        val failures = mutableListOf<McpTransportException>()
        val backend = FakeBackend().apply { this.failure = failure }
        val controller = controller(backend, failures)
        controller.updateQuery("Dune")
        controller.submit()
        advanceUntilIdle()

        assertEquals("offline", controller.state.value.error)
        assertEquals(listOf(failure), failures)
        assertNull(controller.state.value.result)
        assertTrue(!controller.state.value.busy)
    }
}
