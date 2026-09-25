package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.library.Epub
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.Tokens
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A PLACEHOLDER e-book reader (heyarr-desktop had none; heyarr-mobile's `reader/` is the
 * real reference). It fetches the book blob with the connection credential, and for an
 * EPUB shows a linear-text pass ([Epub]) — no CSS, images or pagination. Anything else,
 * or a parse that yields nothing, falls back to "open in your default app" via the
 * existing [AppSession.openExternally] plumbing. Reading-position sync (mobile's
 * `ReadingPositionSync`, §72 personal state) is deliberately out of scope for this pass.
 */
@Composable
fun ReaderScreen(session: AppSession, route: Route.Reader, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var readingSize by remember { mutableStateOf(18) }
    var loading by remember(route.assetId) { mutableStateOf(true) }
    var error by remember(route.assetId) { mutableStateOf<String?>(null) }
    var unsupported by remember(route.assetId) { mutableStateOf(false) }
    var book by remember(route.assetId) { mutableStateOf<Epub.Book?>(null) }

    LaunchedEffect(route.assetId) {
        loading = true
        error = null
        unsupported = false
        book = null
        val looksEpub = route.mime?.contains("epub", ignoreCase = true) == true ||
            route.filename?.endsWith(".epub", ignoreCase = true) == true
        if (!looksEpub) {
            unsupported = true
            loading = false
            return@LaunchedEffect
        }
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                val req = HttpRequest.newBuilder(URI.create(HeyarrApi.blobUrl(session.config.baseUrl, route.blobHash)))
                    .header("Authorization", "Bearer " + session.config.bearerToken.trim()).GET().build()
                val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray())
                resp.takeIf { it.statusCode() == 200 }?.body()
            }.getOrNull()
        }
        if (bytes == null) {
            error = "Couldn't fetch this book from the library."
            loading = false
            return@LaunchedEffect
        }
        val parsed = withContext(Dispatchers.Default) { Epub.parse(bytes) }
        if (parsed == null) unsupported = true else book = parsed
        loading = false
    }

    fun openExternally() {
        scope.launch {
            val msg = session.io {
                session.openExternally.open(
                    session.config.baseUrl,
                    route.blobHash,
                    session.config.bearerToken.trim(),
                    route.filename,
                    route.mime,
                    route.title,
                )
            }.getOrNull()
            if (msg != null) session.toast(Toast.Kind.INFO, msg)
        }
    }

    MediaScope(MediaType.BOOK) {
        val accent = LocalMediaTheme.current.accentGradientEnd
        Column(Modifier.fillMaxSize().background(Tokens.bgBase)) {
            Row(
                Modifier.fillMaxWidth().background(Tokens.surface1).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButtonRound(Icons.Rounded.ArrowBack, "Back", onBack)
                Column(Modifier.weight(1f)) {
                    Text(
                        book?.title ?: route.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Tokens.textPrimary,
                        maxLines = 1,
                    )
                    book?.let {
                        Text(
                            "${it.chapters.size} sections",
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.textMuted,
                        )
                    }
                }
                IconButtonRound(Icons.Rounded.TextDecrease, "Decrease reading text size", {
                    readingSize =
                        (readingSize - 1).coerceAtLeast(14)
                }, enabled = readingSize > 14)
                IconButtonRound(Icons.Rounded.TextIncrease, "Increase reading text size", {
                    readingSize =
                        (readingSize + 1).coerceAtMost(32)
                }, enabled = readingSize < 32)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when {
                    loading -> Centered("Opening…")

                    error != null -> Box(Modifier.padding(24.dp)) { Notice(error!!) }

                    unsupported -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "In-app reading isn’t available for this format yet.",
                            style = MaterialTheme.typography.titleMedium,
                            color = Tokens.textPrimary,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "Open it in your default reader instead.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.textMuted,
                            textAlign = TextAlign.Center,
                        )
                        PrimaryButton("Open externally", ::openExternally, icon = Icons.Rounded.MenuBook)
                    }

                    book != null -> {
                        val listState = rememberLazyListState()
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            book!!.chapters.forEachIndexed { i, ch ->
                                item(key = "h$i") {
                                    Text(
                                        ch.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = accent,
                                        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(
                                            top = if (i ==
                                                0
                                            ) {
                                                0.dp
                                            } else {
                                                28.dp
                                            },
                                            bottom = 10.dp,
                                        ),
                                    )
                                }
                                item(key = "t$i") {
                                    SelectionContainer(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                                        Text(
                                            ch.text,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = readingSize.sp,
                                                lineHeight = (
                                                    readingSize *
                                                        1.6f
                                                    ).sp,
                                            ),
                                            color = Tokens.textPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Tokens.textMuted)
    }
}
