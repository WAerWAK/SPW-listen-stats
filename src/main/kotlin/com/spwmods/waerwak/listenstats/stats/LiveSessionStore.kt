package com.spwmods.waerwak.listenstats.stats

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 当前播放会话的崩溃保护
 *
 * 采集状态平时只在内存里：SPW 被强杀、崩溃或断电时，正在听的这一遍时长会全部丢失。
 * 这里把「未结算的会话」定期写进 `live-session.json`，下次启动时补齐。
 *
 * 关键约束：
 * - 写盘最多每 10 秒一次，且用「临时文件 + 原子改名」，避免写出半个 JSON。
 * - 恢复必须**幂等**：结算成功立刻把文件写成空标记（`ms = 0`），下次启动不会重复计入。
 * - 超过 7 天的残留视为过期（用户可能长期没开插件），忽略并清空。
 */
object LiveSessionStore {

    private const val FILE_NAME = "live-session.json"

    /** 落盘间隔 */
    private const val SAVE_INTERVAL_MS = 10_000L

    /** 超过这个年龄的残留不再恢复 */
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    class Snapshot(
        val startTimeMs: Long,
        val id: String,
        val path: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val listenedMs: Long,
        val durationMs: Long,
        val counted: Boolean,
        /** 本遍会话 id（合并导入去重用） */
        val eid: String = "",
        /** 实际听音区间（扁平 [start,dur,…]） */
        val spans: LongArray = LongArray(0),
        /** 区间是否含推算成分 */
        val approx: Boolean = false,
        /** true = 插件正常停用时保存的"待继续会话"（下次启动同曲目则接着记） */
        val suspended: Boolean = false
    )

    @Volatile
    private var lastSaveAt = 0L

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastRecoveredMs: Long = 0L
        private set

    /** 会话时长增长时调用：内部按间隔节流（调用方不必关心频率） */
    fun maybeSave(snapshot: Snapshot) {
        if (snapshot.listenedMs <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < SAVE_INTERVAL_MS) return
        lastSaveAt = now
        val dir = StatsStore.dataDirOrNull() ?: return
        try {
            if (!dir.isDirectory) dir.mkdirs()
            val target = File(dir, FILE_NAME)
            val tmp = File(dir, FILE_NAME + ".tmp")
            tmp.writeText(lineOf(snapshot, now), Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (t: Throwable) {
                // 某些文件系统不允许原子替换：退回直接写
                target.writeText(lineOf(snapshot, now), Charsets.UTF_8)
            }
            lastError = null
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
        }
    }

    /** 插件停用时调用：把当前会话原样保存为"可继续"快照（不结算、不算一次播放） */
    fun saveSuspended(snapshot: Snapshot) {
        if (snapshot.listenedMs <= 0L) return
        val dir = StatsStore.dataDirOrNull() ?: return
        try {
            if (!dir.isDirectory) dir.mkdirs()
            val target = File(dir, FILE_NAME)
            val tmp = File(dir, FILE_NAME + ".tmp")
            tmp.writeText(lineOf(snapshot, System.currentTimeMillis()), Charsets.UTF_8)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (t: Throwable) {
                target.writeText(lineOf(snapshot, System.currentTimeMillis()), Charsets.UTF_8)
            }
            lastError = null
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
        }
    }

    /** 读取当前快照（不结算、不清除）；没有或已过期返回 null */
    fun load(): Snapshot? {
        val dir = StatsStore.dataDirOrNull() ?: return null
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
            return null
        }
        return parse(text)
    }

    /** 正常结算后调用：清空标记（文件内容置为 ms=0，不删除文件） */
    fun clear() {
        lastSaveAt = 0L
        val dir = StatsStore.dataDirOrNull() ?: return
        try {
            val target = File(dir, FILE_NAME)
            if (target.isFile) target.writeText("{\"v\":2,\"ms\":0}", Charsets.UTF_8)
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
        }
    }

