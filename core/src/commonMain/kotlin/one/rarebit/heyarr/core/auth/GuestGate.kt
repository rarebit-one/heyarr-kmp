package one.rarebit.heyarr.core.auth

/**
 * The surfaces the client can offer, tagged with whether each one needs an enrolled
 * principal. A guest lease may **browse**, **play** and pull **subtitles**; everything
 * that writes desired state (want / follow / rate / monitor / acquire) or reads encrypted
 * controller-side personal state (playlists / resume position / history) needs the
 * "Sign in to save" upgrade.
 *
 * This is the single source of truth the desktop UI consults when it decides whether to
 * show, hide or disable an affordance in guest mode — kept here in pure `:core` so it is
 * unit-tested once and cannot drift screen to screen.
 */
enum class Surface(val requiresEnrolment: Boolean) {
    // Guest-allowed: the whole point of guest-as-default.
    BROWSE(false),
    PLAY(false),
    SUBTITLE(false),

    // Writes to desired state — enrolment required.
    WANT(true),
    FOLLOW(true),
    RATE(true),
    MONITOR(true),
    ACQUIRE(true),

    // Encrypted, controller-side personal state — enrolment required.
    PLAYLISTS(true),
    RESUME(true),
    HISTORY(true),
}

object GuestGate {
    /** True when [surface] must be hidden or disabled for a client acting in [mode]. */
    fun isGated(mode: ClientMode, surface: Surface): Boolean =
        mode == ClientMode.GUEST && surface.requiresEnrolment

    /** True when [surface] may be offered to a client acting in [mode]. */
    fun allows(mode: ClientMode, surface: Surface): Boolean = !isGated(mode, surface)
}
