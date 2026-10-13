package one.rarebit.heyarr.core.heyarr

import one.rarebit.heyarr.core.theme.MediaType

/** Which quality profiles fit a kind of work, and the one a want pre-selects. */
object QualityProfiles {
    /** The profiles that may judge [type]: those naming its content type, and those naming none. */
    fun offeredFor(type: MediaType, profiles: List<QualityProfile>): List<QualityProfile> {
        val api = type.apiName
        return profiles.filter { it.contentTypes.isEmpty() || (api != null && api in it.contentTypes) }
    }

    /**
     * The profile a want pre-selects for [type]: "everyday" when it is offered (the common video
     * case), otherwise the first offered profile. Null when nothing fits — a book or an album
     * must not borrow a video profile's gates (heyarr-core ADR-0082), so the caller asks instead.
     */
    fun defaultFor(type: MediaType, profiles: List<QualityProfile>): String? {
        val offered = offeredFor(type, profiles)
        return (offered.firstOrNull { it.name == "everyday" } ?: offered.firstOrNull())?.name
    }
}
