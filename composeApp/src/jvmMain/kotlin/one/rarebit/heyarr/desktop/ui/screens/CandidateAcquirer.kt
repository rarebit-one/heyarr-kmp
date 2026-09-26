@file:Suppress("FunctionNaming") // Compose components follow the shared component naming convention.

package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.Candidate
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.ui.components.DataTable
import one.rarebit.heyarr.ui.components.ReasonList
import one.rarebit.heyarr.ui.components.RejectedBy
import one.rarebit.heyarr.ui.components.TableColumn
import one.rarebit.heyarr.ui.components.TechnicalDisclosure
import one.rarebit.heyarr.ui.theme.Tokens

internal class CandidateAcquirer(
    private val session: AppSession,
    private val state: DetailState,
    private val scope: CoroutineScope,
    private val reload: () -> Unit,
) {
    fun acquire(want: DesiredItem, candidate: Candidate) {
        val api = session.api ?: return
        scope.launch {
            state.busy = candidate.candidateId
            state.acquisitionError = null
            state.acquisitionRefused = false
            try {
                session.io { api.acquire(want.id, candidate.candidateId) }.fold(
                    onSuccess = { result -> handleResult(result, candidate) },
                    onFailure = { state.acquisitionError = it.message ?: "The node could not request this release." },
                )
            } finally {
                state.busy = null
            }
        }
    }

    private fun handleResult(result: McpResult<*>, candidate: Candidate) {
        when (result) {
            is McpResult.Ok -> {
                session.toast(Toast.Kind.SUCCESS, "Acquiring", candidate.title)
                session.refreshIndex()
                reload()
            }

            is McpResult.Refused -> {
                state.acquisitionRefused = true
                state.acquisitionError = "${result.tool}: ${result.message}"
                session.refused(result)
            }
        }
    }
}

@Composable
internal fun CandidateAcquisitionError(state: DetailState) {
    state.acquisitionError?.let { error ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (state.acquisitionRefused) {
                    "The node declined this release request. Choose another release or view the reason."
                } else {
                    "Could not request this release. Check the connection and try again."
                },
                color = Tokens.danger,
                style = MaterialTheme.typography.bodySmall,
            )
            TechnicalDisclosure(error)
        }
    }
}

@Composable
@Suppress("MagicNumber") // The column widths are intentional release-table proportions.
internal fun CandidateTable(
    candidates: List<Pair<DesiredItem, Candidate>>,
    busy: String?,
    emptyText: String,
    acquire: (DesiredItem, Candidate) -> Unit,
) {
    DataTable(
        columns = listOf(
            TableColumn("Release", 3f),
            TableColumn("Provider", width = 110.dp),
            TableColumn("Score", width = 60.dp, alignEnd = true),
            TableColumn("Verdict", width = 100.dp),
            TableColumn("", width = 110.dp, alignEnd = true),
        ),
        rowCount = candidates.size,
        emptyText = emptyText,
        detailLabel = { row -> candidates[row].second.title },
        detail = { row ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RejectedBy(candidates[row].second.rejectedBy)
                ReasonList(candidates[row].second.reasons)
            }
        },
    ) { row, column ->
        val (want, candidate) = candidates[row]
        CandidateCell(candidate, column, acquireEnabled = busy == null) { acquire(want, candidate) }
    }
}
