@file:Suppress("FunctionNaming")

package one.rarebit.heyarr.mobile.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.HeyarrApp
import one.rarebit.heyarr.mobile.R
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.AudioPlayer
import one.rarebit.heyarr.mobile.playback.AudioState
import one.rarebit.heyarr.mobile.theme.HeyarrTheme
import one.rarebit.heyarr.mobile.ui.components.Artwork
import one.rarebit.heyarr.mobile.ui.components.clockShort
import one.rarebit.heyarr.ui.components.DashedDivider
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private const val READER_STATUS_PADDING_PX = 48

/**
 * The reader: opens the file over the authenticated blob route with Readium and hosts
 * the navigator that fits it — EPUB, PDF (pdfium) or a comic archive — resuming from
 * the locally kept Locator, saving it as it moves, and telling the node the page
 * through the consumption reporter (`read`).
 *
 * Its own Activity because Readium's navigators are Fragments and the app is Compose;
 * the fragment-hosting boundary is cleanest at an Activity.
 */
class ReaderActivity : FragmentActivity() {

    private var publication: Publication? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HeyarrApp
        val container = FrameLayout(this).apply { id = R.id.reader_container }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#101815"))
        }
        root.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val audio = app.graph.audio
                    val audioState by audio.state.collectAsState()
                    HeyarrTheme(MediaThemes.of(one.rarebit.heyarr.core.theme.MediaType.MUSIC)) {
                        CompanionAudioPanel(audioState, audio)
                    }
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        setContentView(root)
        val status = TextView(this).apply {
            text = "Opening…"
            setTextColor(android.graphics.Color.parseColor("#E5E9DC"))
            setBackgroundColor(android.graphics.Color.parseColor("#101815"))
            setPadding(
                READER_STATUS_PADDING_PX,
                READER_STATUS_PADDING_PX,
                READER_STATUS_PADDING_PX,
                READER_STATUS_PADDING_PX,
            )
        }
        container.addView(status)

        val assetId = intent.getStringExtra(EXTRA_ASSET_ID) ?: return finish()
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val positions = PrefsReadingPositionStore(this)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            },
        )

        lifecycleScope.launch {
            val http = ReaderHttp.client(baseUrl = { app.graph.baseUrl() }, header = { app.graph.authHeader.current() })
            val pub = openPublication(http, url, status) ?: return@launch
            publication = pub
            container.removeView(status)

            // Keep the locator on this phone so reopening a book on this device returns
            // to the same page. This location is neither reported nor synced to the node.
            val initialJson = positions.locator(assetId)
            val initial = initialJson?.let { runCatching { Locator.fromJSON(org.json.JSONObject(it)) }.getOrNull() }
            val fragment = ReaderFragment.newInstance()
            fragment.setup(pub, initial) { locator ->
                positions.put(assetId, locator.toJSON().toString())
            }
            supportFragmentManager.beginTransaction().replace(R.id.reader_container, fragment, "reader").commitNow()
            setTitle(title)
        }
    }

    /** Fetch and parse the file at [url]; on failure say why in [status] and return null. */
    private suspend fun openPublication(http: DefaultHttpClient, url: String, status: TextView): Publication? {
        val retriever = AssetRetriever(contentResolver, http)
        val opener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                this@ReaderActivity,
                httpClient = http,
                assetRetriever = retriever,
                pdfFactory = PdfiumDocumentFactory(this@ReaderActivity),
            ),
        )
        val absolute = AbsoluteUrl(url) ?: run {
            status.text = "Not a URL: $url"
            return null
        }
        val asset = retriever.retrieve(absolute).getOrElse {
            status.text = "Could not fetch the file: $it"
            null
        }
        return asset?.let { fetched ->
            opener.open(fetched, allowUserInteraction = false).getOrElse {
                status.text = "Could not open the file: $it"
                null
            }
        }
    }

    override fun onDestroy() {
        publication?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ASSET_ID = "asset_id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        fun intent(context: Context, assetId: String, url: String, title: String): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_ASSET_ID, assetId).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)
    }
}

/** A quiet companion player in the reader Activity, backed by the app's existing audio queue. */
@Composable
private fun CompanionAudioPanel(state: AudioState, audio: AudioPlayer) {
    val item = state.item ?: return
    var expanded by remember(item.assetId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Tokens.surface1)) {
        DashedDivider()
        CompanionAudioHeading(item, expanded) { expanded = !expanded }
        CompanionAudioTransport(state, audio)
        if (expanded) CompanionAudioDetails(state, audio)
    }
}

