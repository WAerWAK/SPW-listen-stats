package com.spwmods.waerwak.listenstats.stats

/**
 * 一遍播放结束产生的事件（事件日志的一行）
 *
 * @property t 事件结束时间（wall clock）
 * @property ms 本遍有效听音总毫秒（等于 spans 时长之和）
 * @property eid 本遍会话 id（UUID）：合并导入时用来去重
 * @property spans 实际听音区间，扁平存 `[start1, dur1, start2, dur2, …]`（绝对 epoch ms）；
 *   暂停/缓冲不在区间内，所以能精确按天/小时归属。旧事件没有这个字段时按单区间近似。
 * @property approx 区间是否由推算得到（旧数据、崩溃恢复、时钟被调整过）
 */
data class PlayEvent(
    val t: Long,
    val id: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val ms: Long,
    val dur: Long,
    val counted: Boolean,
    val skipped: Boolean,
    val eid: String = "",
    val spans: LongArray = LongArray(0),
    val approx: Boolean = false
) {
    /** 区间数量 */
    fun spanCount(): Int = spans.size / 2

    /** 把区间序列化成事件行里的紧凑字符串（start:dur,start:dur） */
    fun spansToText(): String {
        if (spans.size < 2) return ""
        val sb = StringBuilder(spans.size * 8)
        var i = 0
        while (i + 1 < spans.size) {
            if (i > 0) sb.append(',')
            sb.append(spans[i]).append(':').append(spans[i + 1])
            i += 2
        }
        return sb.toString()
    }

    fun toJsonLine(): String {
        val sb = StringBuilder(192)
        sb.append('{')
        sb.append("\"t\":").append(t).append(',')
        sb.append("\"id\":\"").append(JsonLite.escape(id)).append("\",")
        sb.append("\"path\":\"").append(JsonLite.escape(path)).append("\",")
        sb.append("\"title\":\"").append(JsonLite.escape(title)).append("\",")
        sb.append("\"artist\":\"").append(JsonLite.escape(artist)).append("\",")
        sb.append("\"album\":\"").append(JsonLite.escape(album)).append("\",")
        if (albumArtist.isNotEmpty()) {
            sb.append("\"aa\":\"").append(JsonLite.escape(albumArtist)).append("\",")
        }
        sb.append("\"ms\":").append(ms).append(',')
        sb.append("\"dur\":").append(dur).append(',')
        sb.append("\"counted\":").append(counted).append(',')
        sb.append("\"skipped\":").append(skipped)
        if (eid.isNotEmpty()) sb.append(",\"eid\":\"").append(JsonLite.escape(eid)).append('"')
        val spanText = spansToText()
        if (spanText.isNotEmpty()) sb.append(",\"s\":\"").append(spanText).append('"')
        if (approx) sb.append(",\"ap\":true")
        sb.append('}')
        return sb.toString()
    }

    companion object {
        /** 解析事件行里的区间串（"start:dur,start:dur"） */
        fun parseSpans(text: String): LongArray {
            if (text.isEmpty()) return LongArray(0)
            var count = 1
            var i = 0
            while (i < text.length) {
                if (text[i] == ',') count++
                i++
            }
            val out = LongArray(count * 2)
            var index = 0
            var pos = 0
            while (pos < text.length && index + 1 < out.size) {
                var comma = text.indexOf(',', pos)
                if (comma < 0) comma = text.length
                var part = text.substring(pos, comma)
                var colon = part.indexOf(':')
                if (colon > 0) {
                    val start = part.substring(0, colon).toLongOrNull()
                    val dur = part.substring(colon + 1).toLongOrNull()
                    if (start != null && dur != null && dur > 0L) {
                        out[index] = start
                        out[index + 1] = dur
                        index += 2
                    }
                }
                pos = comma + 1
            }
            if (index == out.size) return out
            val trimmed = LongArray(index)
            var k = 0
            while (k < index) {
                trimmed[k] = out[k]
                k++
            }
            return trimmed
        }

        fun fromJsonLine(line: String): PlayEvent? {
            if (line.isEmpty() || line[0] != '{') return null
            val t = JsonLite.long(line, "t", -1L)
            if (t < 0L) return null
            return PlayEvent(
                t = t,
                id = JsonLite.string(line, "id"),
                path = JsonLite.string(line, "path"),
                title = JsonLite.string(line, "title"),
                artist = JsonLite.string(line, "artist"),
                album = JsonLite.string(line, "album"),
                albumArtist = JsonLite.string(line, "aa"),
                ms = JsonLite.long(line, "ms"),
                dur = JsonLite.long(line, "dur"),
                counted = JsonLite.bool(line, "counted"),
                skipped = JsonLite.bool(line, "skipped"),
                eid = JsonLite.string(line, "eid"),
                spans = parseSpans(JsonLite.string(line, "s")),
                approx = JsonLite.bool(line, "ap")
            )
        }
    }
}

