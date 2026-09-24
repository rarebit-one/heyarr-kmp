package one.rarebit.heyarr.mobile.library

// The phone's view of `:core`'s shared series grouping: episodes and seasons of its own
// asset model, so call sites keep reading `Episode` / `Season`.
typealias Episode = one.rarebit.heyarr.core.library.Episode<WorkAsset>
typealias Season = one.rarebit.heyarr.core.library.Season<WorkAsset>
