package com.spwmods.waerwak.listenstats.stats

/** 一遍播放结束产生的事件（事件日志的一行） */
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
    val skipped: Boolean
) {
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
        sb.append('}')
        return sb.toString()
    }

    companion object {
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
                skipped = JsonLite.bool(line, "skipped")
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
