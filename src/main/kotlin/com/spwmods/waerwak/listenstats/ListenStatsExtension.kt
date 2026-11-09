package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.spwmods.waerwak.listenstats.stats.PlaybackPipeline
import org.pf4j.Extension

/**
 * 播放扩展点
 *
 * 这里**只做信号转发**：所有回调都只带时间戳入队（[PlaybackPipeline]），
 * 不在宿主回调线程上碰磁盘、不查询宿主、不做统计计算。
 *
 * 曲目身份不从这里建立：多插件环境下歌词回调可能被其它插件短路
 * （宿主 loadLyrics 遇到非 null 返回值会跳出扩展循环），歌词回调只作为
 * 「曲目可能变化」的提示，由 PlaybackPipeline 用 getCurrentMediaItem() 确认。
 */
@Extension
class ListenStatsExtension : PlaybackExtensionPoint {

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        HostProbe.isPlaying = isPlaying
        PlaybackPipeline.onPlayingChanged(isPlaying)
    }

    override fun onPositionUpdated(position: Long) {
        HostProbe.positionCallbacks += 1L
        PlaybackPipeline.onPosition(position)
    }

    override fun onSeekTo(position: Long) {
        PlaybackPipeline.onSeek(position)
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        PlaybackPipeline.onState(state)
    }

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        HostProbe.lastTrack = mediaItem
        PlaybackPipeline.onLyricHint()
        return null
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        HostProbe.lastTrack = mediaItem
        PlaybackPipeline.onLyricHint()
        return null
    }
}
