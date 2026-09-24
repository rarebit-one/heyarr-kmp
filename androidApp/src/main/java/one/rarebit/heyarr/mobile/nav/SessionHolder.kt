package one.rarebit.heyarr.mobile.nav

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.rarebit.heyarr.core.state.ExternalMetadata
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.settings.SettingsStore
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.RecentSearches
import one.rarebit.heyarr.mobile.state.SearchController
import one.rarebit.heyarr.mobile.ui.screens.CastState
import one.rarebit.heyarr.mobile.ui.screens.DetailState
import one.rarebit.heyarr.mobile.ui.screens.HomeState
import one.rarebit.heyarr.mobile.ui.screens.LibraryState
import one.rarebit.heyarr.mobile.ui.screens.MissingState
import one.rarebit.heyarr.mobile.ui.screens.PlayerScreenState
import one.rarebit.heyarr.mobile.ui.screens.SettingsState
import one.rarebit.heyarr.mobile.ui.screens.TelemetryState

/**
 * The signed-in session and every screen's state, held in one ViewModel keyed on the
 * [ApiEnv] (node + credential shape) so they survive rotation and are rebuilt on a
 * sign-in, an enrolment or a Settings change — the mobile home for the desktop's
 * plain-Compose `AppSession` + per-screen state objects.
 */
class SessionHolder(
    env: ApiEnv,
    settings: SettingsStore,
    external: ExternalMetadata,
    recent: RecentSearches,
) : ViewModel() {
    val session: AppSession = AppSession(
        api = HeyarrApi(env.transport, env.baseUrl, env.credential),
        defaultProfile = env.qualityProfile,
        settings = settings,
        external = external,
        recent = recent,
        scope = viewModelScope,
        credential = env.credential,
    )
    val search = SearchController(viewModelScope, { session.api }, session::noteTransportFailure)
    val home = HomeState()
    val library = LibraryState()
    val missing = MissingState()
    val cast = CastState()
    val settingsState = SettingsState()
    val telemetry = TelemetryState()
    val player = PlayerScreenState()
    val details = mutableStateMapOf<String, DetailState>()

    fun detail(workId: String): DetailState = details.getOrPut(workId) { DetailState(workId) }
}
