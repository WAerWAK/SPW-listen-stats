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
 * 有效时长记的是**单调时钟增量**，位置只用来确认"确实在推进"：
 *  - 位置没推进（暂停 / 缓冲 / 重复回调）、或推进幅度比经过的时间快太多（拖进度条、跳转）→ 整步丢弃；
 *  - 采样停摆超过 MAX_STEP_MS、或位置跳变超过 4 倍容差 → 整步丢弃；
 *  - 基准（lastPosition / lastNano）在**判定之后**才提交，被丢弃的步进不会吃掉下一段的墙钟。
 *
 * 这样既不会被暂停/卡顿虚增，也不会因为宿主位置回调变密（位置粒度粗于回调）而按比例少记。
 *
 * 刻意不用 Kotlin inline 集合/作用域函数（also/ifBlank/minOf…）：
 * Kotlin 2.4.21 + Compose 插件 + in-process 编译下它们会触发后端 codegen 崩溃。
 */
object SessionTracker {

    private const val BACK_JUMP_TOLERANCE_MS = 3_000L
    private const val RESTART_POSITION_MS = 3_000L

    /** 单步可接受的最大墙钟跨度：超过说明采样停摆（切后台/休眠/线程被卡），整段丢弃 */
    private const val MAX_STEP_MS = 15_000L

    /** 位置推进的容差倍数：允许 1–4 倍速播放，超过视为拖进度条/跳转 */
    private const val STEP_TOLERANCE_FACTOR = 4L

