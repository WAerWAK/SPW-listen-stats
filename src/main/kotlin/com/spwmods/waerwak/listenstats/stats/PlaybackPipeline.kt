package com.spwmods.waerwak.listenstats.stats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 播放事件单一处理线程
 *
 * 宿主回调（1 Hz 位置 / 播放态 / seek / 状态 / 歌词）只做一件事：带上时间戳入队后立即返回。
 * 所有会碰磁盘、碰宿主查询、碰统计状态的逻辑都在本对象自己的守护线程里串行执行：
 *
 *  - 不占宿主主线程：快照写盘、门槛配置读取、首次数据加载不再阻塞播放/UI；
 *  - 串行处理：歌词回调（IO 线程）与位置回调（主线程）并发进来也不会互相穿插；
 *  - 曲目身份以 [TrackResolver] 查询到的宿主当前曲目为准：不再依赖可能被其它插件短路的歌词回调。
 *
 * 队列有界（256）；1 Hz 下正常不会满，溢出时丢最旧帧并计数，绝不阻塞宿主线程。
 */
object PlaybackPipeline {

    private const val EV_POSITION = 0
    private const val EV_PLAYING = 1
    private const val EV_SEEK = 2
    private const val EV_STATE = 3
    private const val EV_LYRIC_HINT = 4

    private const val QUEUE_CAPACITY = 256
    private const val IDLE_POLL_MS = 200L

    /** 正常节奏下最多每秒主动校验一次曲目身份 */
    private const val TRACK_CHECK_MS = 1_000L

    /** 位置变化超过该值视为可疑（换曲 / 拖动），立即校验曲目身份 */
    private const val SUSPICIOUS_JUMP_MS = 3_000L

    private class Event(
        val type: Int,
        val position: Long,
        val playing: Boolean,
        val state: PlaybackExtensionPoint.State?,
        val nano: Long
    )

    private val queue = LinkedBlockingQueue<Event>(QUEUE_CAPACITY)

    @Volatile
    private var running = false

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var lastPosition = -1L

    @Volatile
    private var lastTrackCheckNano = 0L

    @Volatile
    private var forceTrackCheck = true

    @Volatile
    private var lastTrackId = ""

    @Volatile
    private var queueDropped = 0L

    @Volatile
    private var trackSwitches = 0L

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var busy = false

    // ------------------------------------------------------------ 宿主回调侧

    fun onPosition(position: Long) {
        enqueue(Event(EV_POSITION, position, false, null, System.nanoTime()))
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        enqueue(Event(EV_PLAYING, 0L, isPlaying, null, System.nanoTime()))
    }

    fun onSeek(position: Long) {
        enqueue(Event(EV_SEEK, position, false, null, System.nanoTime()))
    }

    fun onState(state: PlaybackExtensionPoint.State) {
        enqueue(Event(EV_STATE, 0L, false, state, System.nanoTime()))
    }

    /** 歌词回调只作提示：别的插件短路歌词循环也不影响统计 */
    fun onLyricHint() {
        enqueue(Event(EV_LYRIC_HINT, 0L, false, null, System.nanoTime()))
    }

    private fun enqueue(event: Event) {
        if (!running) return
        if (!queue.offer(event)) queueDropped++
    }

    // ------------------------------------------------------------ 生命周期

    @Synchronized
    fun start() {
        if (running) return
        queue.clear()
        lastPosition = -1L
        lastTrackCheckNano = 0L
        forceTrackCheck = true
        lastTrackId = ""
        running = true
        val t = Thread({ processLoop() }, "listen-stats-playback")
        t.isDaemon = true
        t.priority = Thread.NORM_PRIORITY
        thread = t
        t.start()
    }