/** 单曲聚合 */
class TrackAgg(val key: String) {
    var id: String = ""
    var path: String = ""
    var title: String = ""
    var artist: String = ""
    var album: String = ""
    var albumArtist: String = ""
    var count: Int = 0
    var listenedMs: Long = 0L
    var skipCount: Int = 0
    var firstPlayed: Long = 0L
    var lastPlayed: Long = 0L
    var durationMs: Long = 0L
}

/** 按天聚合（全天汇总，含 24 小时分布） */
class DayAgg(val date: String) {
    var count: Int = 0
    var listenedMs: Long = 0L
    /** 每小时播放次数（下标 0-23） */
    val hours = IntArray(24)
    /** 每小时收听时长 */
    val hoursMs = LongArray(24)
}

/** 小时分布剖面（次数 + 时长） */
class HourProfile(val counts: IntArray, val ms: LongArray)

/** 图表选中区间的歌曲明细行 */
class RangeDetailRow(val key: String) {
    var title: String = ""
    var artist: String = ""
    var ms: Long = 0L
    var count: Int = 0
}

/** 某天某曲目的聚合（当天明细用） */
class DayTrackAgg(val date: String, val key: String) {
    var title: String = ""
    var artist: String = ""
    var count: Int = 0
    var listenedMs: Long = 0L
    var skipCount: Int = 0
    var lastPlayed: Long = 0L
}

/** 面板读取用的不可变快照 */
data class StatsSnapshot(
    val totalCount: Int,
    val totalMs: Long,
    val trackCount: Int,
    val skipCount: Int,
    val topTracks: List<TrackAgg>,
    val recentTracks: List<TrackAgg>
)

/** 歌曲分页结果 */
data class TrackPage(
    val items: List<TrackAgg>,
    val total: Int,
    val page: Int,
    val pageSize: Int
) {
    val pageCount: Int get() = if (total <= 0) 1 else (total + pageSize - 1) / pageSize
}

/** 专辑 / 艺术家聚合行 */
class AggregateRow(val name: String) {
    /** 专辑榜的副标题（专辑艺术家）；艺术家榜留空 */
    var owner: String = ""

    /** 组内最近一次播放时间 */
    var lastPlayed: Long = 0L
    var count: Int = 0
    var listenedMs: Long = 0L
    var skipCount: Int = 0
    var trackCount: Int = 0
    /** 组内缺失曲目数（0 = 都在；等于 trackCount = 全部缺失） */
    var missingCount: Int = 0
    /** 代表曲目的 id（用于取封面：组内听最多那首） */
    var coverId: String = ""
    /** 内部：当前代表曲目的次数 */
    internal var bestCount: Int = -1
}

/** 聚合分页结果 */
data class AggregatePage(
    val items: List<AggregateRow>,
    val total: Int,
    val page: Int,
    val pageSize: Int
) {
    val pageCount: Int get() = if (total <= 0) 1 else (total + pageSize - 1) / pageSize
}

/** 计数与跳过阈值（与 SessionTracker 共用；具体规则由 [CountRule] 维护） */
internal object StatsThresholds {

    /** 低于该时长整条丢弃：不写事件、不计时长、也不记跳过 */
    val IGNORE_MIN_MS: Long
        get() {
            CountRule.load()
            return CountRule.ignoreMinSeconds.toLong() * 1000L
        }

    fun countThreshold(durationMs: Long): Long {
        val rule = CountRule.thresholdMs(durationMs)
        val ignore = IGNORE_MIN_MS
        // 门槛必须不低于「忽略阈值」。否则介于两者之间的播放会被静默丢弃：
        // 没到门槛（不计数），又低于忽略阈值（连"跳过"都不记），规则自相矛盾
        return if (rule < ignore) ignore else rule
    }
}
