package com.spwmods.waerwak.listenstats.stats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.concurrent.Executors

/**
 * 一遍播放的状态机
 *
 * 一遍（pass）的边界：
 *  - 换曲（key 变化）
 *  - State.Idle / Ended
 *  - 已经计过数后又回到开头（单曲循环 / 手动重播）
 *  - seek 到开头且已计数
 *
 * 有效时长 = min(位置增量, 墙钟增量)，因此暂停、卡顿、拖进度条都不会虚增。
 *
 * 刻意不用 Kotlin inline 集合/作用域函数（also/ifBlank/minOf…）：
 * Kotlin 2.4.21 + Compose 插件 + in-process 编译下它们会触发后端 codegen 崩溃。
 */
object SessionTracker {

    private const val BACK_JUMP_TOLERANCE_MS = 3_000L
    private const val RESTART_POSITION_MS = 3_000L
    private const val MAX_STEP_MS = 15_000L

    private class Meta(
        val id: String,
        val path: String,
        val title: String,
        val artist: String,
        val album: String
    )

    private class Session(val key: String, var meta: Meta) {
        var listenedMs: Long = 0L
        var lastPosition: Long = -1L
        var lastWallNanos: Long = 0L
        var counted: Boolean = false
        var durationMs: Long = 0L
    }

    private var session: Session? = null
    private var playing: Boolean = false

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "listen-stats-duration").apply { isDaemon = true }
    }

    // ---------------------------------------------------------------- 回调

    @Synchronized
    fun onTrack(item: PlaybackExtensionPoint.MediaItem) {
        val key = keyOf(item)
        if (key.isBlank()) return
        val meta = Meta(item.id, item.path, item.title, item.artist, item.album)

        val current = session
        if (current != null && current.key == key) {
            current.meta = merge(current.meta, meta)
            return
        }

        if (current != null) {
            val finished = settle(current)
            if (finished != null) StatsStore.record(finished)
        }

        val fresh = Session(key, meta)
        fresh.lastWallNanos = System.nanoTime()
        session = fresh
        if (item.id.isNotBlank()) requestDuration(key, item.id)
    }

    @Synchronized
    fun onPosition(position: Long) {
        val current = session ?: return
        val now = System.nanoTime()
        val last = current.lastPosition
        val lastWall = current.lastWallNanos

        if (last < 0L) {
            current.lastPosition = position
            current.lastWallNanos = now
            return
        }

        if (position < last - BACK_JUMP_TOLERANCE_MS) {
            if (current.counted) {
                val finished = settle(current)
                if (finished != null) StatsStore.record(finished)
                current.listenedMs = 0L
                current.counted = false
            }
            current.lastPosition = position
            current.lastWallNanos = now
            return
        }

        val positionDelta = position - last
        val wallDelta = if (lastWall > 0L) (now - lastWall) / 1_000_000L else -1L
        current.lastPosition = position
        current.lastWallNanos = now

        if (!playing) return
        if (positionDelta < 1L || positionDelta > MAX_STEP_MS) return

        val effective = if (wallDelta >= 0L && wallDelta < positionDelta) wallDelta else positionDelta
        if (effective <= 0L) return

        current.listenedMs += effective
        if (!current.counted) {
            val threshold = StatsThresholds.countThreshold(current.durationMs)
            if (current.listenedMs >= threshold) current.counted = true
        }
    }

    @Synchronized
    fun onSeek(position: Long) {
        val current = session ?: return
        if (current.counted && position <= RESTART_POSITION_MS) {
            val finished = settle(current)
            if (finished != null) StatsStore.record(finished)
            current.listenedMs = 0L
            current.counted = false
        }
        current.lastPosition = position
        current.lastWallNanos = System.nanoTime()
    }

    @Synchronized
    fun onPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        val current = session ?: return
        // 恢复播放时重置墙钟基准，避免把暂停期间的时间补进时长
        current.lastWallNanos = if (isPlaying) System.nanoTime() else 0L
    }

    fun onState(state: PlaybackExtensionPoint.State) {
        if (state == PlaybackExtensionPoint.State.Idle ||
            state == PlaybackExtensionPoint.State.Ended
        ) {
            flush()
        }
    }

    /** 结算当前一遍（换曲 / 停止 / 插件停用） */
    @Synchronized
    fun flush() {
        val current = session ?: return
        session = null
        val finished = settle(current)
        if (finished != null) StatsStore.record(finished)
    }

    // ---------------------------------------------------------------- 内部

    private fun settle(current: Session): PlayEvent? {
        if (current.listenedMs <= 0L) return null
        val dur = current.durationMs
        val counted = current.counted
        var skipped = false
        if (!counted) {
            skipped = current.listenedMs < StatsThresholds.SKIP_MIN_MS
            if (!skipped && dur > 0L) {
                skipped = current.listenedMs * 100L / dur < 20L
            }
        }
        return PlayEvent(
            t = System.currentTimeMillis(),
            id = current.meta.id,
            path = current.meta.path,
            title = current.meta.title,
            artist = current.meta.artist,
            album = current.meta.album,
            ms = current.listenedMs,
            dur = dur,
            counted = counted,
            skipped = skipped
        )
    }

    private fun requestDuration(key: String, id: String) {
        io.execute {
            var fetched = 0L
            try {
                val item = WorkshopApi.playback.getCurrentMediaItem()
                if (item != null && item.id == id) fetched = item.duration
            } catch (t: Throwable) {
                fetched = 0L
            }
            if (fetched > 0L) applyDuration(key, fetched)
        }
    }

    @Synchronized
    private fun applyDuration(key: String, durationMs: Long) {
        val current = session
        if (current != null && current.key == key) current.durationMs = durationMs
    }

    private fun keyOf(item: PlaybackExtensionPoint.MediaItem): String {
        var raw = item.id
        if (raw.isBlank()) raw = item.path
        if (raw.isBlank()) raw = item.title + "|" + item.artist + "|" + item.album
        return raw.trim().lowercase()
    }

    private fun merge(old: Meta, fresh: Meta): Meta = Meta(
        id = if (fresh.id.isBlank()) old.id else fresh.id,
        path = if (fresh.path.isBlank()) old.path else fresh.path,
        title = if (fresh.title.isBlank()) old.title else fresh.title,
        artist = if (fresh.artist.isBlank()) old.artist else fresh.artist,
        album = if (fresh.album.isBlank()) old.album else fresh.album
    )
}
