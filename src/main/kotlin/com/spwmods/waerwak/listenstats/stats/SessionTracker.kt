package com.spwmods.waerwak.listenstats.stats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 *  - 位置与墙钟一致的长间隔（后台/卡顿）照常计入；位置没跟上墙钟（停摆/缓冲）只计位置推进量；
 *  - 位置跳变超过 4 倍容差 / 超过 30 分钟硬上限 → 整步丢弃；
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

    /** 单步的硬上限（防御异常值）：超过视为不可信，整段丢弃 */
    private const val MAX_SANE_STEP_MS = 30L * 60L * 1000L

    /** 判定"位置没跟上墙钟"的最小间隔：这类步进只计位置推进量，避免把停摆/缓冲计入 */
    private const val STALL_MIN_MS = 2_500L

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
        var eid: String = java.util.UUID.randomUUID().toString()
        /** 实际听音区间，扁平存 [start, dur, start, dur, …]（绝对 epoch ms） */
        val spans = ArrayList<LongArray>()
        /** 区间是否含推算成分（墙钟被调整过 / 崩溃恢复补齐） */
        var approx: Boolean = false
        /** 上一步的墙钟时间，用来发现系统时钟跳变 */
        var lastWallMs: Long = 0L
        /** 本遍诊断计数（L1 摘要用） */
        var acceptedSteps: Long = 0L
        var sessionClampedSteps: Long = 0L
        var sessionClampedMs: Long = 0L
        var sessionDroppedSteps: Long = 0L
        var sessionMaxGapMs: Long = 0L
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
        // 权威曲目（TrackResolver 查询到的 MediaItem）通常自带时长，先用它；
        // 拿不到再异步补查。生产环境两者结果一致（异步补查也会立刻返回），
        // 但直接使用可以少一次宿主调用、并让计数门槛更早稳定。
        if (item.duration > 0L) fresh.durationMs = item.duration
        session = fresh
        if (fresh.durationMs <= 0L && item.id.isNotBlank()) requestDuration(key, item.id)
    }

    /** 当前是否已有会话（PlaybackPipeline 用来判断要不要立即解析当前曲目） */
    @Synchronized
    internal fun hasSession(): Boolean = session != null

    fun onPosition(position: Long) {
        val current = session
        if (current == null) {
            TimingDiag.record(0, position, 0L, 0L, 0, false, TimingDiag.REASON_NO_SESSION, 0L, 1.0f)
            return
        }
        val snap = updatePosition(current, position) ?: return
        // 崩溃保护的落盘放到锁外：播放回调每秒好多次，不能让它等磁盘
        // （杀软扫描 / 慢盘时会把宿主播放卡住）
        LiveSessionStore.maybeSave(snap)
    }

    /** 更新一遍播放的进度，返回需要落盘的崩溃保护快照 */
    @Synchronized
    private fun updatePosition(current: Session, position: Long): LiveSessionStore.Snapshot? {
        val now = nanoClock()
        val monoMs = now / 1_000_000L
        val speed = PlaybackSpeed.current(monoMs)
        val playingCode = if (playing == null) 0 else if (playing == true) 1 else 2
        val trackHash = current.key.hashCode()
        val last = current.lastPosition
        val lastNano = current.lastNano

        // 首帧 / 刚建立基准：只记录基准，不计数（插件可能是播放中途才启用的）
        if (last < 0L || lastNano <= 0L) {
            current.lastPosition = position
            current.lastNano = now
            TimingDiag.record(trackHash, position, 0L, 0L, playingCode, false, TimingDiag.REASON_BASELINE, current.listenedMs, speed)
            return null
        }

        val delta = position - last
        val elapsed = (now - lastNano) / 1_000_000L
        if (firstPlayingState == 0) {
            firstPlayingState = if (playing == null) 3 else if (playing == true) 1 else 2
        }
        if (elapsed > maxGapMs) maxGapMs = elapsed
        if (elapsed > current.sessionMaxGapMs) current.sessionMaxGapMs = elapsed

        // 大幅回退（拖回开头 / 单曲循环 / 宿主重置位置）：已计过数就结算这一遍
        if (delta < -BACK_JUMP_TOLERANCE_MS) {
            current.lastPosition = position
            current.lastNano = now
            TimingDiag.record(trackHash, position, delta, elapsed, playingCode, false, TimingDiag.REASON_BASELINE, current.listenedMs, speed)
            if (current.counted) {
                persistFinished(current)
                current.listenedMs = 0L
                current.counted = false
            }
            return null
        }

        // 位置没推进（暂停 / 缓冲 / 重复回调）：**不动基准**。
        // 若在这里提交墙钟基准，宿主位置粒度比回调粗时会按比例少记（每秒只记最后一次回调的间隔）。
        if (delta == 0L) {
            TimingDiag.record(trackHash, position, delta, elapsed, playingCode, false, TimingDiag.REASON_POSITION_UNCHANGED, current.listenedMs, speed)
            return null
        }

        // 数据不可信：超长空档 / 位置跳变（拖进度条、跳转）/ 明确不在播放 → 重建基准，不计数
        if (elapsed < 0L) {
            current.lastPosition = position
            current.lastNano = now
            TimingDiag.record(trackHash, position, delta, elapsed, playingCode, false, TimingDiag.REASON_INVALID_ELAPSED, current.listenedMs, speed)
            return null
        }
        if (elapsed > MAX_SANE_STEP_MS) {
            droppedSteps++
            current.sessionDroppedSteps++
            current.lastPosition = position
            current.lastNano = now
            TimingDiag.record(trackHash, position, delta, elapsed, playingCode, false, TimingDiag.REASON_MAX_GAP, current.listenedMs, speed)
            return null
        }
        if (playing == false) {
            current.lastPosition = position
            current.lastNano = now
            TimingDiag.record(trackHash, position, delta, elapsed, playingCode, false, TimingDiag.REASON_NOT_PLAYING, current.listenedMs, speed)
            return null
        }
        if (!withinStepTolerance(delta, elapsed, speed)) {
            droppedSteps++
            current.sessionDroppedSteps++
            current.lastPosition = position
            current.lastNano = now
            TimingDiag.record(trackHash, position, delta, elapsed, playingCode, false, TimingDiag.REASON_POSITION_JUMP, current.listenedMs, speed)
            return null
        }

        // 位置确实在推进 → 建立播放态（宿主不会在注册时补发状态）
        if (playing == null) playing = true

        // 正常播放但回调间隔较长（后台/主线程卡顿）：位置与墙钟同步前进，照常计入。
        // 位置明显没跟上墙钟（缓冲/解码停摆）：只按位置推进量/倍速折算成实际收听时间，
        // 避免把停顿算成收听，也避免 2 倍速时把 1 秒真实时间记成 2 秒。
        var counted = elapsed
        var reason = TimingDiag.REASON_OK
        if (elapsed > STALL_MIN_MS && delta.toDouble() * 2.0 < elapsed.toDouble() * speed) {
            counted = (delta.toDouble() / speed.toDouble()).toLong()
            if (counted < 1L) counted = 1L
            current.sessionClampedSteps++
            current.sessionClampedMs += (elapsed - counted)
            clampedSteps++
            reason = TimingDiag.REASON_STALL_CLAMPED
        }
        current.acceptedSteps++
        current.listenedMs += counted
        appendSpan(current, wallClock(), counted)
        if (!current.counted) {
            val threshold = StatsThresholds.countThreshold(current.durationMs)
            if (current.listenedMs >= threshold) current.counted = true
        }
        TimingDiag.record(trackHash, position, delta, elapsed, playingCode, true, reason, current.listenedMs, speed)

        current.lastPosition = position
        current.lastNano = now
        // 调用方在锁外写盘（进程被强杀时最多丢 10 秒）
        return snapshotOf(current)
    }

    /** 当前会话的崩溃保护快照 */
    private fun snapshotOf(current: Session): LiveSessionStore.Snapshot {
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
    private fun withinStepTolerance(delta: Long, elapsed: Long, speed: Float): Boolean {
        if (delta < 1L) return false
        val factor = if (speed > 0.1f) speed.toDouble() else STEP_TOLERANCE_FACTOR.toDouble()
        val tolerance = elapsed.toDouble() * factor * 1.25 + 500.0
        val maxDelta = if (tolerance > STEP_TOLERANCE_FLOOR_MS.toDouble()) tolerance else STEP_TOLERANCE_FLOOR_MS.toDouble()
        return delta.toDouble() <= maxDelta
    }

    @Synchronized
    fun onSeek(position: Long) {
        val current = session ?: return
        if (playing != false) countTail(current)
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
        val current = session
        // 暂停/结束时，最后一次计数帧到"暂停这一刻"之间仍是真实播放：
        // 通过状态回调补记（上限 1.5s，避免把状态回调延迟当成播放）
        if (!isPlaying && current != null && playing != false) countTail(current)
        playing = isPlaying
        // 不重建基准：暂停期间没有位置回调，恢复后第一帧 delta=0，
        // 下一次推进会因"位置明显落后墙钟"被停摆钳制为只计位置增量（暂停时间不计入）
    }

    /** 补记当前基准到"状态切换"之间的播放时间（仅供暂停/seek/停止使用） */
    private fun countTail(current: Session) {
        if (current.lastNano <= 0L) return
        val tail = (nanoClock() - current.lastNano) / 1_000_000L
        if (tail < 1L || tail > 1_500L) return
        current.listenedMs += tail
        appendSpan(current, wallClock(), tail)
        if (!current.counted) {
            val threshold = StatsThresholds.countThreshold(current.durationMs)
            if (current.listenedMs >= threshold) current.counted = true
        }
        current.lastNano = nanoClock()
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

    /** 被丢弃的步进数（超长空档 / 位置跳变） */
    @Volatile
    var droppedSteps: Long = 0L
        private set

    /** 被"只计位置推进量"钳制的步数（位置没跟上墙钟，多为缓冲/停摆） */
    @Volatile
    var clampedSteps: Long = 0L
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
        clampedSteps = 0L
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

    /**
     * 插件停用：把当前会话保存为"可继续"快照，不结算、不算一次播放
     *
     * 重新启用且还是同一首时由 [resumeFrom] 接着记，避免重载插件被识别成两次播放。
     */
    @Synchronized
    fun suspendCurrent() {
        val current = session ?: return
        session = null
        if (current.listenedMs <= 0L) return
        val snap = snapshotOf(current)
        val suspended = LiveSessionStore.Snapshot(
            startTimeMs = snap.startTimeMs,
            id = snap.id,
            path = snap.path,
            title = snap.title,
            artist = snap.artist,
            album = snap.album,
            albumArtist = snap.albumArtist,
            listenedMs = snap.listenedMs,
            durationMs = snap.durationMs,
            counted = snap.counted,
            eid = snap.eid,
            spans = snap.spans,
            approx = snap.approx,
            suspended = true
        )
        LiveSessionStore.saveSuspended(suspended)
    }

    /** 接续被 [suspendCurrent] 挂起的会话（同一首曲目） */
    @Synchronized
    fun resumeFrom(snapshot: LiveSessionStore.Snapshot): Boolean {
        if (session != null) return false
        val meta = Meta(
            snapshot.id, snapshot.path, snapshot.title,
            snapshot.artist, snapshot.album, snapshot.albumArtist
        )
        val key = keyOf(meta)
        if (key.isBlank()) return false
        val fresh = Session(key, meta)
        if (snapshot.eid.isNotBlank()) fresh.eid = snapshot.eid
        fresh.listenedMs = snapshot.listenedMs
        fresh.counted = snapshot.counted
        fresh.durationMs = snapshot.durationMs
        fresh.startTimeMs = snapshot.startTimeMs
        fresh.approx = snapshot.approx
        var i = 0
        while (i + 1 < snapshot.spans.size) {
            fresh.spans.add(longArrayOf(snapshot.spans[i], snapshot.spans[i + 1]))
            i += 2
        }
        fresh.lastNano = nanoClock()
        fresh.lastPosition = -1L
        session = fresh
        LiveSessionStore.clear()
        return true
    }

    // ---------------------------------------------------------------- 内部

    /** 结算并写入统计；写入成功后清空崩溃保护的残留 */
    private fun persistFinished(current: Session) {
        val finished = settle(current)
        if (finished != null) StatsStore.record(finished)
        // 即使这一遍被丢弃也要清空崩溃保护，否则下次启动会把它当成未结算会话又恢复一次
        LiveSessionStore.clear()
    }

    /**
     * L1 计时摘要：只在出现异常（钳制/丢步/时钟跳变/超长空档）时写一行到 listen-stats.log
     *
     * 正常的一遍不写，避免日志噪音；用户反馈"时长不准"时这行能直接看出原因。
     */
    private fun maybeLogTiming(current: Session) {
        val anomaly = current.sessionClampedSteps > 0L || current.sessionDroppedSteps > 0L ||
            current.approx || current.sessionMaxGapMs > MAX_SANE_STEP_MS / 2L
        if (!anomaly) return
        try {
            val dir = StatsStore.dataDirOrNull() ?: return
            val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()) +
                "  [timing] eid=" + current.eid +
                " dur=" + current.durationMs +
                " listened=" + current.listenedMs +
                " steps=" + current.acceptedSteps +
                " clampedN=" + current.sessionClampedSteps +
                " clampedMs=" + current.sessionClampedMs +
                " drops=" + current.sessionDroppedSteps +
                " maxGapMs=" + current.sessionMaxGapMs +
                " approx=" + current.approx +
                " speed=" + PlaybackSpeed.current(System.nanoTime() / 1_000_000L) + "\n"
            LogFiles.append(File(dir, "listen-stats.log"), line)
        } catch (t: Throwable) {
            // 诊断日志失败不影响统计
        }
    }

    private fun settle(current: Session): PlayEvent? {
        if (current.listenedMs <= 0L) return null
        maybeLogTiming(current)
        // 极短播放整条丢弃：连点「下一首」不该堆出一串 1~2 秒的噪音记录
        if (current.listenedMs < StatsThresholds.IGNORE_MIN_MS) return null
        val dur = current.durationMs
        val counted = current.counted
        // 过了忽略阈值但没到计数门槛 → 记一次跳过
        val skipped = !counted
        val endAt = wallClock()
        var spans = flattenSpans(current)
        var approx = current.approx
        // 时钟跳变等情况下区间可能被裁剪，与 listenedMs 不再一致：
        // 统计以监听时长为权威，区间退化为"结束时间倒推一整段"，避免日聚合比总时长少
        if (spans.size >= 2) {
            var spanSum = 0L
            var si = 0
            while (si + 1 < spans.size) {
                spanSum += spans[si + 1]
                si += 2
            }
            if (spanSum != current.listenedMs) {
                spans = longArrayOf(endAt - current.listenedMs, current.listenedMs)
                approx = true
            }
        }
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

    private fun keyOf(meta: Meta): String {
        var raw = meta.id
        if (raw.isBlank()) raw = meta.path
        if (raw.isBlank()) raw = meta.title + "|" + meta.artist + "|" + meta.album
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
