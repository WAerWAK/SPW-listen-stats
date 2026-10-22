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
        val album: String,
        val albumArtist: String
    )

    private class Session(val key: String, var meta: Meta) {
        var listenedMs: Long = 0L
        var lastPosition: Long = -1L
        var lastWallNanos: Long = 0L
        var counted: Boolean = false
        var durationMs: Long = 0L
        /** 本遍的近似开始时间（崩溃恢复时用来还原时间位置） */
        var startTimeMs: Long = 0L
    }

    private var session: Session? = null
    private var playing: Boolean = false

    /**
     * 时间源（可注入，供回归测试用假时钟）
     *
     * 生产环境用系统时钟；测试里替换成可控的计数，才能验证跨天、暂停、
     * 睡眠等需要精确时间关系的场景。
     */
    internal var nanoClock: () -> Long = { System.nanoTime() }
    internal var wallClock: () -> Long = { System.currentTimeMillis() }

    /** 清空全部运行状态（仅测试用） */
    @Synchronized
    internal fun resetForTesting() {
        session = null
        playing = false
        nanoClock = { System.nanoTime() }
        wallClock = { System.currentTimeMillis() }
    }

    /** 时长查询线程（插件停用后会释放，重新启用时重建） */
    @Volatile
    private var io = newIo()

    private fun newIo() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "listen-stats-duration").apply { isDaemon = true }
    }

    /** 插件停用时调用：释放线程，避免重载插件累积 */
    @Synchronized
    internal fun shutdown() {
        try {
            io.shutdown()
        } catch (t: Throwable) {
            // 忽略
        }
        io = newIo()
    }

    // ---------------------------------------------------------------- 回调

    @Synchronized
    fun onTrack(item: PlaybackExtensionPoint.MediaItem) {
        val key = keyOf(item)
        if (key.isBlank()) return
        val meta = Meta(item.id, item.path, item.title, item.artist, item.album, item.albumArtist)

        val current = session
        if (current != null && current.key == key) {
            current.meta = merge(current.meta, meta)
            return
        }

        if (current != null) {
            persistFinished(current)
        }

        val fresh = Session(key, meta)
        fresh.lastWallNanos = nanoClock()
        fresh.startTimeMs = wallClock()
        session = fresh
        if (item.id.isNotBlank()) requestDuration(key, item.id)
    }

    fun onPosition(position: Long) {
        val snap = updatePosition(position) ?: return
        // 崩溃保护的落盘放到锁外：播放回调每秒好多次，不能让它等磁盘
        // （杀软扫描 / 慢盘时会把宿主播放卡住）
        LiveSessionStore.maybeSave(snap)
    }

    /** 更新一遍播放的进度，返回需要落盘的崩溃保护快照 */
    @Synchronized
    private fun updatePosition(position: Long): LiveSessionStore.Snapshot? {
        val current = session ?: return null
        val now = nanoClock()
        val last = current.lastPosition
        val lastWall = current.lastWallNanos

        if (last < 0L) {
            current.lastPosition = position
            current.lastWallNanos = now
            return null
        }

        if (position < last - BACK_JUMP_TOLERANCE_MS) {
            if (current.counted) {
                persistFinished(current)
                current.listenedMs = 0L
                current.counted = false
            }
            current.lastPosition = position
            current.lastWallNanos = now
            return null
        }

        val positionDelta = position - last
        val wallDelta = if (lastWall > 0L) (now - lastWall) / 1_000_000L else -1L
        current.lastPosition = position
        current.lastWallNanos = now

        if (!playing) return null
        if (positionDelta < 1L || positionDelta > MAX_STEP_MS) return null

        val effective = if (wallDelta >= 0L && wallDelta < positionDelta) wallDelta else positionDelta
        if (effective <= 0L) return null

        current.listenedMs += effective
        if (!current.counted) {
            val threshold = StatsThresholds.countThreshold(current.durationMs)
            if (current.listenedMs >= threshold) current.counted = true
        }
        // 调用方在锁外写盘（进程被强杀时最多丢 10 秒）
        return LiveSessionStore.Snapshot(
            startTimeMs = current.startTimeMs,
            id = current.meta.id,
            path = current.meta.path,
            title = current.meta.title,
            artist = current.meta.artist,
            album = current.meta.album,
            albumArtist = current.meta.albumArtist,
            listenedMs = current.listenedMs,
            durationMs = current.durationMs,
            counted = current.counted
        )
    }

    @Synchronized
    fun onSeek(position: Long) {
        val current = session ?: return
        if (current.counted && position <= RESTART_POSITION_MS) {
            persistFinished(current)
            current.listenedMs = 0L
            current.counted = false
        }
        current.lastPosition = position
        current.lastWallNanos = nanoClock()
    }

    @Synchronized
    fun onPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        val current = session ?: return
        // 恢复播放时重置墙钟基准，避免把暂停期间的时间补进时长
        current.lastWallNanos = if (isPlaying) nanoClock() else 0L
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
        persistFinished(current)
    }

    // ---------------------------------------------------------------- 内部

    /** 结算并写入统计；写入成功后清空崩溃保护的残留 */
    private fun persistFinished(current: Session) {
        val finished = settle(current)
        if (finished != null) StatsStore.record(finished)
        // 即使这一遍被丢弃也要清空崩溃保护，否则下次启动会把它当成未结算会话又恢复一次
        LiveSessionStore.clear()
    }

    private fun settle(current: Session): PlayEvent? {
        if (current.listenedMs <= 0L) return null
        // 极短播放整条丢弃：连点「下一首」不该堆出一串 1~2 秒的噪音记录
        if (current.listenedMs < StatsThresholds.IGNORE_MIN_MS) return null
        val dur = current.durationMs
        val counted = current.counted
        // 过了忽略阈值但没到计数门槛 → 记一次跳过
        val skipped = !counted
        return PlayEvent(
            t = wallClock(),
            id = current.meta.id,
            path = current.meta.path,
            title = current.meta.title,
            artist = current.meta.artist,
            album = current.meta.album,
            albumArtist = current.meta.albumArtist,
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
        album = if (fresh.album.isBlank()) old.album else fresh.album,
        albumArtist = if (fresh.albumArtist.isBlank()) old.albumArtist else fresh.albumArtist
    )
}