@Composable
private fun CompanionAudioHeading(item: AudioItem, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Artwork(
            item.artworkUrl,
            one.rarebit.heyarr.core.theme.MediaType.MUSIC,
            Modifier.size(44.dp),
            contentDescription = "Cover for ${item.title}",
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("COMPANION AUDIO", style = MaterialTheme.typography.labelSmall, color = Tokens.accentGradEnd)
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = Tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(item.artist, item.album).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButtonRound(
            if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
            if (expanded) "Collapse companion audio" else "Expand companion audio",
            onToggle,
            size = 44.dp,
        )
    }
}

@Composable
private fun CompanionAudioTransport(state: AudioState, audio: AudioPlayer) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.weight(1f))
        IconButtonRound(
            Icons.Rounded.SkipPrevious,
            "Previous track",
            audio::previous,
            enabled = state.index > 0,
            size = 44.dp,
        )
        IconButtonRound(
            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (state.playing) "Pause companion audio" else "Play companion audio",
            audio::togglePlayPause,
            filled = true,
            size = 44.dp,
        )
        IconButtonRound(Icons.Rounded.SkipNext, "Next track", audio::next, enabled = state.hasNext, size = 44.dp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CompanionAudioDetails(state: AudioState, audio: AudioPlayer) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.VolumeUp,
            contentDescription = null,
            tint = Tokens.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Slider(
            value = state.volume,
            onValueChange = audio::setVolume,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Companion audio volume" },
            valueRange = 0f..1f,
        )
    }
    if (state.durationMs > 0) CompanionAudioSeek(state, audio)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
        itemsIndexed(state.queue) { index, _ ->
            CompanionTrackRow(index, state, audio)
            if (index < state.queue.lastIndex) DashedDivider(Modifier.padding(horizontal = 18.dp))
        }
    }
}

@Composable
private fun CompanionAudioSeek(state: AudioState, audio: AudioPlayer) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(clockShort(state.positionMs), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        Slider(
            value = state.fraction,
            onValueChange = { audio.seekTo((it * state.durationMs).toLong()) },
            modifier = Modifier.weight(1f),
        )
        Text(clockShort(state.durationMs), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
    }
}

@Composable
private fun CompanionTrackRow(index: Int, state: AudioState, audio: AudioPlayer) {
    val track = state.queue[index]
    Row(
        Modifier.fillMaxWidth().clickable { audio.skipTo(index) }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.textMuted,
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (index == state.index) Tokens.accentGradEnd else Tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (index ==
            state.index
        ) {
            Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = Tokens.accentGradEnd)
        }
    }
}

/** Hosts the Readium navigator fragment that fits the publication and relays its locator. */
class ReaderFragment : Fragment() {

    private var publication: Publication? = null
    private var initial: Locator? = null
    private var onLocator: (Locator) -> Unit = {}

    fun setup(publication: Publication, initial: Locator?, onLocator: (Locator) -> Unit) {
        this.publication = publication
        this.initial = initial
        this.onLocator = onLocator
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val pub = publication
        if (pub != null) {
            childFragmentManager.fragmentFactory = when {
                pub.conformsTo(
                    Publication.Profile.PDF,
                ) -> PdfNavigatorFactory(pub, PdfiumEngineProvider()).createFragmentFactory(initialLocator = initial)

                pub.conformsTo(
                    Publication.Profile.DIVINA,
                ) -> ImageNavigatorFragment.createFactory(pub, initialLocator = initial)

                else -> EpubNavigatorFactory(pub).createFragmentFactory(initialLocator = initial)
            }
        }
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ): android.view.View {
        val host = FrameLayout(requireContext()).apply { id = CONTAINER_ID }
        val pub = publication ?: return host
        if (savedInstanceState == null) {
            val cls: Class<out Fragment> = when {
                pub.conformsTo(Publication.Profile.PDF) -> PdfNavigatorFragment::class.java
                pub.conformsTo(Publication.Profile.DIVINA) -> ImageNavigatorFragment::class.java
                else -> EpubNavigatorFragment::class.java
            }
            childFragmentManager.beginTransaction().add(CONTAINER_ID, cls, Bundle(), TAG).commitNow()
        }
        val navigator = childFragmentManager.findFragmentByTag(TAG) as? Navigator
        if (navigator != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    navigator.currentLocator.onEach { onLocator(it) }.launchIn(this)
                }
            }
        }
        return host
    }

    companion object {
        private const val TAG = "navigator"
        private val CONTAINER_ID = android.view.View.generateViewId()
        fun newInstance() = ReaderFragment()
    }
}
