package one.rarebit.heyarr.desktop.library

import one.rarebit.heyarr.desktop.music.Track

// The desktop's view of `:core`'s shared series grouping: episodes and seasons of its own
// asset model, so call sites keep reading `Episode` / `Season`.
typealias Episode = one.rarebit.heyarr.core.library.Episode<Track>
typealias Season = one.rarebit.heyarr.core.library.Season<Track>
