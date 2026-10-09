package one.rarebit.heyarr.vault

import java.text.Normalizer

// java.text.Normalizer is on the JDK and on Android alike, so one actual serves both targets.
internal actual fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
