package one.rarebit.heyarr.desktop.settings

import java.util.Properties

/**
 * The deployment this build points at: the `heyarrBaseUrl` and `heyarrDnsHost` Gradle
 * properties, baked into the `heyarr-site.properties` classpath resource at build time
 * (composeApp/build.gradle.kts). They are never committed — this repository is public and a
 * deployment's address is its own — so a build made without them starts at
 * [PLACEHOLDER_BASE_URL] and finds its node by mDNS or from Settings.
 */
object SiteDefaults {
    /** A reserved name (RFC 6761 `.test`) that never resolves: an unconfigured build reaches no one. */
    const val PLACEHOLDER_BASE_URL = "https://heyarr.example.test:7777"

    private val props: Properties by lazy {
        Properties().apply {
            SiteDefaults::class.java.getResourceAsStream("/heyarr-site.properties")?.use(::load)
        }
    }

    private fun value(name: String): String? = props.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

    /** The node a fresh install connects to. */
    val baseUrl: String get() = value("heyarrBaseUrl") ?: PLACEHOLDER_BASE_URL

    /** The split-horizon DNS name discovery tries after mDNS; blank skips that rung. */
    val dnsHost: String get() = value("heyarrDnsHost").orEmpty()
}
