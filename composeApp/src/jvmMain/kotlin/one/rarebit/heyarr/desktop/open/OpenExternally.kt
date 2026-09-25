package one.rarebit.heyarr.desktop.open

/**
 * The download-then-open flow for books and archived articles: fetch the authenticated
 * blob to a temp file ([BlobDownloader]) and hand it to the system app ([ExternalOpener]).
 * Both steps are seams, so this composed action is JVM-tested end to end with fakes — no
 * network, no `xdg-open`.
 *
 * Returns a UI-safe status string (never a thrown exception, never the token).
 */
class OpenExternally(private val downloader: BlobDownloader, private val opener: ExternalOpener) {
    /**
     * Download the blob [blobHash] and open it. [filename]/[mime] pick the temp extension so
     * the right handler is chosen; [displayName] names the thing in the returned status.
     */
    fun open(
        baseUrl: String,
        blobHash: String,
        token: String,
        filename: String?,
        mime: String?,
        displayName: String,
    ): String {
        val ext = MediaExt.forNameAndMime(filename, mime)
        return when (val dl = downloader.download(baseUrl, blobHash, token, ext)) {
            is DownloadResult.Failed -> dl.message

            is DownloadResult.Downloaded -> when (val op = opener.open(dl.file)) {
                is OpenResult.Opened -> "Opened “$displayName” in your default app."
                is OpenResult.Failed -> op.message
            }
        }
    }
}
