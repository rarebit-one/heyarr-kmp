package one.rarebit.heyarr.mobile.reader

/** What a book-shaped file is, decided from its MIME first and its filename second. Pure, unit-tested. */
enum class ReaderFormat(val label: String) {
    EPUB("EPUB"),
    PDF("PDF"),
    CBZ("Comic (CBZ)"),
    CBR("Comic (CBR)"),
    AUDIOBOOK("Audiobook"),
    ;

    companion object {
        fun of(mime: String?, filename: String?): ReaderFormat? {
            when (mime?.lowercase()?.substringBefore(';')?.trim()) {
                "application/epub+zip" -> return EPUB
                "application/pdf" -> return PDF
                "application/vnd.comicbook+zip", "application/x-cbz" -> return CBZ
                "application/vnd.comicbook-rar", "application/x-cbr" -> return CBR
            }
            if (mime?.lowercase()?.startsWith("audio/") == true) return AUDIOBOOK
            return when (filename?.substringAfterLast('.', "")?.lowercase()) {
                "epub" -> EPUB
                "pdf" -> PDF
                "cbz" -> CBZ
                "cbr" -> CBR
                "m4b", "mp3", "m4a", "flac", "ogg", "opus" -> AUDIOBOOK
                else -> null
            }
        }
    }
}
