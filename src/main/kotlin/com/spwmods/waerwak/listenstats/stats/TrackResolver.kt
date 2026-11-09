@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spwmods.waerwak.listenstats.stats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * 权威曲目解析
 *
 * 宿主没有「曲目变化」回调，也没有位置/播放态查询接口；唯一可靠的曲目身份来源是
 * [WorkshopApi.playback.getCurrentMediaItem]。多插件环境下歌词回调可能被其它插件短路
 * （宿主 loadLyrics 遍历扩展时遇到非 null 返回值会跳出循环），因此会话建立与换曲判断
 * 一律以这里解析到的曲目为准，歌词回调只当作「曲目可能变化」的提示。
 *
 * 查询成本：正常帧最多每秒一次；会话为空 / 位置可疑跳变 / seek / 状态变化时立即查询。
 * 查询在 [PlaybackPipeline] 的专用线程执行，不占用宿主主线程。
 */
object TrackResolver {

    private const val MIN_QUERY_INTERVAL_MS = 1_000L

    /** 测试注入点：替换宿主查询（不启动宿主也能跑回归） */
    internal var provider: (() -> PlaybackExtensionPoint.MediaItem?)? = null

    private var cached: PlaybackExtensionPoint.MediaItem? = null
    private var lastQueryMs = 0L
    private var lastError: String? = null
    private var queries = 0L

    @Synchronized
    internal fun resetForTesting() {
        cached = null
        lastQueryMs = 0L
        lastError = null
        queries = 0L
        provider = null
    }

    /** 主动解析当前曲目（monotonicMs 用单调时钟，避免校时/时区影响节流） */
    @Synchronized
    fun resolve(monotonicMs: Long, force: Boolean): PlaybackExtensionPoint.MediaItem? {
        if (!force && lastQueryMs > 0L && monotonicMs - lastQueryMs < MIN_QUERY_INTERVAL_MS) {
            return cached
        }
        lastQueryMs = monotonicMs
        queries++
        var item: PlaybackExtensionPoint.MediaItem? = null
        try {
            val injected = provider
            if (injected != null) {
                item = injected()
            } else {
                item = WorkshopApi.playback.getCurrentMediaItem()
            }
        } catch (t: Throwable) {
            // 宿主 API 异常不能影响计时：沿用缓存曲目，只记录错误
            lastError = t.javaClass.simpleName + ": " + t.message
        }
        if (item != null && item.id.isNotBlank()) cached = item
        return cached
    }

    @Synchronized
    fun cachedId(): String {
        val item = cached
        return if (item == null) "" else item.id
    }

    @Synchronized
    fun error(): String? = lastError

    @Synchronized
    fun queryCount(): Long = queries
}