    /** 容差的绝对下限（回调间隔很短时避免误杀正常播放） */
    private const val STEP_TOLERANCE_FLOOR_MS = 3_000L

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
        var lastNano: Long = 0L
        var counted: Boolean = false
        var durationMs: Long = 0L
        /** 本遍的近似开始时间（崩溃恢复时用来还原时间位置） */
        var startTimeMs: Long = 0L
        /** 本遍会话 id：合并导入时用来去重 */
        val eid: String = java.util.UUID.randomUUID().toString()
        /** 实际听音区间，扁平存 [start, dur, start, dur, …]（绝对 epoch ms） */
        val spans = ArrayList<LongArray>()
        /** 区间是否含推算成分（墙钟被调整过 / 崩溃恢复补齐） */
        var approx: Boolean = false
        /** 上一步的墙钟时间，用来发现系统时钟跳变 */
        var lastWallMs: Long = 0L
    }

    private var session: Session? = null

    /**
     * 播放态：null = 还没收到过明确状态
     *
     * 宿主不提供查询接口，注册扩展时也不会补发当前状态 —— 播放中途启用/热重载插件时，
     * 如果死等 onIsPlayingChanged(true)，这一遍会整段不记。所以 null 时允许由"位置确实在推进"建立播放态。
     */
    private var playing: Boolean? = null

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
        playing = null
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
        fresh.lastNano = nanoClock()
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
        val lastNano = current.lastNano

        // 首帧 / 刚建立基准：只记录基准，不计数（插件可能是播放中途才启用的）
        if (last < 0L || lastNano <= 0L) {
            current.lastPosition = position
            current.lastNano = now
            return null
        }

        val delta = position - last
        val elapsed = (now - lastNano) / 1_000_000L
        if (firstPlayingState == 0) {
            firstPlayingState = if (playing == null) 3 else if (playing == true) 1 else 2
        }
        if (elapsed > maxGapMs) maxGapMs = elapsed

        // 大幅回退（拖回开头 / 单曲循环 / 宿主重置位置）：已计过数就结算这一遍
        if (delta < -BACK_JUMP_TOLERANCE_MS) {
            current.lastPosition = position
            current.lastNano = now
            if (current.counted) {
                persistFinished(current)
                current.listenedMs = 0L
                current.counted = false
            }
            return null
        }

        // 位置没推进（暂停 / 缓冲 / 重复回调）：**不动基准**。
        // 若在这里提交墙钟基准，宿主位置粒度比回调粗时会按比例少记（每秒只记最后一次回调的间隔）。
        if (delta == 0L) return null

        // 位置在动但数据不可信（停摆过久 / 幅度超过容差 = 拖进度条、跳转）：重建基准，不计数
        if (elapsed < 0L || elapsed > MAX_STEP_MS || !withinStepTolerance(delta, elapsed) || playing == false) {
            if (elapsed > MAX_STEP_MS || (playing != false && !withinStepTolerance(delta, elapsed))) droppedSteps++
            current.lastPosition = position
            current.lastNano = now
            return null
        }

        // 位置确实在推进 → 建立播放态（宿主不会在注册时补发状态）
        if (playing == null) playing = true

        // 记的是经过的时间，位置只作证据：宿主位置粒度比回调粗时也不会少记
        current.listenedMs += elapsed
        appendSpan(current, wallClock(), elapsed)
        if (!current.counted) {
            val threshold = StatsThresholds.countThreshold(current.durationMs)
            if (current.listenedMs >= threshold) current.counted = true
        }

        current.lastPosition = position
        current.lastNano = now
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
            counted = current.counted,
            eid = current.eid,
            spans = flattenSpans(current),
            approx = current.approx
        )
    }

    /** 追加一段实际听音区间；与上一段首尾相接时合并，避免区间碎片化 */
    private fun appendSpan(current: Session, wallNow: Long, elapsed: Long) {
        if (elapsed <= 0L) return
        if (current.lastWallMs > 0L) {
            val expected = current.lastWallMs + elapsed
            if (wallNow - expected > 100L || expected - wallNow > 100L) current.approx = true
        }
        val start = wallNow - elapsed
        current.lastWallMs = wallNow
        val n = current.spans.size
        if (n >= 1) {
            val last = current.spans[n - 1]
            if (last[0] + last[1] == start) {
                current.spans[n - 1] = longArrayOf(last[0], last[1] + elapsed)
                return
            }
        }
        current.spans.add(longArrayOf(start, elapsed))
    }

    /**
     * 位置推进幅度是否可信
     *
     * 允许 1–4 倍速（位置比时间快）；超过说明是拖进度条或跳转，不能算作正常播放。
     */
    private fun withinStepTolerance(delta: Long, elapsed: Long): Boolean {
        if (delta < 1L) return false
        val tolerance = elapsed * STEP_TOLERANCE_FACTOR + 250L
        val maxDelta = if (tolerance > STEP_TOLERANCE_FLOOR_MS) tolerance else STEP_TOLERANCE_FLOOR_MS
        return delta <= maxDelta
    }

    @Synchronized
    fun onSeek(position: Long) {
        val current = session ?: return
        if (current.counted && position <= RESTART_POSITION_MS) {
            persistFinished(current)
            current.listenedMs = 0L
            current.counted = false
        }
        // seek 之后下一帧只重建基准，不把跳过的这段算成收听
        current.lastPosition = position
        current.lastNano = nanoClock()
    }

    @Synchronized
    fun onPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        val current = session ?: return
        // 播放/暂停切换时重建基准：暂停期间的时间不能补进时长
        current.lastPosition = -1L
        current.lastNano = 0L
    }

    fun onState(state: PlaybackExtensionPoint.State) {
        if (state == PlaybackExtensionPoint.State.Idle ||
            state == PlaybackExtensionPoint.State.Ended
        ) {
            flush()
            return
        }
        // 缓冲时挂起采样：位置可能停住或回跳，这一段不算收听
        if (state == PlaybackExtensionPoint.State.Buffering) {
            suspendCurrentBaseline()
        }
    }

    @Synchronized
    private fun suspendCurrentBaseline() {
        val current = session ?: return
        current.lastPosition = -1L
        current.lastNano = 0L
    }

    // ---------------------------------------------------------------- 诊断

    /** 被丢弃的步进数（采样停摆 / 位置跳变） */
    @Volatile
    var droppedSteps: Long = 0L
        private set

    /** 观察到的最大回调空档（毫秒） */
    @Volatile
    var maxGapMs: Long = 0L
        private set

    /** 首次收到位置回调时的播放态：0 未知 / 1 播放 / 2 暂停（=0 说明插件是曲中启用的） */
    @Volatile
    var firstPlayingState: Int = 0
        private set

    @Synchronized
    internal fun resetDiagnostics() {
        droppedSteps = 0L
        maxGapMs = 0L
        firstPlayingState = 0
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
        val endAt = wallClock()
        var spans = flattenSpans(current)
        var approx = current.approx
        if (spans.size < 2) {
            // 没有任何区间（理论上进不来）：退化成"以结束时间倒推一整段"
            spans = longArrayOf(endAt - current.listenedMs, current.listenedMs)
            approx = true
        }
        return PlayEvent(
            t = endAt,
            id = current.meta.id,
            path = current.meta.path,
            title = current.meta.title,
            artist = current.meta.artist,
            album = current.meta.album,
            albumArtist = current.meta.albumArtist,
            ms = current.listenedMs,
            dur = dur,
            counted = counted,
            skipped = skipped,
            eid = current.eid,
            spans = spans,
            approx = approx
        )
    }

    /** 把区间列表压平成一维数组；重叠/乱序时按起点排序并裁剪，保证时长守恒 */
    private fun flattenSpans(current: Session): LongArray {
        val list = current.spans
        if (list.isEmpty()) return LongArray(0)
        var out = LongArray(list.size * 2)
        var i = 0
        while (i < list.size) {
            out[i * 2] = list[i][0]
            out[i * 2 + 1] = list[i][1]
            i++
        }
        // 插入排序（区间通常已经按时间递增，交换极少）
        var a = 1
        while (a < list.size) {
            var b = a
            while (b > 0 && out[(b - 1) * 2] > out[b * 2]) {
                val s = out[(b - 1) * 2]
                val d = out[(b - 1) * 2 + 1]
                out[(b - 1) * 2] = out[b * 2]
                out[(b - 1) * 2 + 1] = out[b * 2 + 1]
                out[b * 2] = s
                out[b * 2 + 1] = d
                b--
            }
            a++
        }
        // 裁剪重叠：后一段起点不得早于前一段结束
        var k = 1
        while (k < list.size) {
            val prevEnd = out[(k - 1) * 2] + out[(k - 1) * 2 + 1]
            val start = out[k * 2]
            val dur = out[k * 2 + 1]
            if (start < prevEnd) {
                val newDur = dur - (prevEnd - start)
                if (newDur <= 0L) {
                    out[k * 2 + 1] = 0L
                } else {
                    out[k * 2] = prevEnd
                    out[k * 2 + 1] = newDur
                }
                current.approx = true
            }
            k++
        }
        // 去掉裁剪后为 0 的区间
        var keep = 0
        var j = 0
        while (j < list.size) {
            if (out[j * 2 + 1] > 0L) {
                out[keep * 2] = out[j * 2]
                out[keep * 2 + 1] = out[j * 2 + 1]
                keep++
            }
            j++
        }
        if (keep == list.size) return out
        val trimmed = LongArray(keep * 2)
        var t = 0
        while (t < keep * 2) {
            trimmed[t] = out[t]
            t++
        }
        return trimmed
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
