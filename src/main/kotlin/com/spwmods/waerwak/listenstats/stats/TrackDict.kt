package com.spwmods.waerwak.listenstats.stats

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * 曲目字典（方案 B 第一层）
 *
 * 每次播放的聚合行只写 8 位 hex key，标题/艺术家/专辑/时长在这里各存一次，
 * 避免每条记录重复几百字节的元信息。
 */
internal object TrackDict {

    class Entry {
        var id: String = ""
        var title: String = ""
        var artist: String = ""
        var album: String = ""
        var albumArtist: String = ""
        var dur: Long = 0L
    }

    private val map = HashMap<String, Entry>()
    private val pending = ArrayList<String>()
    private var file: File? = null

    /**
     * 稳定的 hex key（同一首曲目恒定，跨启动一致）
     *
     * 注意：这里是 hashCode 的十六进制形式，**不补前导零**（长度 1–8）。
     * 补零会改变已有数据的 key、让旧聚合记录对不上曲目，所以保持现状。
     */
    fun keyOf(id: String, path: String): String {
        var src = id
        if (src.isBlank()) src = path
        var h = 0
        var i = 0
        while (i < src.length) {
            h = 31 * h + src[i].code
            i++
        }
        return Integer.toHexString(h)
    }

    fun get(key: String): Entry? = map[key]

    /**
     * 全部 (hex key → 宿主曲目 id) 快照（缺失检测用）
     *
     * id 为空的旧记录无法判定，直接跳过：它们本来也取不到封面。
     */
    @Synchronized
    fun snapshotKeys(): HashMap<String, String> {
        val out = HashMap<String, String>(map.size * 2)
        for (entry in map.entries) {
            val id = entry.value.id
            if (id.isNotEmpty()) out[entry.key] = id
        }
        return out
    }

    fun load(f: File) {
        file = f
        if (!f.isFile) return
        try {
            val reader = Files.newBufferedReader(f.toPath(), Charsets.UTF_8)
            try {
                while (true) {
                    val line = reader.readLine()
                    if (line == null) break
                    if (line.isEmpty() || line[0] != '{') continue
                    val key = JsonLite.string(line, "k")
                    if (key.isEmpty()) continue
                    val entry = Entry()
                    entry.id = JsonLite.string(line, "id")
                    entry.title = JsonLite.string(line, "ti")
                    entry.artist = JsonLite.string(line, "ar")
                    entry.album = JsonLite.string(line, "al")
                    // 旧数据没有 aa 字段：回退到 artist，保证专辑分组键可用
                    val storedAa = JsonLite.string(line, "aa")
                    entry.albumArtist = if (storedAa.isNotEmpty()) storedAa else entry.artist
                    entry.dur = JsonLite.long(line, "dur")
                    map[key] = entry
                }
            } finally {
                reader.close()
            }
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 读取曲目字典失败: " + t.message)
        }
    }

    /** 确保字典里有这首曲目；缺失的字段会被补全 */
    @Synchronized
    fun ensure(
        key: String,
        id: String,
        title: String,
        artist: String,
        album: String,
        dur: Long,
        albumArtist: String = ""
    ) {
        val existing = map[key]
        if (existing != null) {
            var changed = false
            if (existing.id.isBlank() && id.isNotBlank()) {
                existing.id = id
                changed = true
            }
            if (existing.title.isBlank() && title.isNotBlank()) {
                existing.title = title
                changed = true
            }
            if (existing.artist.isBlank() && artist.isNotBlank()) {
                existing.artist = artist
                changed = true
            }
            if (existing.album.isBlank() && album.isNotBlank()) {
                existing.album = album
                changed = true
            }
            if (existing.albumArtist.isBlank() && albumArtist.isNotBlank()) {
                existing.albumArtist = albumArtist
                changed = true
            }
            if (existing.dur <= 0L && dur > 0L) {
                existing.dur = dur
                changed = true
            }
            // 补全过的字段要重新落盘，否则重启后又变空（旧数据 / 迁移场景）
            if (changed) {
                pending.add(lineOf(key, existing))
                if (pending.size >= 32) flush()
            }
            return
        }
        val entry = Entry()
        entry.id = id
        entry.title = title
        entry.artist = artist
        entry.album = album
        entry.albumArtist = if (albumArtist.isNotBlank()) albumArtist else artist
        entry.dur = dur
        map[key] = entry
        pending.add(lineOf(key, entry))
        if (pending.size >= 32) flush()
    }

    /** 清空内存字典（恢复备份后调用，避免旧曲目名残留） */
    @Synchronized
    fun clear() {
        map.clear()
        pending.clear()
    }

    /** 追加待写行（由写入线程调用） */
    @Synchronized
    fun flush() {
        val target = file ?: return
        if (pending.isEmpty()) return
        val lines = ArrayList<String>(pending)
        try {
            val parent = target.parentFile
            if (parent != null) parent.mkdirs()
            Files.write(
                target.toPath(),
                lines,
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
            // 写成功才清队列：失败时保留这几行，下次 flush 重试
            pending.clear()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 写入曲目字典失败: " + t.message)
            // 避免一直失败时无限堆积
            if (pending.size > 4096) pending.clear()
        }
    }

    private fun lineOf(key: String, entry: Entry): String {
        val sb = StringBuilder(160)
        sb.append('{')
        sb.append("\"k\":\"").append(JsonLite.escape(key)).append("\",")
        sb.append("\"id\":\"").append(JsonLite.escape(entry.id)).append("\",")
        sb.append("\"ti\":\"").append(JsonLite.escape(entry.title)).append("\",")
        sb.append("\"ar\":\"").append(JsonLite.escape(entry.artist)).append("\",")
        sb.append("\"al\":\"").append(JsonLite.escape(entry.album)).append("\",")
        if (entry.albumArtist.isNotEmpty()) {
            sb.append("\"aa\":\"").append(JsonLite.escape(entry.albumArtist)).append("\",")
        }
        sb.append("\"dur\":").append(entry.dur)
        sb.append('}')
        return sb.toString()
    }
}
