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
        val counted: Boolean
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

    /** 正常结算后调用：清空标记（文件内容置为 ms=0，不删除文件） */
    fun clear() {
        lastSaveAt = 0L
        val dir = StatsStore.dataDirOrNull() ?: return
        try {
            val target = File(dir, FILE_NAME)
            if (target.isFile) target.writeText("{\"v\":1,\"ms\":0}", Charsets.UTF_8)
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
        }
    }

    /**
     * 启动时调用：把上次未结算的会话补记进统计
     *
     * @return 是否成功补记
     */
    @Synchronized
    fun recover(): Boolean {
        val dir = StatsStore.dataDirOrNull() ?: return false
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return false
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
            return false
        }
        val ms = JsonLite.long(text, "ms")
        if (ms <= 0L) return false
        val savedAt = JsonLite.long(text, "savedAt")
        val now = System.currentTimeMillis()
        if (savedAt > 0L && now - savedAt > MAX_AGE_MS) {
            clear()
            return false
        }
        // 低于忽略阈值：整条丢弃（顺手清掉残留，避免每次启动都重算）
        if (ms < StatsThresholds.IGNORE_MIN_MS) {
            clear()
            return false
        }
        val dur = JsonLite.long(text, "dur")
        val counted = JsonLite.bool(text, "c")
        val skipped = !counted
        val start = JsonLite.long(text, "t")
        val event = PlayEvent(
            t = if (start > 0L) start + ms else now,
            id = JsonLite.string(text, "id"),
            path = JsonLite.string(text, "path"),
            title = JsonLite.string(text, "ti"),
            artist = JsonLite.string(text, "ar"),
            album = JsonLite.string(text, "al"),
            albumArtist = JsonLite.string(text, "aa"),
            ms = ms,
            dur = dur,
            counted = counted,
            skipped = skipped
        )
        return try {
            StatsStore.record(event)
            lastRecoveredMs = ms
            clear()
            true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
            false
        }
    }

    private fun lineOf(s: Snapshot, savedAt: Long): String {
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append("\"v\":1")
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
        sb.append('}')
        return sb.toString()
    }
}
