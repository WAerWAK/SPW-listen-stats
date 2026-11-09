package com.spwmods.waerwak.listenstats.stats

import java.io.File

/**
 * 计时诊断
 *
 * L1（常开）：每遍结算时若有异常，由 SessionTracker 往 listen-stats.log 追加一行摘要。
 * L2（默认关）：内存环形缓冲记录逐帧判定（位置/间隔/播放态/接受与否/原因），
 * 用户在信息页打开后可导出 timing-diag.txt，用于定位多记/少记。
 *
 * 所有写入都只在导出时发生；正常运行时只有一次数组写入，开销可忽略。
 */
object TimingDiag {

    const val REASON_OK = 0
    const val REASON_POSITION_UNCHANGED = 1
    const val REASON_MAX_GAP = 2
    const val REASON_POSITION_JUMP = 3
    const val REASON_NOT_PLAYING = 4
    const val REASON_INVALID_ELAPSED = 5
    const val REASON_STALL_CLAMPED = 6
    const val REASON_NO_SESSION = 7
    const val REASON_BASELINE = 8

    private const val CAPACITY = 4096

    @Volatile
    var enabled: Boolean = false
        private set

    private val nano = LongArray(CAPACITY)
    private val position = LongArray(CAPACITY)
    private val delta = LongArray(CAPACITY)
    private val elapsed = LongArray(CAPACITY)
    private val listened = LongArray(CAPACITY)
    private val speed = FloatArray(CAPACITY)
    private val playing = IntArray(CAPACITY)
    private val trackHash = IntArray(CAPACITY)
    private val accepted = BooleanArray(CAPACITY)
    private val reason = IntArray(CAPACITY)

    private var index = 0
    private var total = 0L

    @Volatile
    var rejectedTotal: Long = 0L
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun clear() {
        index = 0
        total = 0L
        rejectedTotal = 0L
    }

    fun frameCount(): Long = total

    /** 逐帧记录（L2 关闭时直接返回） */
    fun record(
        trackIdHash: Int,
        positionMs: Long,
        deltaMs: Long,
        elapsedMs: Long,
        playingState: Int,
        acceptedFlag: Boolean,
        reasonCode: Int,
        listenedMs: Long,
        speedValue: Float
    ) {
        if (!enabled) return
        val slot = index
        {
            nano[slot] = System.nanoTime()
            position[slot] = positionMs
            delta[slot] = deltaMs
            elapsed[slot] = elapsedMs
            listened[slot] = listenedMs
            speed[slot] = speedValue
            playing[slot] = playingState
            trackHash[slot] = trackIdHash
            accepted[slot] = acceptedFlag
            reason[slot] = reasonCode
        }
        index = (slot + 1) % CAPACITY
        total++
        if (!acceptedFlag) rejectedTotal++
    }

    /**
     * 导出最近一帧到文件；返回写入帧数，失败返回 -1
     *
     * 超过 2MB 时把旧文件改名为 timing-diag.1.txt（保留一份）。
     */
    fun dump(dir: File): Int {
        if (total <= 0L) return 0
        val file = File(dir, "timing-diag.txt")
        try {
            if (!dir.isDirectory) dir.mkdirs()
            if (file.isFile && file.length() > 2L * 1024L * 1024L) {
                val old = File(dir, "timing-diag.1.txt")
                if (old.isFile) old.delete()
                file.renameTo(old)
            }
            val frames = if (total < CAPACITY) total.toInt() else CAPACITY
            val start = if (total < CAPACITY) 0 else index
            val sb = StringBuilder(frames * 80 + 256)
            sb.append("# 听歌统计逐帧计时诊断\n")
            sb.append("# reason: 0=接受 1=位置未变 2=超长空档 3=位置跳变 4=暂停态 5=间隔异常 6=停摆钳制 7=无会话 8=建基准\n")
            sb.append("# nano_ms position delta elapsed listenedMs speed playing(0未知/1播放/2暂停) trackHash accepted reason\n")
            var k = 0
            while (k < frames) {
                val slot = (start + k) % CAPACITY
                sb.append(nano[slot] / 1_000_000L).append(' ')
                    .append(position[slot]).append(' ')
                    .append(delta[slot]).append(' ')
                    .append(elapsed[slot]).append(' ')
                    .append(listened[slot]).append(' ')
                    .append(speed[slot]).append(' ')
                    .append(playing[slot]).append(' ')
                    .append(trackHash[slot]).append(' ')
                    .append(if (accepted[slot]) 1 else 0).append(' ')
                    .append(reason[slot]).append('\n')
                k++
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
            return frames
        } catch (t: Throwable) {
            return -1
        }
    }
}
