package one.rarebit.heyarr.mobile.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.HeyarrApp
import one.rarebit.heyarr.mobile.R
import one.rarebit.heyarr.mobile.consumption.Position
import one.rarebit.heyarr.mobile.consumption.ProgressReporter
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
        val container = FrameLayout(this).apply { id = R.id.reader_container }
        setContentView(container)
        val status = TextView(this).apply {
            text = "Opening…"
            setPadding(48, 48, 48, 48)
        }
        container.addView(status)

        val assetId = intent.getStringExtra(EXTRA_ASSET_ID) ?: return finish()
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val app = application as HeyarrApp
        val positions = PrefsReadingPositionStore(this)
        val reporter = app.reporter

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

            // Resume from the freshest same-device Locator; if this device has none
            // (a fresh install, or first read on another device), fall back to the exact
            // locator the encrypted reading-position space carries (§45), caching it
            // locally so the next open is instant. The node never sees the plaintext locator.
            val localJson = positions.locator(assetId)
            val initialJson =
                localJson
                    ?: withContext(kotlinx.coroutines.Dispatchers.IO) {
                        app.readingPositionSync.resume(assetId)
                    }?.also { positions.put(assetId, it) }
            val initial = initialJson?.let { runCatching { Locator.fromJSON(org.json.JSONObject(it)) }.getOrNull() }
            val fragment = ReaderFragment.newInstance()
            fragment.setup(pub, initial) { locator ->
                positions.put(assetId, locator.toJSON().toString())
                ReaderPosition.pageOf(locator.toJSON().toString())?.let { reporter.progressAt(Position.page(it)) }
            }
            supportFragmentManager.beginTransaction().replace(R.id.reader_container, fragment, "reader").commitNow()
            reporter.begin(assetId, "read")
            ReaderPosition.pageOf(initial?.toJSON()?.toString() ?: "")?.let { reporter.resumeAt(Position.page(it)) }
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
        val app = application as? HeyarrApp
        val last = intent.getStringExtra(EXTRA_ASSET_ID)?.let { PrefsReadingPositionStore(this).locator(it) }
        val page = last?.let { ReaderPosition.pageOf(it) }
        app?.reporter?.endAt(page?.let { Position.page(it) } ?: Position.page(0), completed = false)
        intent.getStringExtra(EXTRA_ASSET_ID)?.let { id -> last?.let { app?.readingPositionSync?.save(id, it) } }
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
