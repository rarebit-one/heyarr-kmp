package one.rarebit.heyarr.mobile.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.consumption.ProgressReporter

/**
 * Turns the audio queue's state into consumption sessions: a new item is a `listen`
 * begin, play/pause are resume/pause, the queue emptying is a stop, and the position
 * ticks through (the reporter throttles). One observer for the process.
 */
class AudioSessionBridge(audio: AudioPlayer, private val reporter: ProgressReporter, scope: CoroutineScope) {
    private var lastAsset: String? = null
    private var lastPlaying = false

    init {
        scope.launch {
            audio.state.collect { s ->
                val asset = s.item?.assetId
                val pos = s.positionMs / 1000.0
                when {
                    asset != lastAsset -> {
                        if (lastAsset != null) reporter.end(pos, completed = false)
                        lastAsset = asset
                        lastPlaying = s.playing
                        if (asset != null) reporter.begin(asset, "listen")
                    }

                    asset != null && s.playing != lastPlaying -> {
                        lastPlaying = s.playing
                        if (s.playing) reporter.resume(pos) else reporter.pause(pos)
                    }

                    asset != null && s.playing -> reporter.progress(pos)
                }
            }
        }
    }
}
