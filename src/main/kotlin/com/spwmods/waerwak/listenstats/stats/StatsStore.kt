package com.spwmods.waerwak.listenstats.stats

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 事件日志仓库 + 内存聚合
 *
 * 唯一数据源是 `events/YYYY-MM-DD.jsonl`（append-only，崩溃最多丢最后一行）。
 * 启动时全量重放构建内存索引；播放结束追加一行。
 *
 * 注意：本项目刻意不用 Kotlin 的 inline 集合函数（filter/map/also/sortedBy/buildString…）。
 * Kotlin 2.4.21 + Compose 编译器插件 + in-process 编译下，这类内联会触发后端 codegen
 * 崩溃（AnalyzerException: DUP / "try block should start with NOP"）。
 * 全部改写成手写循环 + 显式 Comparator。
 */
object StatsStore {

    private const val EVENTS_DIR = "events"
    private const val DAILY_DIR = "daily"
    private const val DICT_FILE = "tracks.jsonl"
    private const val EXPIRED_DIR = "expired"

    private val tracks = LinkedHashMap<String, TrackAgg>()
    private val days = LinkedHashMap<String, DayAgg>()
    /** 日期 → hexKey → 当天该曲目的聚合（当天明细 / 周月榜用） */
    private val dayTracks = LinkedHashMap<String, LinkedHashMap<String, DayTrackAgg>>()

    private var totalCount: Int = 0
    private var totalMs: Long = 0L
    private var skippedCount: Int = 0

