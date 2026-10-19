package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.spwmods.waerwak.listenstats.stats.SessionTracker
import org.pf4j.Extension

/**
 * 播放扩展点
 *
 * 采集交给 [SessionTracker]，这里只做信号转发 + 面板用的轻量状态记录。
 */
@Extension
class ListenStatsExtension : PlaybackExtensionPoint {

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        HostProbe.isPlaying = isPlaying
        SessionTracker.onPlayingChanged(isPlaying)
    }

    override fun onPositionUpdated(position: Long) {
        HostProbe.positionCallbacks += 1L
        SessionTracker.onPosition(position)
    }

    override fun onSeekTo(position: Long) {
        SessionTracker.onSeek(position)
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        SessionTracker.onState(state)
    }

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        HostProbe.lastTrack = mediaItem
        SessionTracker.onTrack(mediaItem)
        return null
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        HostProbe.lastTrack = mediaItem
        SessionTracker.onTrack(mediaItem)
        return null
    }
}