    @Synchronized
    fun stop() {
        if (!running) {
            SessionTracker.suspendCurrent()
            return
        }
        running = false
        val t = thread
        thread = null
        if (t != null) {
            try {
                t.join(3_000L)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @Synchronized
    internal fun resetForTesting() {
        running = false
        val t = thread
        thread = null
        if (t != null) {
            try {
                t.join(3_000L)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        queue.clear()
        lastPosition = -1L
        lastTrackCheckNano = 0L
        forceTrackCheck = true
        lastTrackId = ""
        queueDropped = 0L
        trackSwitches = 0L
        lastError = null
    }

    // ------------------------------------------------------------ 处理线程

    private fun processLoop() {
        try {
            // 启动即解析当前曲目：播放中途启用/热重载插件也能接管当前这一遍
            bootstrapSession()
            while (running) {
                val event = queue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS)
                if (event != null) {
                    // busy 在取出后立刻置位：测试的 awaitIdle 依赖“队列空 + 不忙”两个条件，
                    // 如果等 process() 里才置位，会出现“已取出但尚未标记”的窗口而被误判为空闲
                    busy = true
                    try {
                        process(event)
                    } finally {
                        busy = false
                    }
                }
            }
            drainRemaining()
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
        } finally {
            try {
                // 插件停用：挂起当前会话（下次启动同曲目接着记，不算两次播放）
                SessionTracker.suspendCurrent()
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName + ": " + t.message
            }
        }
    }

    /**
     * 启动引导：
     * 1) 先读挂起快照，若是同一首曲目 → 接续上次会话（避免重载插件算两次播放）；
     * 2) 否则按旧逻辑补记上次未结算会话（崩溃/强杀保护）；
     * 3) 最后以权威曲目 seed 当前会话（曲中启用也能立刻开始记录）。
     */
    private fun bootstrapSession() {
        val nano = System.nanoTime()
        val item = TrackResolver.resolve(nano / 1_000_000L, true)
        val itemId = if (item == null) "" else item.id
        val snap = LiveSessionStore.load()
        if (snap != null && snap.suspended && itemId.isNotEmpty() && snap.id == itemId) {
            if (SessionTracker.resumeFrom(snap)) {
                lastTrackId = itemId
                lastTrackCheckNano = nano
                return
            }
        }
        try {
            if (LiveSessionStore.recover()) {
                System.err.println(
                    "[listen-stats] 已恢复上次未结算的会话：" +
                        (LiveSessionStore.lastRecoveredMs / 1000L) + " 秒"
                )
            }
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
        }
        if (item != null && item.id.isNotBlank()) {
            SessionTracker.onTrack(item)
            lastTrackId = item.id
        }
        lastTrackCheckNano = nano
    }

    private fun drainRemaining() {
        while (true) {
            val event = queue.poll() ?: return
            process(event)
        }
    }

    private fun process(event: Event) {
        try {
            handle(event)
        } finally {
            busy = false
        }
    }

    private fun handle(event: Event) {
        when (event.type) {
            EV_POSITION -> processPosition(event)
            EV_PLAYING -> {
                forceTrackCheck = true
                SessionTracker.onPlayingChanged(event.playing)
            }
            EV_SEEK -> {
                forceTrackCheck = true
                SessionTracker.onSeek(event.position)
            }
            EV_STATE -> {
                val state = event.state
                if (state != null) {
                    if (state != PlaybackExtensionPoint.State.Ready) forceTrackCheck = true
                    SessionTracker.onState(state)
                }
            }
            EV_LYRIC_HINT -> {
                forceTrackCheck = true
                syncTrack(event.nano)
            }
        }
    }

    private fun processPosition(event: Event) {
        val prev = lastPosition
        var suspicious = false
        if (prev >= 0L) {
            val forward = event.position - prev
            if (forward > SUSPICIOUS_JUMP_MS || forward < -SUSPICIOUS_JUMP_MS) suspicious = true
        }
        val due = lastTrackCheckNano == 0L ||
            event.nano - lastTrackCheckNano >= TRACK_CHECK_MS * 1_000_000L ||
            !SessionTracker.hasSession()
        if (forceTrackCheck || suspicious || due) syncTrack(event.nano)
        SessionTracker.onPosition(event.position)
        lastPosition = event.position
    }

    /**
     * 把「宿主当前曲目」同步给 SessionTracker
     *
     * 同一曲目重复调用只会合并元数据；曲目变化时才结算上一遍并开新遍。
     */
    private fun syncTrack(nano: Long) {
        val item = TrackResolver.resolve(nano / 1_000_000L, true)
        if (item == null || item.id.isBlank()) return
        if (item.id != lastTrackId) {
            if (lastTrackId.isNotEmpty()) trackSwitches++
            lastTrackId = item.id
        }
        SessionTracker.onTrack(item)
        lastTrackCheckNano = nano
        forceTrackCheck = false
    }

    // ------------------------------------------------------------ 诊断

    fun droppedEvents(): Long = queueDropped

    fun switches(): Long = trackSwitches

    fun error(): String? = lastError

    fun isRunning(): Boolean = running

    /** 测试用：等队列处理完（正常运行时不要调用） */
    internal fun awaitIdleForTesting(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while ((busy || !queue.isEmpty()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2L)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !busy && queue.isEmpty()
    }

    /** 测试诊断：卡住时把当前状态带进断言信息 */
    internal fun stateForTesting(): String =
        "busy=" + busy + " queue=" + queue.size + " running=" + running + " err=" + (lastError ?: "-")
}