    private var dataDir: File? = null
    private var loaded = false
    private var replayedLines = 0

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "listen-stats-writer").apply { isDaemon = true }
    }

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    private val hourFormat = SimpleDateFormat("HH", Locale.ROOT)

    @Synchronized
    fun attach(dir: File?) {
        if (dir != null && dataDir == null) dataDir = dir
    }

    /** 清空全部内存状态并绑定新的数据目录（仅测试用） */
    @Synchronized
    internal fun resetForTesting(dir: File?) {
        tracks.clear()
        days.clear()
        dayTracks.clear()
        replayedLines = 0
        TrackDict.clear()
        dataDir = dir
        loaded = false
    }

    @Synchronized
    fun dataDirOrNull(): File? {
        ensureLoaded()
        return dataDir
    }

    @Synchronized
    fun replayedLineCount(): Int {
        ensureLoaded()
        return replayedLines
    }

    /** 记录一遍播放：更新内存 + 异步落盘（原始明细 + 按月聚合） */
    @Synchronized
    fun record(event: PlayEvent) {
        ensureLoaded()
        applyToMemory(event)
        val dir = dataDir
        if (dir == null) return
        val startMs = if (event.ms > 0L && event.ms <= event.t) event.t - event.ms else event.t
        // 明细文件按「开始日」归档（与次数归属一致）
        val day = dayFormat.format(Date(startMs))
        val key = TrackDict.keyOf(event.id, event.path)
        val dailyLines = buildDailyLines(event, key)
        io.execute {
            try {
                // 1) 原始明细（仅保留最近 N 天）
                val eventFile = File(File(dir, EVENTS_DIR), day + ".jsonl")
                val eventParent = eventFile.parentFile
                if (eventParent != null) eventParent.mkdirs()
                Files.write(
                    eventFile.toPath(),
                    listOf(event.toJsonLine()),
                    Charsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )
                // 2) 按月聚合（永久）：跨天时会写多行，每行一个日期
                var li = 0
                while (li < dailyLines.size) {
                    val line = dailyLines[li]
                    val lineDay = JsonLite.string(line, "d")
                    val month = if (lineDay.length >= 7) lineDay.substring(0, 7) else lineDay
                    val dailyFile = File(File(dir, DAILY_DIR), month + ".jsonl")
                    val dailyParent = dailyFile.parentFile
                    if (dailyParent != null) dailyParent.mkdirs()
                    Files.write(
                        dailyFile.toPath(),
                        listOf(line),
                        Charsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    )
                    li++
                }
                // 3) 曲目字典
                TrackDict.flush()
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 写入统计失败: " + t.message)
            }
        }
    }

    /** 数据版本号（每次记录/清空自增，供 UI 判断是否需要刷新） */
    @Volatile
    var dataVersion: Long = 0L
        private set

    /** 备份全部数据到 zip（**纯只读操作，不改动任何源文件，也不触发加载/迁移**） */
    @Synchronized
    fun backupTo(target: File): String {
        val dir = dataDir ?: return "没有数据目录"
        if (!dir.isDirectory) return "数据目录不存在"
        // 安全保护：不允许把备份写到数据文件本身上
        val targetPath = try {
            target.canonicalPath
        } catch (t: Throwable) {
            target.absolutePath
        }
        val dictFile = File(dir, DICT_FILE)
        if (targetPath == dictFile.absolutePath) return "备份失败：不能覆盖数据文件"
        if (target.exists() && target.isDirectory) return "备份失败：目标是目录"

        var zos: java.util.zip.ZipOutputStream? = null
        var written = 0
        var totalBytes = 0L
        try {
            zos = java.util.zip.ZipOutputStream(target.outputStream().buffered())
            if (dictFile.isFile) {
                writeEntry(zos, dictFile, DICT_FILE)
                written++
                totalBytes += dictFile.length()
            }
            val dailyResult = writeDir(zos, File(dir, DAILY_DIR), DAILY_DIR, targetPath)
            written += dailyResult[0].toInt()
            totalBytes += dailyResult[1]
            val eventsResult = writeDir(zos, File(dir, EVENTS_DIR), EVENTS_DIR, targetPath)
            written += eventsResult[0].toInt()
            totalBytes += eventsResult[1]
            for (extra in arrayOf("config.json", "ui-state.json", DICT_FILE + ".bak")) {
                val f = File(dir, extra)
                if (f.isFile && f.absolutePath != targetPath) {
                    writeEntry(zos, f, extra)
                    written++
                    totalBytes += f.length()
                }
            }
        } catch (t: Throwable) {
            closeQuietly(zos)
            log("备份失败：" + t.message)
            return "备份失败：" + t.message
        }
        closeQuietly(zos)
        if (written == 0) return "没有可备份的数据"
        log("备份完成：" + written + " 个文件，源数据共 " + totalBytes + " 字节 → " + target.absolutePath)
        return "已备份 " + written + " 个文件到 " + target.absolutePath
    }

    /** 追加一行到 listen-stats.log（排查用） */
    private fun log(message: String) {
        try {
            val dir = dataDir ?: return
            val file = File(dir, "listen-stats.log")
            val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()) +
                "  [backup] " + message + "\n"
            Files.write(
                file.toPath(),
                line.toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (t: Throwable) {
            System.err.println("[listen-stats] " + message)
        }
    }

    private fun writeEntry(zos: java.util.zip.ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(java.util.zip.ZipEntry(entryName))
        val input = file.inputStream().buffered()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                zos.write(buf, 0, n)
            }
        } catch (t: Throwable) {
            try {
                input.close()
            } catch (e: Throwable) {
                // 忽略
            }
            throw t
        }
        input.close()
        zos.closeEntry()
    }

    private fun writeDir(
        zos: java.util.zip.ZipOutputStream,
        dir: File,
        prefix: String,
        excludePath: String
    ): LongArray {
        val result = LongArray(2)
        if (!dir.isDirectory) return result
        val files = dir.listFiles() ?: return result
        for (f in files) {
            if (!f.isFile) continue
            val path = try {
                f.canonicalPath
            } catch (t: Throwable) {
                f.absolutePath
            }
            if (path == excludePath) continue
            writeEntry(zos, f, prefix + "/" + f.name)
            result[0] = result[0] + 1L
            result[1] = result[1] + f.length()
        }
        return result
    }

    private fun closeQuietly(zos: java.util.zip.ZipOutputStream?) {
        if (zos == null) return
        try {
            zos.closeEntry()
        } catch (t: Throwable) {
            // 忽略
        }
        try {
            zos.close()
        } catch (t: Throwable) {
            // 忽略
        }
    }

    /** 从 zip 恢复（现有数据先改名保留为 backup-时间戳） */
    @Synchronized
    fun restoreFrom(source: File): String {
        ensureLoaded()
        val dir = dataDir ?: return "没有数据目录"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        var restored = 0
        // 1) 先备份现有数据（改名，不删除）
        val dict = File(dir, DICT_FILE)
        if (dict.isFile) dict.renameTo(File(dir, DICT_FILE + "." + stamp + ".bak"))
        for (name in arrayOf(DAILY_DIR, EVENTS_DIR)) {
            val d = File(dir, name)
            if (d.isDirectory) d.renameTo(File(dir, name + "." + stamp + ".bak"))
        }
        // 2) 解压
        var zis: java.util.zip.ZipInputStream? = null
        try {
            zis = java.util.zip.ZipInputStream(source.inputStream().buffered())
            val buf = ByteArray(8192)
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name.isEmpty()) {
                    zis.closeEntry()
                    continue
                }
                val target = File(dir, name)
                val parent = target.parentFile
                if (parent != null) parent.mkdirs()
                val out = target.outputStream().buffered()
                var failed = false
                try {
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                } catch (t: Throwable) {
                    failed = true
                }
                out.close()
                zis.closeEntry()
                if (failed) break
                restored++
            }
        } catch (t: Throwable) {
            try {
                if (zis != null) zis.close()
            } catch (e: Throwable) {
                // 忽略
            }
            return "恢复失败：" + t.message
        }
        try {
            if (zis != null) zis.close()
        } catch (e: Throwable) {
            // 忽略
        }
        // 3) 重置内存，下次访问重新加载
        TrackDict.clear()
        tracks.clear()
        days.clear()
        dayTracks.clear()
        totalCount = 0
        totalMs = 0L
        skippedCount = 0
        replayedLines = 0
        loaded = false
        dataVersion++
        if (restored == 0) return "压缩包为空，未恢复任何数据"
        return "已恢复 " + restored + " 个文件（原数据已改名为 .bak 保留）"
    }


    /** 存储占用摘要（聚合 / 明细 / 字典） */
    @Synchronized
    fun storageSummary(): String {
        ensureLoaded()
        val dir = dataDir ?: return "-"
        val daily = dirSize(File(dir, DAILY_DIR))
        val events = dirSize(File(dir, EVENTS_DIR))
        val expired = dirSize(File(dir, EXPIRED_DIR))
        val dict = File(dir, DICT_FILE).length()
        val sb = StringBuilder(64)
        sb.append("聚合 ").append(formatSize(daily))
        sb.append(" · 明细 ").append(formatSize(events))
        sb.append(" · 字典 ").append(formatSize(dict))
        if (expired > 0L) sb.append(" · 已归档 ").append(formatSize(expired))
        return sb.toString()
    }

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        val files = dir.listFiles()
        if (files == null) return 0L
        var total = 0L
        for (f in files) {
            if (f.isFile) total += f.length()
        }
        return total
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return bytes.toString() + " B"
        if (bytes < 1024L * 1024L) return (bytes / 1024L).toString() + " KB"
        return (bytes / (1024L * 1024L)).toString() + " MB"
    }

    /** 指定日期范围的小时分布（date 格式 yyyy-MM-dd，含首尾） */
    @Synchronized
    fun hourHistogramRange(fromDate: String, toDate: String): IntArray {
        ensureLoaded()
        val out = IntArray(24)
        for (entry in days.entries) {
            val date = entry.key
            if (date < fromDate || date > toDate) continue
            val dayAgg = entry.value
            var i = 0
            while (i < 24) {
                out[i] += dayAgg.hours[i]
                i++
            }
        }
        return out
    }

    /** 指定日期范围的小时分布（次数 + 时长，date 格式 yyyy-MM-dd，含首尾） */
    @Synchronized
    fun hourProfile(fromDate: String, toDate: String): HourProfile {
        ensureLoaded()
        val counts = IntArray(24)
        val ms = LongArray(24)
        for (entry in days.entries) {
            val date = entry.key
            if (date < fromDate || date > toDate) continue
            val dayAgg = entry.value
            var i = 0
            while (i < 24) {
                counts[i] += dayAgg.hours[i]
                ms[i] += dayAgg.hoursMs[i]
                i++
            }
        }
        return HourProfile(counts, ms)
    }

    /** 按给定日期列表取每天的小时次数矩阵（周视图分层用） */
    @Synchronized
    fun hourMatrix(dates: Array<String>): Array<IntArray> {
        ensureLoaded()
        val out = Array(dates.size) { IntArray(24) }
        var d = 0
        while (d < dates.size) {
            val dayAgg = days[dates[d]]
            if (dayAgg != null) {
                var i = 0
                while (i < 24) {
                    out[d][i] = dayAgg.hours[i]
                    i++
                }
            }
            d++
        }
        return out
    }

    /** 按时间范围聚合后的曲目（sinceDate 为 null = 总榜；否则只统计 date >= sinceDate） */
    private fun rangeAggregatedTracks(sinceDate: String?): LinkedHashMap<String, TrackAgg> {
        val map = LinkedHashMap<String, TrackAgg>()
        if (sinceDate == null) {
            for (agg in tracks.values) map[agg.key] = agg
            return map
        }
        for (dayEntry in dayTracks.entries) {
            if (dayEntry.key < sinceDate) continue
            for (dt in dayEntry.value.values) {
                var agg = map[dt.key]
                if (agg == null) {
                    agg = TrackAgg(dt.key)
                    val src = tracks[dt.key]
                    if (src != null) {
                        agg.id = src.id
                        agg.path = src.path
                        agg.title = src.title
                        agg.artist = src.artist
                        agg.album = src.album
                        agg.albumArtist = src.albumArtist
                        agg.durationMs = src.durationMs
                    } else {
                        agg.title = dt.title
                        agg.artist = dt.artist
                    }
                    map[dt.key] = agg
                }
                agg.count += dt.count
                agg.listenedMs += dt.listenedMs
                agg.skipCount += dt.skipCount
                if (dt.lastPlayed > agg.lastPlayed) agg.lastPlayed = dt.lastPlayed
                if (dt.lastPlayed > 0L && (agg.firstPlayed == 0L || dt.lastPlayed < agg.firstPlayed)) {
                    agg.firstPlayed = dt.lastPlayed
                }
            }
        }
        return map
    }

    /** 歌曲分页（支持时间范围：sinceDate 为 null 表示总榜） */
    @Synchronized
    fun trackPageRange(
        page: Int,
        pageSize: Int,
        sortBy: Int,
        query: String,
        sinceDate: String?
    ): TrackPage {
        ensureLoaded()
        val needle = query.trim().lowercase()
        val source = rangeAggregatedTracks(sinceDate)
        val sorted = ArrayList<TrackAgg>()
        for (agg in source.values) {
            if (agg.count <= 0 && agg.listenedMs <= 0L) continue
            if (needle.isNotEmpty() && !matches(agg, needle)) continue
            sorted.add(agg)
        }
        sorted.sortWith(Comparator { a, b ->
            // 0=次数 1=时长 2=最近
            when (sortBy) {
                1 -> if (a.listenedMs != b.listenedMs) b.listenedMs.compareTo(a.listenedMs)
                else b.count.compareTo(a.count)
                2 -> if (a.lastPlayed != b.lastPlayed) b.lastPlayed.compareTo(a.lastPlayed)
                else b.count.compareTo(a.count)
                else -> if (a.count != b.count) b.count.compareTo(a.count)
                else b.listenedMs.compareTo(a.listenedMs)
            }
        })
        return buildTrackPage(sorted, page, pageSize)
    }

    /** 专辑 / 艺术家分页（支持时间范围 + 关键词过滤） */
    @Synchronized
    fun aggregatePageRange(
        page: Int,
        pageSize: Int,
        byAlbum: Boolean,
        sinceDate: String?,
        query: String,
        sortBy: Int = 0
    ): AggregatePage {
        ensureLoaded()
        val needle = query.trim().lowercase()
        val source = rangeAggregatedTracks(sinceDate)
        val map = LinkedHashMap<String, AggregateRow>()
        for (agg in source.values) {
            if (agg.count <= 0 && agg.listenedMs <= 0L) continue
            if (byAlbum) {
                val album = agg.album.trim()
                if (album.isEmpty()) continue
                val displayName = GroupKeys.albumDisplayName(album)
                val displayArtist = GroupKeys.albumDisplayArtist(agg.albumArtist, agg.artist)
                if (needle.isNotEmpty() &&
                    !displayName.lowercase().contains(needle) &&
                    !displayArtist.lowercase().contains(needle)
                ) {
                    continue
                }
                // 专辑用「专辑名 + 专辑艺术家」双键：不同歌手的同名专辑不会被合并
                val key = GroupKeys.albumGroupKey(album, agg.albumArtist, agg.artist, agg.key)
                var row = map[key]
                if (row == null) {
                    row = AggregateRow(displayName)
                    row.owner = displayArtist
                    map[key] = row
                }
                accumulate(row, agg)
            } else {
                // 艺术家署名可能包含多个合作者，拆开后各自计一次
                val parts = GroupKeys.splitArtists(agg.artist)
                var pi = 0
                while (pi < parts.size) {
                    val name = parts[pi]
                    pi++
                    if (name.isBlank()) continue
                    if (needle.isNotEmpty() && !name.lowercase().contains(needle)) continue
                    val key = name.lowercase()
                    var row = map[key]
                    if (row == null) {
                        row = AggregateRow(name)
                        map[key] = row
                    }
                    accumulate(row, agg)
                }
            }
        }
        val sorted = ArrayList<AggregateRow>()
        for (row in map.values) sorted.add(row)
        sorted.sortWith(Comparator { a, b ->
            // 0=次数 1=时长 2=最近
            when (sortBy) {
                1 -> if (a.listenedMs != b.listenedMs) b.listenedMs.compareTo(a.listenedMs)
                else b.count.compareTo(a.count)
                2 -> if (a.lastPlayed != b.lastPlayed) b.lastPlayed.compareTo(a.lastPlayed)
                else b.count.compareTo(a.count)
                else -> if (a.count != b.count) b.count.compareTo(a.count)
                else b.listenedMs.compareTo(a.listenedMs)
            }
        })
        val total = sorted.size
        val items = ArrayList<AggregateRow>()
        val from = page * pageSize
        var i = from
        while (i < total && items.size < pageSize) {
            val src = sorted[i]
            val copy = AggregateRow(src.name)
            copy.owner = src.owner
            copy.count = src.count
            copy.listenedMs = src.listenedMs
            copy.trackCount = src.trackCount
            copy.coverId = src.coverId
            copy.lastPlayed = src.lastPlayed
            items.add(copy)
            i++
        }
        return AggregatePage(items, total, page, pageSize)
    }

    /**
     * 取某个专辑 / 艺术家分组下的全部曲目（详情弹窗用）
     *
     * @param byAlbum true = 专辑， false = 艺术家
     * @param name 专辑名或艺术家名（与榜单显示名一致）
     * @param owner 专辑艺术家（仅专辑模式使用，空则不限）
     */
    @Synchronized
    fun tracksOfGroup(byAlbum: Boolean, name: String, owner: String): List<TrackAgg> {
        ensureLoaded()
        val out = ArrayList<TrackAgg>()
        val target = name.trim()
        if (target.isEmpty()) return out
        val source = rangeAggregatedTracks(null)
        for (agg in source.values) {
            if (agg.count <= 0 && agg.listenedMs <= 0L) continue
            if (byAlbum) {
                val album = agg.album.trim()
                if (album.isEmpty()) continue
                val displayName = GroupKeys.albumDisplayName(album)
                if (!displayName.equals(target, ignoreCase = true)) continue
                if (owner.isNotEmpty()) {
                    val displayOwner = GroupKeys.albumDisplayArtist(agg.albumArtist, agg.artist)
                    if (!displayOwner.equals(owner.trim(), ignoreCase = true)) continue
                }
                out.add(agg)
            } else {
                val parts = GroupKeys.splitArtists(agg.artist)
                var found = false
                var i = 0
                while (i < parts.size) {
                    if (parts[i].equals(target, ignoreCase = true)) {
                        found = true
                        break
                    }
                    i++
                }
                if (found) out.add(agg)
            }
        }
        out.sortWith(Comparator { a, b ->
            if (a.count != b.count) b.count.compareTo(a.count)
            else b.listenedMs.compareTo(a.listenedMs)
        })
        return out
    }

    /** 把一个曲目的数据累加到聚合行，并选出封面（取该组内最常听的曲目） */
    private fun accumulate(row: AggregateRow, agg: TrackAgg) {
        row.count += agg.count
        row.listenedMs += agg.listenedMs
        row.trackCount++
        if (agg.lastPlayed > row.lastPlayed) row.lastPlayed = agg.lastPlayed
        if (agg.count > row.bestCount && agg.id.isNotBlank()) {
            row.bestCount = agg.count
            row.coverId = agg.id
        } else if (row.coverId.isBlank() && agg.id.isNotBlank()) {
            row.coverId = agg.id
        }
    }

    /** 指定日期范围的播放次数（含首尾） */
    @Synchronized
    fun countInRange(fromDate: String, toDate: String): Int {
        ensureLoaded()
        var total = 0
        for (entry in days.entries) {
            val date = entry.key
            if (date < fromDate || date > toDate) continue
            total += entry.value.count
        }
        return total
    }

    /** 按 hex key 取单曲聚合（拷贝） */
    @Synchronized
    fun trackByKey(key: String): TrackAgg? {
        ensureLoaded()
        val agg = tracks[key] ?: return null
        return agg.copyOf()
    }

    /** 当前连续收听天数（今天未听则从昨天开始算，不算断） */
    @Synchronized
    fun currentStreak(): Int {
        ensureLoaded()
        var day = LocalDate.now()
        if (!hasPlayOn(day.toString())) day = day.minusDays(1)
        var streak = 0
        while (streak < 4000 && hasPlayOn(day.toString())) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    private fun hasPlayOn(date: String): Boolean {
        val d = days[date] ?: return false
        return d.count > 0 || d.listenedMs > 0L
    }

    /** 某曲目的最近播放记录（从原始明细读，仅覆盖保留期内） */
    @Synchronized
    fun trackHistory(trackId: String, limit: Int): List<PlayEvent> {
        ensureLoaded()
        val out = ArrayList<PlayEvent>()
        if (trackId.isBlank()) return out
        val dir = dataDir ?: return out
        val eventsDir = File(dir, EVENTS_DIR)
        if (!eventsDir.isDirectory) return out
        val files = eventsDir.listFiles()
        if (files == null) return out
        val list = ArrayList<File>()
        for (f in files) {
            if (f.isFile && f.name.endsWith(".jsonl")) list.add(f)
        }
        list.sortWith(Comparator { a, b -> b.name.compareTo(a.name) })
        for (file in list) {
            try {
                val reader = Files.newBufferedReader(file.toPath(), Charsets.UTF_8)
                try {
                    while (true) {
                        val line = reader.readLine()
                        if (line == null) break
                        val event = PlayEvent.fromJsonLine(line)
                        if (event == null) continue
                        if (event.id == trackId) out.add(event)
                    }
                } finally {
                    reader.close()
                }
            } catch (t: Throwable) {
                // 单个文件失败不影响其他
            }
        }
        out.sortWith(Comparator { a, b -> b.t.compareTo(a.t) })
        while (out.size > limit) out.removeAt(out.size - 1)
        return out
    }

    /** 某天的曲目明细（按次数降序） */
    @Synchronized
    fun dayDetail(date: String): List<DayTrackAgg> {
        ensureLoaded()
        val out = ArrayList<DayTrackAgg>()
        val perDay = dayTracks[date]
        if (perDay == null) return out
        for (dt in perDay.values) out.add(dt)
        out.sortWith(Comparator { a, b ->
            if (a.count != b.count) b.count.compareTo(a.count)
            else b.listenedMs.compareTo(a.listenedMs)
        })
        return out
    }

    @Synchronized
    fun snapshot(topLimit: Int = 10, recentLimit: Int = 10): StatsSnapshot {
        ensureLoaded()

        val byCount = ArrayList<TrackAgg>()
        for (agg in tracks.values) {
            if (agg.count > 0) byCount.add(agg)
        }
        byCount.sortWith(Comparator { a, b ->
            if (a.count != b.count) {
                b.count.compareTo(a.count)
            } else {
                b.listenedMs.compareTo(a.listenedMs)
            }
        })
        val top = ArrayList<TrackAgg>()
        var topCount = 0
        while (topCount < topLimit && topCount < byCount.size) {
            top.add(byCount[topCount].copyOf())
            topCount++
        }

        val byRecent = ArrayList<TrackAgg>()
        for (agg in tracks.values) {
            if (agg.lastPlayed > 0L) byRecent.add(agg)
        }
        byRecent.sortWith(Comparator { a, b -> b.lastPlayed.compareTo(a.lastPlayed) })
        val recent = ArrayList<TrackAgg>()
        var recentCount = 0
        while (recentCount < recentLimit && recentCount < byRecent.size) {
            recent.add(byRecent[recentCount].copyOf())
            recentCount++
        }

        val byDay = ArrayList<DayAgg>()
        for (day in days.values) byDay.add(day)
        byDay.sortWith(Comparator { a, b -> b.date.compareTo(a.date) })
        val recentDays = ArrayList<DayAgg>()
        var dayCount = 0
        while (dayCount < 30 && dayCount < byDay.size) {
            val src = byDay[dayCount]
            val copy = DayAgg(src.date)
            copy.count = src.count
            copy.listenedMs = src.listenedMs
            recentDays.add(copy)
            dayCount++
        }

        return StatsSnapshot(
            totalCount = totalCount,
            totalMs = totalMs,
            trackCount = tracks.size,
            skippedCount = skippedCount,
            topTracks = top,
            recentTracks = recent,
            days = recentDays
        )
    }

    /** 清空（内存 + 磁盘事件日志改名为 .bak，可回滚） */
    @Synchronized
    fun reset(): String {
        ensureLoaded()
        val dir = dataDir
        tracks.clear()
        days.clear()
        dayTracks.clear()
        totalCount = 0
        totalMs = 0L
        skippedCount = 0
        replayedLines = 0
        if (dir == null) return "已清空（无数据目录）"

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        var moved = 0
        // 原始明细 + 按月聚合 + 曲目字典全部改名为 .bak（可回滚）
        val dirs = arrayOf(EVENTS_DIR, DAILY_DIR)
        for (name in dirs) {
            val target = File(dir, name)
            val files = target.listFiles()
            if (files == null) continue
            for (f in files) {
                if (!f.isFile) continue
                val bak = File(f.parentFile, f.name + "." + stamp + ".bak")
                if (f.renameTo(bak)) moved++
            }
        }
        val dict = File(dir, DICT_FILE)
        if (dict.isFile) {
            val bak = File(dir, DICT_FILE + "." + stamp + ".bak")
            if (dict.renameTo(bak)) moved++
        }
        return "已清空，" + moved + " 个数据文件已改名为 .bak"
    }

    // ------------------------------------------------------------------

    /** 全部曲目（导出用，按次数降序） */
    @Synchronized
    fun allTracks(): List<TrackAgg> {
        ensureLoaded()
        val list = ArrayList<TrackAgg>()
        for (agg in tracks.values) list.add(agg.copyOf())
        list.sortWith(Comparator { a, b ->
            if (a.count != b.count) b.count.compareTo(a.count) else b.listenedMs.compareTo(b.listenedMs)
        })
        return list
    }

    /** 最近 30 天（已按日期降序） */
    @Synchronized
    fun recentDays(limit: Int): List<DayAgg> {
        ensureLoaded()
        val sorted = ArrayList<DayAgg>()
        for (d in days.values) sorted.add(d)
        sorted.sortWith(Comparator { a, b -> b.date.compareTo(a.date) })
        val out = ArrayList<DayAgg>()
        var i = 0
        while (i < limit && i < sorted.size) {
            val src = sorted[i]
            val copy = DayAgg(src.date)
            copy.count = src.count
            copy.listenedMs = src.listenedMs
            out.add(copy)
            i++
        }
        return out
    }

    private fun matches(agg: TrackAgg, needle: String): Boolean {
        if (agg.title.lowercase().indexOf(needle) >= 0) return true
        if (agg.artist.lowercase().indexOf(needle) >= 0) return true
        if (agg.album.lowercase().indexOf(needle) >= 0) return true
        return false
    }

    private fun buildTrackPage(sorted: ArrayList<TrackAgg>, page: Int, pageSize: Int): TrackPage {
        val total = sorted.size
        val items = ArrayList<TrackAgg>()
        val from = page * pageSize
        var i = from
        while (i < total && items.size < pageSize) {
            items.add(sorted[i].copyOf())
            i++
        }
        return TrackPage(items, total, page, pageSize)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val dir = dataDir
        if (dir == null) return

        // 1) 曲目字典
        TrackDict.load(File(dir, DICT_FILE))
        // 2) 按月聚合（主数据源）
        loadDaily(File(dir, DAILY_DIR))
        // 3) 没有聚合数据但有旧明细 → 一次性迁移
        if (days.isEmpty() && replayedLines == 0) {
            val migrated = migrateEvents(dir)
            if (migrated > 0) {
                System.err.println("[listen-stats] 已迁移 $migrated 条旧事件到按月聚合")
            }
        }
        TrackDict.flush()
        // 4) 归档超期明细
        cleanupExpiredEvents(dir)
    }

    /** 读取 daily 目录下的聚合文件（每行 = 一次播放的压缩记录） */
    private fun loadDaily(dir: File) {
        if (!dir.isDirectory) return
        val files = dir.listFiles()
        if (files == null) return
        val list = ArrayList<File>()
        for (f in files) {
            if (f.isFile && f.name.endsWith(".jsonl")) list.add(f)
        }
        list.sortWith(Comparator { a, b -> a.name.compareTo(b.name) })
        for (file in list) {
            try {
                val reader = Files.newBufferedReader(file.toPath(), Charsets.UTF_8)
                try {
                    while (true) {
                        val line = reader.readLine()
                        if (line == null) break
                        applyDailyLine(line)
                    }
                } finally {
                    reader.close()
                }
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 读取聚合 " + file.name + " 失败: " + t.message)
            }
        }
    }

    /** 解析一行聚合记录并累加到内存 */
    private fun applyDailyLine(line: String) {
        if (line.length < 8 || line[0] != '{') return
        val date = JsonLite.string(line, "d")
        val key = JsonLite.string(line, "k")
        if (date.isEmpty() || key.isEmpty()) return
        val count = JsonLite.long(line, "c").toInt()
        val ms = JsonLite.long(line, "ms")
        val skips = JsonLite.long(line, "sk").toInt()
        val hour = JsonLite.long(line, "hr").toInt()
        val lastT = JsonLite.long(line, "lt")

        val entry = TrackDict.get(key)
        var agg = tracks[key]
        if (agg == null) {
            agg = TrackAgg(key)
            if (entry != null) {
                agg.id = entry.id
                agg.title = entry.title
                agg.artist = entry.artist
                agg.album = entry.album
                agg.albumArtist = entry.albumArtist
                agg.durationMs = entry.dur
            }
            tracks[key] = agg
        }
        agg.count += count
        agg.listenedMs += ms
        agg.skipCount += skips
        if (lastT > 0L) {
            if (agg.firstPlayed == 0L || lastT < agg.firstPlayed) agg.firstPlayed = lastT
            if (lastT > agg.lastPlayed) agg.lastPlayed = lastT
        }

        var dayAgg = days[date]
        if (dayAgg == null) {
            dayAgg = DayAgg(date)
            days[date] = dayAgg
        }
        dayAgg.count += count
        dayAgg.listenedMs += ms
        // 次数归开始小时；时长若带小时分布（hs）则按段累加，否则退化为全部计入开始小时
        if (hour >= 0 && hour < 24) dayAgg.hours[hour] += 1
        val hourSpan = JsonLite.string(line, "hs")
        if (hourSpan.isNotEmpty()) {
            var idx = 0
            while (idx < hourSpan.length) {
                val comma = hourSpan.indexOf(',', idx)
                val end = if (comma < 0) hourSpan.length else comma
                val token = hourSpan.substring(idx, end)
                val colon = token.indexOf(':')
                if (colon > 0) {
                    val h = token.substring(0, colon).toIntOrNull() ?: -1
                    val partMs = token.substring(colon + 1).toLongOrNull() ?: 0L
                    if (h >= 0 && h < 24 && partMs > 0L) dayAgg.hoursMs[h] += partMs
                }
                idx = end + 1
            }
        } else if (hour >= 0 && hour < 24) {
            dayAgg.hoursMs[hour] += ms
        }

        var perDay = dayTracks[date]
        if (perDay == null) {
            perDay = LinkedHashMap()
            dayTracks[date] = perDay
        }
        var dt = perDay[key]
        if (dt == null) {
            dt = DayTrackAgg(date, key)
            if (entry != null) {
                dt.title = entry.title
                dt.artist = entry.artist
            }
            perDay[key] = dt
        }
        dt.count += count
        dt.listenedMs += ms
        dt.skipCount += skips
        if (lastT > dt.lastPlayed) dt.lastPlayed = lastT

        totalCount += count
        totalMs += ms
        skippedCount += skips
        replayedLines++
    }

    /** 旧版 events 目录的明细 → daily 目录的按月聚合 迁移 */
    private fun migrateEvents(dir: File): Int {
        val events = File(dir, EVENTS_DIR)
        if (!events.isDirectory) return 0
        val files = events.listFiles()
        if (files == null) return 0
        val list = ArrayList<File>()
        for (f in files) {
            if (f.isFile && f.name.endsWith(".jsonl")) list.add(f)
        }
        list.sortWith(Comparator { a, b -> a.name.compareTo(b.name) })

        val byMonth = LinkedHashMap<String, ArrayList<String>>()
        var migrated = 0
        for (file in list) {
            try {
                val reader = Files.newBufferedReader(file.toPath(), Charsets.UTF_8)
                try {
                    while (true) {
                        val line = reader.readLine()
                        if (line == null) break
                        val event = PlayEvent.fromJsonLine(line)
                        if (event == null) continue
                        applyToMemory(event)
                        replayedLines++
                        migrated++
                        val key = TrackDict.keyOf(event.id, event.path)
                        // 迁移时也用「日期 + 小时」多行形式，与实时写入保持一致
                        val multi = buildDailyLines(event, key)
                        var li = 0
                        while (li < multi.size) {
                            val oneLine = multi[li]
                            val lineDay = JsonLite.string(oneLine, "d")
                            val oneMonth = if (lineDay.length >= 7) lineDay.substring(0, 7) else lineDay
                            var oneBucket = byMonth[oneMonth]
                            if (oneBucket == null) {
                                oneBucket = ArrayList()
                                byMonth[oneMonth] = oneBucket
                            }
                            oneBucket.add(oneLine)
                            li++
                        }
                    }
                } finally {
                    reader.close()
                }
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 迁移 " + file.name + " 失败: " + t.message)
            }
        }

        if (migrated > 0) {
            val dailyDir = File(dir, DAILY_DIR)
            dailyDir.mkdirs()
            for (month in byMonth.keys) {
                val lines = byMonth[month]
                if (lines == null || lines.isEmpty()) continue
                try {
                    Files.write(
                        File(dailyDir, month + ".jsonl").toPath(),
                        lines,
                        Charsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                } catch (t: Throwable) {
                    System.err.println("[listen-stats] 写聚合失败: " + t.message)
                }
            }
            TrackDict.flush()
        }
        return migrated
    }

    /** 超期原始明细移动到 expired/（不直接删除） */
    private fun cleanupExpiredEvents(dir: File) {
        StorageRule.load()
        val events = File(dir, EVENTS_DIR)
        if (!events.isDirectory) return
        val files = events.listFiles()
        if (files == null) return
        val cutoff = LocalDate.now().minusDays(StorageRule.rawEventKeepDays.toLong()).toString()
        var moved = 0
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(".jsonl")) continue
            val date = f.name.substring(0, f.name.length - 6)
            if (date >= cutoff) continue
            val expired = File(dir, EXPIRED_DIR)
            expired.mkdirs()
            val target = File(expired, f.name)
            if (target.exists()) target.delete()
            if (f.renameTo(target)) moved++
        }
        if (moved > 0) {
            System.err.println("[listen-stats] 已归档 $moved 个超期明细文件")
        }
    }

    /** 一行聚合记录（约 90 字节，不含标题等元信息） */
    /**
     * 把一遍播放拆成「日期 + 小时」的多行聚合记录
     *
     * 跨天时每天各一行：次数与跳过只在**开始那一行**计 1，其余行为 0；
     * `hr` 只在开始行写真实小时，其余行写 -1，避免加载时重复累加小时次数。
     */
    private fun buildDailyLines(event: PlayEvent, key: String): List<String> {
        val startMs = if (event.ms > 0L && event.ms <= event.t) event.t - event.ms else event.t
        val startDate = dayFormat.format(Date(startMs))
        val startHour = hourFormat.format(Date(startMs)).toIntOrNull() ?: 0
        val out = ArrayList<String>(3)
        if (event.ms <= 0L) {
            out.add(
                lineOf(startDate, key, startHour, 0L,
                    if (event.counted) 1 else 0, if (event.skipped) 1 else 0, event.t)
            )
            return out
        }
        var cursor = startMs
        var remain = event.ms
        var guard = 0
        while (remain > 0L && guard < 200) {
            val zdt = try {
                java.time.Instant.ofEpochMilli(cursor).atZone(java.time.ZoneId.systemDefault())
            } catch (t: Throwable) {
                break
            }
            val date = dayFormat.format(Date(cursor))
            val hour = zdt.hour
            val hourEnd = zdt.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .plusHours(1)
                .toInstant()
                .toEpochMilli()
            var part = hourEnd - cursor
            if (part > remain) part = remain
            if (part <= 0L) break
            val isStart = date == startDate && hour == startHour
            out.add(
                lineOf(
                    date, key, if (isStart) hour else -1, part,
                    if (isStart && event.counted) 1 else 0,
                    if (isStart && event.skipped) 1 else 0,
                    event.t
                )
            )
            cursor += part
            remain -= part
            guard++
        }
        if (out.isEmpty()) {
            out.add(
                lineOf(startDate, key, startHour, 0L,
                    if (event.counted) 1 else 0, if (event.skipped) 1 else 0, event.t)
            )
        }
        return out
    }

    private fun lineOf(
        date: String,
        key: String,
        hour: Int,
        partMs: Long,
        counted: Int,
        skipped: Int,
        lastPlayed: Long
    ): String {
        val sb = StringBuilder(160)
        sb.append('{')
        sb.append("\"d\":\"").append(date).append("\",")
        sb.append("\"k\":\"").append(key).append("\",")
        sb.append("\"c\":").append(counted).append(',')
        sb.append("\"ms\":").append(partMs).append(',')
        sb.append("\"sk\":").append(skipped).append(',')
        sb.append("\"hr\":").append(hour).append(',')
        val span = StringBuilder(24)
        if (hour >= 0 && hour < 24 && partMs > 0L) span.append(hour).append(':').append(partMs)
        sb.append("\"hs\":\"").append(span).append("\",")
        sb.append("\"lt\":").append(lastPlayed)
        sb.append('}')
        return sb.toString()
    }

    /**
     * 把一段收听时长按「日期 + 小时」边界切开
     *
     * 例：23:59:30 开始听 60 秒 → 19 日 23 点 30 秒 + 20 日 0 点 30 秒。
     */
    private fun splitByDayHour(
        startMs: Long,
        durationMs: Long,
        onPart: (dateKey: String, hour: Int, ms: Long) -> Unit
    ) {
        if (durationMs <= 0L) return
        var cursor = startMs
        var remain = durationMs
        var guard = 0
        while (remain > 0L && guard < 200) {
            val zdt = try {
                java.time.Instant.ofEpochMilli(cursor).atZone(java.time.ZoneId.systemDefault())
            } catch (t: Throwable) {
                onPart(dayFormat.format(Date(cursor)), 0, remain)
                return
            }
            val hourEnd = zdt.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .plusHours(1)
                .toInstant()
                .toEpochMilli()
            var part = hourEnd - cursor
            if (part > remain) part = remain
            if (part <= 0L) break
            onPart(dayFormat.format(Date(cursor)), zdt.hour, part)
            cursor += part
            remain -= part
            guard++
        }
        if (remain > 0L) {
            onPart(dayFormat.format(Date(cursor)), 0, remain)
        }
    }

    /**
     * 把一段收听时长按小时边界切开
     *
     * 例：20:55 开始听 8 分钟 → 20 点计入 5 分钟、21 点计入 3 分钟。
     */
    private fun splitByHour(startMs: Long, durationMs: Long, onPart: (hour: Int, ms: Long) -> Unit) {
        if (durationMs <= 0L) return
        var cursor = startMs
        var remain = durationMs
        var guard = 0
        while (remain > 0L && guard < 26) {
            val zdt = try {
                java.time.Instant.ofEpochMilli(cursor).atZone(java.time.ZoneId.systemDefault())
            } catch (t: Throwable) {
                onPart(0, remain)
                return
            }
            val hour = zdt.hour
            val hourEnd = zdt.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .plusHours(1)
                .toInstant()
                .toEpochMilli()
            if (hourEnd <= cursor) {
                onPart(hour, remain)
                return
            }
            val part = if (hourEnd - cursor < remain) hourEnd - cursor else remain
            onPart(hour, part)
            cursor += part
            remain -= part
            guard++
        }
        if (remain > 0L) onPart(23, remain)
    }

    private fun applyToMemory(event: PlayEvent) {
        val key = TrackDict.keyOf(event.id, event.path)
        if (key.isEmpty()) return
        TrackDict.ensure(key, event.id, event.title, event.artist, event.album, event.dur, event.albumArtist)

        var agg = tracks[key]
        if (agg == null) {
            agg = TrackAgg(key)
            tracks[key] = agg
        }
        if (event.id.isNotBlank()) agg.id = event.id
        if (event.title.isNotBlank()) agg.title = event.title
        if (event.artist.isNotBlank()) agg.artist = event.artist
        if (event.album.isNotBlank()) agg.album = event.album
        if (event.albumArtist.isNotBlank()) agg.albumArtist = event.albumArtist
        if (event.dur > 0L) agg.durationMs = event.dur
        if (event.counted) agg.count++
        agg.listenedMs += event.ms
        if (event.skipped) agg.skipCount++
        if (agg.firstPlayed == 0L || event.t < agg.firstPlayed) agg.firstPlayed = event.t
        if (event.t > agg.lastPlayed) agg.lastPlayed = event.t

        val startMs = if (event.ms > 0L && event.ms <= event.t) event.t - event.ms else event.t
        val startDate = dayFormat.format(Date(startMs))
        val startHour = hourFormat.format(Date(startMs)).toIntOrNull() ?: 0

        // 次数和跳过归「开始日 / 开始小时」
        var startDay = days[startDate]
        if (startDay == null) {
            startDay = DayAgg(startDate)
            days[startDate] = startDay
        }
        if (event.counted) startDay.count++
        if (startHour >= 0 && startHour < 24) startDay.hours[startHour] += 1

        // 时长按「天 + 小时」切分：跨午夜时两天各得自己那一段
        splitByDayHour(startMs, event.ms) { dateKey, partHour, partMs ->
            var d = days[dateKey]
            if (d == null) {
                d = DayAgg(dateKey)
                days[dateKey] = d
            }
            d.listenedMs += partMs
            if (partHour >= 0 && partHour < 24) d.hoursMs[partHour] += partMs
        }

        var perDay = dayTracks[startDate]
        if (perDay == null) {
            perDay = LinkedHashMap()
            dayTracks[startDate] = perDay
        }
        var dt = perDay[key]
        if (dt == null) {
            dt = DayTrackAgg(startDate, key)
            dt.title = event.title
            dt.artist = event.artist
            perDay[key] = dt
        }
        if (event.counted) dt.count++
        dt.listenedMs += event.ms
        if (event.skipped) dt.skipCount++
        if (event.t > dt.lastPlayed) dt.lastPlayed = event.t

        if (event.counted) totalCount++
        totalMs += event.ms
        if (event.skipped) skippedCount++
        dataVersion++
    }
}

internal fun TrackAgg.copyOf(): TrackAgg {
    val c = TrackAgg(key)
    c.id = id
    c.path = path
    c.title = title
    c.artist = artist
    c.album = album
    c.count = count
    c.listenedMs = listenedMs
    c.skipCount = skipCount
    c.firstPlayed = firstPlayed
    c.lastPlayed = lastPlayed
    c.durationMs = durationMs
    return c
}
