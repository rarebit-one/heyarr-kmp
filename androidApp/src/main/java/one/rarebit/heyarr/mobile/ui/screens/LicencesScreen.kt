package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.R
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import org.json.JSONArray
import org.json.JSONObject

/** One third-party library compiled into this app, as the AboutLibraries plugin recorded it. */
internal data class OssLibrary(
    val name: String,
    val version: String?,
    val website: String?,
    val licences: List<OssLicence>,
)

internal data class OssLicence(val name: String, val url: String?, val text: String?)

/**
 * Reads `aboutlibraries.json`, which the AboutLibraries Gradle plugin writes into
 * `res/raw` at build time from the release runtime classpath and its POMs. Libraries
 * come back sorted by name; a licence id the file does not define is kept as its id,
 * so nothing listed is silently dropped.
 */
internal fun parseOssLibraries(json: String): List<OssLibrary> {
    val root = JSONObject(json)
    val licences = root.optJSONObject("licenses") ?: JSONObject()
    val libraries = root.optJSONArray("libraries") ?: JSONArray()
    return (0 until libraries.length()).map { i ->
        val lib = libraries.getJSONObject(i)
        val ids = lib.optJSONArray("licenses") ?: JSONArray()
        OssLibrary(
            name = lib.optString("name").ifBlank { lib.optString("uniqueId") },
            version = lib.optString("artifactVersion").ifBlank { null },
            website = lib.optString("website").ifBlank { null },
            licences = (0 until ids.length()).map { j ->
                val id = ids.getString(j)
                val def = licences.optJSONObject(id)
                OssLicence(
                    name = def?.optString("name")?.ifBlank { null } ?: id,
                    url = def?.optString("url")?.ifBlank { null },
                    text = def?.optString("content")?.ifBlank { null },
                )
            },
        )
    }.sortedBy { it.name.lowercase() }
}

/** Settings' About panel: where the app accounts for the third-party code it ships. */
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Composables are PascalCase by Compose convention.
@Composable
internal fun AboutPanel(onLicences: () -> Unit) {
    Panel("About") {
        SecondaryButton("Open-source licences", onLicences, compact = true)
    }
}

/** Settings → Open-source licences: every third-party library in this build and its licence. */
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Composables are PascalCase by Compose convention.
@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val libraries = remember {
        context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use {
            parseOssLibraries(it.readText())
        }
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Tokens.screenPadding, vertical = Tokens.s3),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Settings", onBack, icon = Icons.Rounded.ArrowBack)
            }
        }
        item { SectionHeader("Open-source licences") }
        item {
            Text(
                "heyarr is built with these open-source libraries. Tap one to read its licence.",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
            )
        }
        items(libraries, key = { it.name + "@" + it.version }) { lib ->
            val key = lib.name + "@" + lib.version
            Panel(lib.name, Modifier.clickable { expanded = if (expanded == key) null else key }) {
                Text(
                    listOfNotNull(lib.version, lib.licences.joinToString { it.name }.ifBlank { null })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textMuted,
                )
                if (expanded == key) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lib.website?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                        }
                        lib.licences.forEach { licence ->
                            Text(
                                licence.text ?: licence.url ?: licence.name,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }
}