    /** 解析快照文本；没有有效会话返回 null */
    private fun parse(text: String): Snapshot? {
        val ms = JsonLite.long(text, "ms")
        if (ms <= 0L) return null
        val savedAt = JsonLite.long(text, "savedAt")
        if (savedAt > 0L && System.currentTimeMillis() - savedAt > MAX_AGE_MS) return null
        var spans = PlayEvent.parseSpans(JsonLite.string(text, "s"))
        var approx = JsonLite.bool(text, "ap")
        var total = ms
        if (spans.size >= 2) {
            var sum = 0L
            var i = 0
            while (i + 1 < spans.size) {
                sum += spans[i + 1]
                i += 2
            }
            if (sum > 0L) total = sum
            if (total != ms) approx = true
        } else {
            // 旧格式（v1）只有总毫秒：用"结束时间倒推一整段"近似
            val start = JsonLite.long(text, "t")
            val endAt = if (start > 0L) start + ms else System.currentTimeMillis()
            spans = longArrayOf(endAt - ms, ms)
            approx = true
        }
        return Snapshot(
            startTimeMs = JsonLite.long(text, "t"),
            id = JsonLite.string(text, "id"),
            path = JsonLite.string(text, "path"),
            title = JsonLite.string(text, "ti"),
            artist = JsonLite.string(text, "ar"),
            album = JsonLite.string(text, "al"),
            albumArtist = JsonLite.string(text, "aa"),
            listenedMs = total,
            durationMs = JsonLite.long(text, "dur"),
            counted = JsonLite.bool(text, "c"),
            eid = JsonLite.string(text, "eid"),
            spans = spans,
            approx = approx,
            suspended = JsonLite.bool(text, "suspend")
        )
    }

    /**
     * 启动时调用：把上次未结算的会话补记进统计
     *
     * @return 是否成功补记
     */
    @Synchronized
    fun recover(): Boolean {
        val snap = load() ?: return false
        // 低于忽略阈值：整条丢弃（顺手清掉残留，避免每次启动都重算）
        if (snap.listenedMs < StatsThresholds.IGNORE_MIN_MS) {
            clear()
            return false
        }
        val start = snap.startTimeMs
        val event = PlayEvent(
            t = if (start > 0L) start + snap.listenedMs else System.currentTimeMillis(),
            id = snap.id,
            path = snap.path,
            title = snap.title,
            artist = snap.artist,
            album = snap.album,
            albumArtist = snap.albumArtist,
            ms = snap.listenedMs,
            dur = snap.durationMs,
            counted = snap.counted,
            skipped = !snap.counted,
            eid = snap.eid,
            spans = snap.spans,
            approx = snap.approx
        )
        return try {
            StatsStore.record(event)
            lastRecoveredMs = snap.listenedMs
            clear()
            true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
            false
        }
    }

    private fun lineOf(s: Snapshot, savedAt: Long): String {
        val sb = StringBuilder(320)
        sb.append('{')
        sb.append("\"v\":2")
        sb.append(",\"ms\":").append(s.listenedMs)
        sb.append(",\"t\":").append(s.startTimeMs)
        sb.append(",\"dur\":").append(s.durationMs)
        sb.append(",\"c\":").append(s.counted)
        sb.append(",\"savedAt\":").append(savedAt)
        if (s.id.isNotEmpty()) {
            sb.append(",\"id\":\"").append(JsonLite.escape(s.id)).append('"')
        }
        if (s.path.isNotEmpty()) {
            sb.append(",\"path\":\"").append(JsonLite.escape(s.path)).append('"')
        }
        if (s.title.isNotEmpty()) {
            sb.append(",\"ti\":\"").append(JsonLite.escape(s.title)).append('"')
        }
        if (s.artist.isNotEmpty()) {
            sb.append(",\"ar\":\"").append(JsonLite.escape(s.artist)).append('"')
        }
        if (s.album.isNotEmpty()) {
            sb.append(",\"al\":\"").append(JsonLite.escape(s.album)).append('"')
        }
        if (s.albumArtist.isNotEmpty()) {
            sb.append(",\"aa\":\"").append(JsonLite.escape(s.albumArtist)).append('"')
        }
        if (s.eid.isNotEmpty()) {
            sb.append(",\"eid\":\"").append(JsonLite.escape(s.eid)).append('"')
        }
        val spans = s.spans
        if (spans.size >= 2) {
            val text = StringBuilder(spans.size * 8)
            var i = 0
            while (i + 1 < spans.size) {
                if (i > 0) text.append(',')
                text.append(spans[i]).append(':').append(spans[i + 1])
                i += 2
            }
            sb.append(",\"s\":\"").append(text).append('"')
        }
        if (s.approx) sb.append(",\"ap\":true")
        if (s.suspended) sb.append(",\"suspend\":true")
        sb.append('}')
        return sb.toString()
    }
}
