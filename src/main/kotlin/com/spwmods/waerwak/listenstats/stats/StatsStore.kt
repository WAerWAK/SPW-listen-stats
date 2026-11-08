package com.spwmods.waerwak.listenstats.stats

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 统计仓库 + 内存聚合
 *
 * 主数据源是 `daily/YYYY-MM.jsonl`（按月聚合、永久保留，每行约 90 字节）；
 * `events/YYYY-MM-DD.jsonl` 只是明细（默认保留 90 天，供「最近播放记录」查询）。
 * 启动时重放 daily 构建内存索引；每次播放结束同时追加明细与聚合行。
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

    private var dataDir: File? = null
    private var loaded = false
    private var replayedLines = 0

    /** 写线程（插件停用后会释放，重新启用时重建） */
    @Volatile
    private var io = newIo()

    private fun newIo() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "listen-stats-writer").apply { isDaemon = true }
    }

    /** 插件停用时调用：先排空写队列再释放线程，避免重载插件累积线程 */
    @Synchronized
    internal fun shutdown() {
        drainIo()
        try {
            io.shutdown()
        } catch (t: Throwable) {
            // 忽略
        }
        io = newIo()
    }

    /**
     * 等写队列排空
     *
     * [record] 是异步落盘的；清空 / 恢复 / 备份前必须先排空，否则排队中的旧行会在
     * 改名之后被追加回来（重启后旧数据"复活"），或者被压进备份包里。
     * 注意：只能在持有本对象锁时调用，且写任务本身不能回头拿本对象的锁（否则死锁）。
     */
    private fun drainIo() {
        try {
            val latch = CountDownLatch(1)
            io.execute { latch.countDown() }
            latch.await(3, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            // 超时/中断：继续执行，最坏情况是极少量行落错位置
        }
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
        val spans = normalizedSpans(event)
        val startMs = if (spans.size >= 2) spans[0] else event.t
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

    /** 上次加载数据时的错误（null = 正常）；UI 用它显示错误条而不是把失败装成"没数据" */
    @Volatile
    var lastLoadError: String? = null
        private set

    @Synchronized
    fun loadError(): String? = lastLoadError

    /** 备份目录内的元数据文件名 */
    private const val MANIFEST_FILE = "manifest.json"

    /** 备份格式版本：2 起带 includes / sha256 */
    private const val BACKUP_FORMAT = 2

    /** 备份包安全上限（解压前校验，防止恶意/异常包撑爆磁盘） */
    private const val MAX_BACKUP_ENTRIES = 5_000
    private const val MAX_BACKUP_BYTES = 256L * 1024L * 1024L

    /** 插件版本（写进 manifest，便于排查备份来源）；由 ListenStatsPlugin.start() 注入 */
    @Volatile
    var pluginVersionHint: String = ""

    private class BackupEntry(val name: String, val file: File)

    /** 备份全部数据到 zip（**纯只读操作，不改动任何源文件，也不触发加载/迁移**） */
    @Synchronized
    fun backupTo(target: File): String {
        val dir = dataDir ?: return "没有数据目录"
        if (!dir.isDirectory) return "数据目录不存在"
        // 等写队列排空，避免压到写了一半的行
        drainIo()
        // 安全保护：不允许把备份写到数据文件本身上
        val targetPath = try {
            target.canonicalPath
        } catch (t: Throwable) {
            target.absolutePath
        }
        val dictFile = File(dir, DICT_FILE)
        // 两边都用 canonicalPath：absolutePath 与 canonicalPath 在短文件名/符号链接下可能不同
        val dictPath = try {
            dictFile.canonicalPath
        } catch (t: Throwable) {
            dictFile.absolutePath
        }
        if (targetPath == dictPath) return "备份失败：不能覆盖数据文件"
        if (target.exists() && target.isDirectory) return "备份失败：目标是目录"

        // 1) 收集条目（决定 includes 与校验和；顺序固定，保证同一份数据得到同一个 sha256）
        val entries = ArrayList<BackupEntry>(16)
        if (dictFile.isFile) entries.add(BackupEntry(DICT_FILE, dictFile))
        collectBackupDir(entries, File(dir, DAILY_DIR), DAILY_DIR)
        collectBackupDir(entries, File(dir, EVENTS_DIR), EVENTS_DIR)
        for (extra in arrayOf("config.json", "ui-state.json", "missing.txt")) {
            val f = File(dir, extra)
            val fPath = try {
                f.canonicalPath
            } catch (t: Throwable) {
                f.absolutePath
            }
            if (f.isFile && fPath != targetPath) entries.add(BackupEntry(extra, f))
        }
        if (entries.isEmpty()) return "没有可备份的数据"
        entries.sortWith(Comparator { a, b -> a.name.compareTo(b.name) })
        var digest = ""
        try {
            digest = backupDigest(entries)
        } catch (t: Throwable) {
            log("备份校验和计算失败：" + t.message)
            return "备份失败：读取数据文件出错（" + t.message + "）"
        }

        // 2) 写包
        var zos: java.util.zip.ZipOutputStream? = null
        var written = 0
        var totalBytes = 0L
        try {
            zos = java.util.zip.ZipOutputStream(target.outputStream().buffered())
            // 先写 manifest：恢复时可以先展示备份内容摘要再确认
            val manifest = buildManifest(dir, entries, digest)
            zos.putNextEntry(java.util.zip.ZipEntry(MANIFEST_FILE))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            written++
            var i = 0
            while (i < entries.size) {
                writeEntry(zos, entries[i].file, entries[i].name)
                written++
                totalBytes += entries[i].file.length()
                i++
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

    /**
     * 生成备份元数据（只读统计，不触发加载）
     *
     * 恢复前用它告诉用户「这个备份里有什么」，避免盲目覆盖现有数据。
     */
    private fun buildManifest(dir: File, entries: ArrayList<BackupEntry>, digest: String): String {
        var trackLines = 0
        try {
            val dict = File(dir, DICT_FILE)
            if (dict.isFile) trackLines = dict.readLines().size
        } catch (t: Throwable) {
            trackLines = 0
        }
        var dayLines = 0
        var eventLines = 0
        for (name in arrayOf(DAILY_DIR, EVENTS_DIR)) {
            val d = File(dir, name)
            val files = d.listFiles()
            if (files == null) continue
            for (f in files) {
                if (!f.isFile || !f.name.endsWith(".jsonl")) continue
                val n = try {
                    f.readLines().size
                } catch (t: Throwable) {
                    0
                }
                if (name == DAILY_DIR) dayLines += n else eventLines += n
            }
        }
        // 事件文件名就是日期，取最早的 / 最晚的
        var firstDay = ""
        var lastDay = ""
        val eventFiles = File(dir, EVENTS_DIR).listFiles()
        if (eventFiles != null) {
            for (f in eventFiles) {
                if (!f.isFile || !f.name.endsWith(".jsonl")) continue
                val day = f.name.removeSuffix(".jsonl")
                if (firstDay.isEmpty() || day < firstDay) firstDay = day
                if (day > lastDay) lastDay = day
            }
        }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val zone = java.time.ZoneId.systemDefault().id
        val version = if (pluginVersionHint.isEmpty()) "?" else pluginVersionHint
        val includes = StringBuilder(128)
        var i = 0
        while (i < entries.size) {
            if (i > 0) includes.append(',')
            includes.append('"').append(JsonLite.escape(entries[i].name)).append('"')
            i++
        }
        return "{\"format\":\"spw-listen-stats-backup\",\"v\":" + BACKUP_FORMAT +
            ",\"exportedAt\":\"" + now + "\"" +
            ",\"pluginVersion\":\"" + JsonLite.escape(version) + "\"" +
            ",\"timeZone\":\"" + JsonLite.escape(zone) + "\"" +
            ",\"tracks\":" + trackLines +
            ",\"dailyRows\":" + dayLines + ",\"events\":" + eventLines +
            ",\"firstDay\":\"" + firstDay + "\",\"lastDay\":\"" + lastDay + "\"" +
            ",\"includes\":[" + includes + "]" +
            ",\"sha256\":\"" + digest + "\"}"
    }

    /** 收集目录下的 .jsonl 条目（只收集一层，与备份写入保持一致） */
    private fun collectBackupDir(out: ArrayList<BackupEntry>, dir: File, prefix: String) {
        val files = dir.listFiles() ?: return
        var i = 0
        while (i < files.size) {
            val f = files[i]
            i++
            if (!f.isFile) continue
            if (!f.name.endsWith(".jsonl")) continue
            out.add(BackupEntry(prefix + "/" + f.name, f))
        }
    }

    /** 备份校验和：按条目名排序后，对「名字 + 大小 + 内容哈希」再哈希 */
    private fun backupDigest(entries: ArrayList<BackupEntry>): String {
        val master = java.security.MessageDigest.getInstance("SHA-256")
        var i = 0
        while (i < entries.size) {
            val entry = entries[i]
            val bytes = entry.file.readBytes()
            val per = java.security.MessageDigest.getInstance("SHA-256")
            per.update(bytes)
            master.update(
                (entry.name + "\u0000" + bytes.size + "\u0000" + hexOf(per.digest()) + "\n")
                    .toByteArray(Charsets.UTF_8)
            )
            i++
        }
        return hexOf(master.digest())
    }

    private fun hexOf(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        var i = 0
        while (i < bytes.size) {
            val v = bytes[i].toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
            i++
        }
        return sb.toString()
    }

    private fun zipNames(zip: java.util.zip.ZipFile): ArrayList<String> {
        val names = ArrayList<String>(32)
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.isDirectory) continue
            names.add(entry.name)
        }
        names.sortWith(Comparator { a, b -> a.compareTo(b) })
        return names
    }

    /** 恢复白名单：只接受统计相关文件，其他一律拒绝（防止覆盖 config/live-session 之外的东西） */
    private fun allowedBackupEntry(name: String): Boolean {
        if (name == MANIFEST_FILE) return true
        if (name == DICT_FILE) return true
        if (name == "config.json" || name == "ui-state.json" || name == "missing.txt") return true
        if (!name.endsWith(".jsonl")) return false
        if (name.startsWith(DAILY_DIR + "/") || name.startsWith(EVENTS_DIR + "/")) {
            val rest = name.substring(name.indexOf('/') + 1)
            return rest.isNotEmpty() && rest.indexOf('/') < 0
        }
        return false
    }

    /** 读取备份的元数据摘要（不修改任何数据）；null 表示旧版备份没有 manifest */
    @Synchronized
    fun inspectBackup(source: File): String? {
        var zis: java.util.zip.ZipInputStream? = null
        try {
            zis = java.util.zip.ZipInputStream(source.inputStream().buffered())
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == MANIFEST_FILE) {
                    val bytes = zis.readBytes()
                    return String(bytes, Charsets.UTF_8)
                }
                zis.closeEntry()
            }
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                zis?.close()
            } catch (t: Throwable) {
                // 忽略
            }
        }
        return null
    }

    /** 追加一行到 listen-stats.log（排查用） */
    private fun log(message: String) {
        try {
            val dir = dataDir ?: return
            val file = File(dir, "listen-stats.log")
            val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()) +
                "  [backup] " + message + "\n"
            LogFiles.append(file, line)
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

    /**
     * 校验备份包（在改动任何数据之前）
     *
     * - 条目白名单 + 数量/解压体积上限
     * - v2 起校验 sha256（防止文件被改坏/篡改）
     * @return 错误文案；null 表示可以恢复
     */
    private fun validateBackup(source: File): String? {
        var zip: java.util.zip.ZipFile? = null
        try {
            zip = java.util.zip.ZipFile(source)
            val names = zipNames(zip)
            if (names.isEmpty()) return "恢复失败：压缩包为空，未恢复任何数据"
            if (names.size > MAX_BACKUP_ENTRIES) return "恢复失败：备份条目过多（" + names.size + " 个）"
            var i = 0
            while (i < names.size) {
                val name = names[i]
                i++
                if (name == MANIFEST_FILE) continue
                if (!allowedBackupEntry(name)) return "恢复失败：备份包含不允许的条目：" + name
            }
            val manifestEntry = zip.getEntry(MANIFEST_FILE)
            var expectSha = ""
            if (manifestEntry != null) {
                val manifestText = String(zip.getInputStream(manifestEntry).readBytes(), Charsets.UTF_8)
                val format = JsonLite.string(manifestText, "format")
                if (format.isNotEmpty() && format != "spw-listen-stats-backup") {
                    return "恢复失败：不认识的备份格式：" + format
                }
                val version = JsonLite.long(manifestText, "v")
                if (version > BACKUP_FORMAT) {
                    return "恢复失败：备份来自更新版本的插件（v" + version + "），请先升级插件再恢复"
                }
                expectSha = JsonLite.string(manifestText, "sha256")
            }
            // 逐个条目累计体积/校验和（与备份时相同的排序与算法）
            val master = java.security.MessageDigest.getInstance("SHA-256")
            var total = 0L
            i = 0
            while (i < names.size) {
                val name = names[i]
                i++
                if (name == MANIFEST_FILE) continue
                val entry = zip.getEntry(name) ?: continue
                val bytes = zip.getInputStream(entry).readBytes()
                total += bytes.size
                if (total > MAX_BACKUP_BYTES) {
                    return "恢复失败：备份解压后超过 " + (MAX_BACKUP_BYTES / 1024L / 1024L) + " MB"
                }
                val per = java.security.MessageDigest.getInstance("SHA-256")
                per.update(bytes)
                master.update(
                    (name + "\u0000" + bytes.size + "\u0000" + hexOf(per.digest()) + "\n")
                        .toByteArray(Charsets.UTF_8)
                )
            }
            if (expectSha.isNotEmpty() && hexOf(master.digest()) != expectSha) {
                return "恢复失败：备份校验和不匹配（文件可能被修改或损坏）"
            }
            return null
        } catch (t: Throwable) {
            return "恢复失败：备份包损坏：" + (t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                zip?.close()
            } catch (t: Throwable) {
                // 忽略
            }
        }
    }

    /** 从 zip 恢复（现有数据先改名保留为 backup-时间戳） */
    @Synchronized
    fun restoreFrom(source: File): String {
        ensureLoaded()
        val dir = dataDir ?: return "没有数据目录"
        // 排队中的写入先落地，否则解压后又被追加旧行
        drainIo()
        // 0) 先校验：不合法的包在改动任何数据之前就拒绝
        val invalid = validateBackup(source)
        if (invalid != null) return invalid
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        var restored = 0
        // 1) 先备份现有数据（改名，不删除）；记录改名清单，失败时回滚
        val renamed = ArrayList<Pair<File, File>>(4)
        val dict = File(dir, DICT_FILE)
        if (dict.isFile) {
            val bak = File(dir, DICT_FILE + "." + stamp + ".bak")
            if (dict.renameTo(bak)) renamed.add(Pair(bak, dict))
        }
        for (name in arrayOf(DAILY_DIR, EVENTS_DIR)) {
            val d = File(dir, name)
            if (d.isDirectory) {
                val bak = File(dir, name + "." + stamp + ".bak")
                if (d.renameTo(bak)) renamed.add(Pair(bak, d))
            }
        }
        // 2) 解压（ZipFile 随机读：条目顺序不影响结果；记录写出的文件，失败时删掉并回滚 .bak）
        val written = ArrayList<File>(32)
        var writeFailed = false
        var zip: java.util.zip.ZipFile? = null
        try {
            zip = java.util.zip.ZipFile(source)
            val names = zipNames(zip)
            var i = 0
            while (i < names.size) {
                val name = names[i]
                i++
                if (name == MANIFEST_FILE) continue
                val entry = zip.getEntry(name) ?: continue
                val target = File(dir, name)
                // 防 zip slip：entry 名可能是 "../xxx"，解析后必须仍落在数据目录内
                val basePath = try {
                    dir.canonicalPath + File.separator
                } catch (t: Throwable) {
                    dir.absolutePath + File.separator
                }
                val targetPath = try {
                    target.canonicalPath
                } catch (t: Throwable) {
                    ""
                }
                if (targetPath.isEmpty() || !targetPath.startsWith(basePath)) {
                    System.err.println("[listen-stats] 跳过非法备份条目: " + name)
                    continue
                }
                val parent = target.parentFile
                if (parent != null) parent.mkdirs()
                val input = zip.getInputStream(entry)
                val out = target.outputStream().buffered()
                var failed = false
                try {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                    }
                } catch (t: Throwable) {
                    failed = true
                }
                out.close()
                input.close()
                if (failed) {
                    writeFailed = true
                    break
                }
                written.add(target)
                restored++
            }
        } catch (t: Throwable) {
            try {
                zip?.close()
            } catch (e: Throwable) {
                // 忽略
            }
            rollbackRestore(written, renamed)
            return "恢复失败：" + t.message
        }
        try {
            zip?.close()
        } catch (e: Throwable) {
            // 忽略
        }
        if (writeFailed || restored == 0) {
            rollbackRestore(written, renamed)
            return if (writeFailed) "恢复失败：写入中断，已还原原数据" else "压缩包为空，未恢复任何数据"
        }
        // 3) 重置内存，下次访问重新加载
        TrackDict.clear()
        tracks.clear()
        days.clear()
        dayTracks.clear()
        totalCount = 0
        totalMs = 0L
        replayedLines = 0
        loaded = false
        // 4) 派生状态必须重建：缺失检测与内存封面都是按"原来的曲库"算的
        try {
            MissingStore.clear()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 恢复后清空缺失记录失败: " + t.message)
        }
        try {
            com.spwmods.waerwak.listenstats.ui.CoverCache.clearMemory()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 恢复后清空封面缓存失败: " + t.message)
        }
        dataVersion++
        return "已恢复 " + restored + " 个文件（原数据已改名为 .bak 保留；缺失检测已重置，可重新检测）"
    }

    /**
     * 合并导入：把另一份备份里的播放**明细**并进来（按会话 id 去重）
     *
     * 与 restoreFrom 的区别：不替换现有数据，同一份备份导入两次也不会重复计数。
     * 只认 events/ 里的明细（身份 = eid，旧数据用 id+t+ms）；备份里没有明细时拒绝，
     * 因为按天聚合行没有身份，合并必然重复计数。
     */
    @Synchronized
    fun importMerge(source: File): String {
        ensureLoaded()
        val dir = dataDir ?: return "没有数据目录"
        drainIo()
        val invalid = validateBackup(source)
        if (invalid != null) return invalid
        val imported = ArrayList<PlayEvent>(256)
        val dictLines = ArrayList<String>(64)
        var duplicates = 0
        var badLines = 0
        var zip: java.util.zip.ZipFile? = null
        try {
            zip = java.util.zip.ZipFile(source)
            val names = zipNames(zip)
            val existing = existingEventIdentities(dir)
            var i = 0
            while (i < names.size) {
                val name = names[i]
                i++
                if (name == DICT_FILE) {
                    val entry = zip.getEntry(name) ?: continue
                    val text = String(zip.getInputStream(entry).readBytes(), Charsets.UTF_8)
                    val lines = text.split('\n')
                    var k = 0
                    while (k < lines.size) {
                        if (lines[k].isNotBlank()) dictLines.add(lines[k])
                        k++
                    }
                    continue
                }
                if (!name.startsWith(EVENTS_DIR + "/") || !name.endsWith(".jsonl")) continue
                val entry = zip.getEntry(name) ?: continue
                val text = String(zip.getInputStream(entry).readBytes(), Charsets.UTF_8)
                val lines = text.split('\n')
                var k = 0
                while (k < lines.size) {
                    val line = lines[k]
                    k++
                    if (line.isBlank()) continue
                    val event = PlayEvent.fromJsonLine(line)
                    if (event == null) {
                        badLines++
                        continue
                    }
                    val identity = eventIdentity(event)
                    if (existing.contains(identity)) {
                        duplicates++
                        continue
                    }
                    existing.add(identity)
                    imported.add(event)
                }
            }
        } catch (t: Throwable) {
            return "合并失败：" + (t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                zip?.close()
            } catch (t: Throwable) {
                // 忽略
            }
        }
        if (imported.isEmpty()) {
            return if (duplicates > 0) {
                "没有新记录：备里的 " + duplicates + " 条都已存在"
            } else {
                "备份里没有可合并的播放明细（只有按天聚合数据，请用「恢复数据」）"
            }
        }
        // 1) 先补曲目字典：标题/艺术家/封面对得上
        var ci = 0
        while (ci < dictLines.size) {
            val line = dictLines[ci]
            ci++
            val key = JsonLite.string(line, "k")
            if (key.isEmpty()) continue
            if (TrackDict.get(key) != null) continue
            TrackDict.ensure(
                key,
                JsonLite.string(line, "id"),
                JsonLite.string(line, "ti"),
                JsonLite.string(line, "ar"),
                JsonLite.string(line, "al"),
                JsonLite.long(line, "dur"),
                JsonLite.string(line, "aa")
            )
        }
        TrackDict.flush()
        // 2) 逐条并入内存 + 追加明细与聚合行
        var addedMs = 0L
        var addedCount = 0
        var ev = 0
        while (ev < imported.size) {
            val event = imported[ev]
            ev++
            applyToMemory(event)
            addedMs += event.ms
            if (event.counted) addedCount++
            val spans = normalizedSpans(event)
            val startMs = if (spans.size >= 2) spans[0] else event.t
            val day = dayFormat.format(Date(startMs))
            val line = event.toJsonLine()
            val dailyLines = buildDailyLines(event, TrackDict.keyOf(event.id, event.path))
            io.execute {
                try {
                    val eventFile = File(File(dir, EVENTS_DIR), day + ".jsonl")
                    val eventParent = eventFile.parentFile
                    if (eventParent != null) eventParent.mkdirs()
                    Files.write(
                        eventFile.toPath(),
                        listOf(line),
                        Charsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    )
                    var li = 0
                    while (li < dailyLines.size) {
                        val dailyLine = dailyLines[li]
                        li++
                        val lineDay = JsonLite.string(dailyLine, "d")
                        val month = if (lineDay.length >= 7) lineDay.substring(0, 7) else lineDay
                        val dailyFile = File(File(dir, DAILY_DIR), month + ".jsonl")
                        val dailyParent = dailyFile.parentFile
                        if (dailyParent != null) dailyParent.mkdirs()
                        Files.write(
                            dailyFile.toPath(),
                            listOf(dailyLine),
                            Charsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND
                        )
                    }
                } catch (t: Throwable) {
                    System.err.println("[listen-stats] 合并写入失败: " + t.message)
                }
            }
        }
        drainIo()
        dataVersion++
        return "已合并 " + imported.size + " 条新记录（跳过 " + duplicates + " 条重复、" + badLines +
            " 条无法解析），新增时长 " + durationText(addedMs)
    }

    private fun durationText(ms: Long): String {
        if (ms <= 0L) return "0 分钟"
        val minutes = (ms + 30_000L) / 60_000L
        val hours = minutes / 60L
        val rest = minutes % 60L
        if (hours > 0L && rest > 0L) return hours.toString() + " 小时 " + rest + " 分"
        if (hours > 0L) return hours.toString() + " 小时"
        return rest.toString() + " 分钟"
    }

    /** 现有明细的身份集合（eid；旧数据退化为 id+t+ms） */
    private fun existingEventIdentities(dir: File): HashSet<String> {
        val set = HashSet<String>(256)
        val files = File(dir, EVENTS_DIR).listFiles()
        if (files == null) return set
        var i = 0
        while (i < files.size) {
            val file = files[i]
            i++
            if (!file.isFile || !file.name.endsWith(".jsonl")) continue
            try {
                val reader = Files.newBufferedReader(file.toPath(), Charsets.UTF_8)
                try {
                    while (true) {
                        val line = reader.readLine()
                        if (line == null) break
                        if (line.isBlank()) continue
                        val event = PlayEvent.fromJsonLine(line)
                        if (event != null) set.add(eventIdentity(event))
                    }
                } finally {
                    reader.close()
                }
            } catch (t: Throwable) {
                // 读不了的单条文件跳过，不影响其余合并
            }
        }
        return set
    }

    private fun eventIdentity(event: PlayEvent): String {
        if (event.eid.isNotEmpty()) return "e:" + event.eid
        return "s:" + event.id + "|" + event.t + "|" + event.ms
    }

    /**
     * 恢复失败时回滚：删掉本次解压出的文件，把 .bak 改回原名
     *
     * 否则用户选错文件 / zip 损坏后，原数据只剩一堆 .bak，面板里数据"消失"，
     * 只能手动去数据目录改名才能找回。
     */
    private fun rollbackRestore(written: ArrayList<File>, renamed: ArrayList<Pair<File, File>>) {
        var i = 0
        while (i < written.size) {
            try {
                written[i].delete()
            } catch (t: Throwable) {
                // 忽略
            }
            i++
        }
        i = 0
        while (i < renamed.size) {
            val p = renamed[i]
            try {
                if (!p.second.exists()) p.first.renameTo(p.second)
            } catch (t: Throwable) {
                // 忽略
            }
            i++
        }
        // 内存已清空 → 下次访问时重新加载回原数据
        TrackDict.clear()
        tracks.clear()
        days.clear()
        dayTracks.clear()
        totalCount = 0
        totalMs = 0L
        replayedLines = 0
        loaded = false
        dataVersion++
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

    // ---------------------------------------------------------------- 查询缓存（C3）
    //
    // 面板每 1.5 秒重组一次、翻页/排序/切范围都会重新查；命中条件 = 数据版本 + 缺失状态未变。
    // 排序结果与范围聚合都缓存在这里，把「每次重组都全量排序」变成「每次数据变化只算一次」。

    private var cacheSignature = ""
    private val rangeCache = HashMap<String, LinkedHashMap<String, TrackAgg>>()
    private val sortedCache = HashMap<String, ArrayList<TrackAgg>>()
    private var cachedSnapshot: StatsSnapshot? = null
    private var cachedSnapshotTop = -1
    private var cachedSnapshotRecent = -1

    /** 缓存身份：数据版本 / 是否隐藏缺失 / 缺失条数（检测结果变化不会动 dataVersion） */
    private fun cacheSignature(): String {
        return dataVersion.toString() + "|" + MissingStore.hidden + "|" + MissingStore.count()
    }

    private fun ensureCacheFresh() {
        val signature = cacheSignature()
        if (signature == cacheSignature) return
        cacheSignature = signature
        rangeCache.clear()
        sortedCache.clear()
        cachedSnapshot = null
        cachedSnapshotTop = -1
        cachedSnapshotRecent = -1
    }

    /**
     * 启动时后台预热：把首次打开面板要付的加载/排序成本提前付掉
     *
     * 由 ListenStatsPlugin 在守护线程里调用，绝不放在宿主界面线程上。
     */
    @Synchronized
    fun prefetch() {
        ensureLoaded()
        try {
            snapshot(10, 10)
            sortedTracks(null, 0, false)
            sortedTracks(null, 1, false)
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 预热缓存失败: " + t.message)
        }
    }

    /** 列表里是否跳过这首：开了「隐藏缺失曲目」且这首确实缺失 */
    private fun hideInList(key: String): Boolean {
        return MissingStore.hidden && MissingStore.isMissingKey(key)
    }

    /** 按时间范围聚合后的曲目（sinceDate 为 null = 总榜；否则只统计 date >= sinceDate） */
    private fun rangeAggregatedTracks(sinceDate: String?): LinkedHashMap<String, TrackAgg> {
        ensureCacheFresh()
        val cacheKey = if (sinceDate == null) "" else sinceDate
        val hit = rangeCache[cacheKey]
        if (hit != null) return hit
        val map = LinkedHashMap<String, TrackAgg>()
        if (sinceDate == null) {
            for (agg in tracks.values) map[agg.key] = agg
            rangeCache[cacheKey] = map
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
        rangeCache[cacheKey] = map
        return map
    }

    /** 排序后的曲目列表（按范围 + 排序键缓存；关键词过滤交给调用方，避免为每个搜索词建缓存） */
    private fun sortedTracks(sinceDate: String?, sortKey: Int, asc: Boolean): ArrayList<TrackAgg> {
        ensureCacheFresh()
        val key = (if (sinceDate == null) "" else sinceDate) + "|" + sortKey + "|" + asc
        val hit = sortedCache[key]
        if (hit != null) return hit
        val source = rangeAggregatedTracks(sinceDate)
        val list = ArrayList<TrackAgg>(source.size)
        for (agg in source.values) {
            if (agg.count <= 0 && agg.listenedMs <= 0L) continue
            if (hideInList(agg.key)) continue
            list.add(agg)
        }
        list.sortWith(Comparator { a, b ->
            val primary = when (sortKey) {
                1 -> a.count.compareTo(b.count)
                2 -> a.skipCount.compareTo(b.skipCount)
                3 -> a.lastPlayed.compareTo(b.lastPlayed)
                4 -> titleCollator.compare(a.title, b.title)
                else -> a.listenedMs.compareTo(b.listenedMs)
            }
            if (primary != 0) (if (asc) primary else -primary) else b.count.compareTo(a.count)
        })
        sortedCache[key] = list
        return list
    }

    /** 中文标题排序器（按拼音）；与其它排序一起在同步块内使用 */
    private val titleCollator: java.text.Collator = java.text.Collator.getInstance(Locale.CHINA)

    /**
     * 歌曲分页（支持时间范围：sinceDate 为 null 表示总榜）
     *
     * @param sortKey 0=时长 1=次数 2=跳过 3=上次播放 4=曲目名
     * @param asc true = 升序，false = 降序
     */
    @Synchronized
    fun trackPageRange(
        page: Int,
        pageSize: Int,
        sortKey: Int,
        asc: Boolean,
        query: String,
        sinceDate: String?
    ): TrackPage {
        ensureLoaded()
        val needle = query.trim().lowercase()
        val base = sortedTracks(sinceDate, sortKey, asc)
        if (needle.isEmpty()) return buildTrackPage(base, page, pageSize)
        val filtered = ArrayList<TrackAgg>(base.size)
        var index = 0
        while (index < base.size) {
            val agg = base[index]
            index++
            if (matches(agg, needle)) filtered.add(agg)
        }
        return buildTrackPage(filtered, page, pageSize)
    }

    /**
     * 专辑 / 艺术家分页（支持时间范围 + 关键词过滤）
     *
     * @param sortKey 0=时长 1=次数 2=跳过 3=上次播放 4=名称
     */
    @Synchronized
    fun aggregatePageRange(
        page: Int,
        pageSize: Int,
        byAlbum: Boolean,
        sinceDate: String?,
        query: String,
        sortKey: Int = 0,
        asc: Boolean = false
    ): AggregatePage {
        ensureLoaded()
        ensureCacheFresh()
        val needle = query.trim().lowercase()
        val source = rangeAggregatedTracks(sinceDate)
        val map = LinkedHashMap<String, AggregateRow>()
        for (agg in source.values) {
            if (agg.count <= 0 && agg.listenedMs <= 0L) continue
            if (hideInList(agg.key)) continue
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
            val primary = when (sortKey) {
                1 -> a.count.compareTo(b.count)
                2 -> a.skipCount.compareTo(b.skipCount)
                3 -> a.lastPlayed.compareTo(b.lastPlayed)
                4 -> titleCollator.compare(a.name, b.name)
                else -> a.listenedMs.compareTo(b.listenedMs)
            }
            if (primary != 0) (if (asc) primary else -primary) else b.count.compareTo(a.count)
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
            copy.skipCount = src.skipCount
            copy.trackCount = src.trackCount
            copy.missingCount = src.missingCount
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
     * @param sinceDate 与榜单一致的时间范围（null = 总榜），否则详情里的次数/时长会和列表行对不上
     */
    @Synchronized
    fun tracksOfGroup(
        byAlbum: Boolean,
        name: String,
        owner: String,
        sinceDate: String? = null
    ): List<TrackAgg> {
        ensureLoaded()
        val out = ArrayList<TrackAgg>()
        val target = name.trim()
        if (target.isEmpty()) return out
        val source = rangeAggregatedTracks(sinceDate)
        for (agg in source.values) {
            if (agg.count <= 0 && agg.listenedMs <= 0L) continue
            if (hideInList(agg.key)) continue
            if (byAlbum) {
                val album = agg.album.trim()
                if (album.isEmpty()) continue
                val displayName = GroupKeys.albumDisplayName(album)
                if (!displayName.equals(target, ignoreCase = true)) continue
                if (owner.isNotEmpty()) {
                    val displayOwner = GroupKeys.albumDisplayArtist(agg.albumArtist, agg.artist)
                    if (!displayOwner.equals(owner.trim(), ignoreCase = true)) continue
                }
                out.add(agg.copyOf())
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
                if (found) out.add(agg.copyOf())
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
        row.skipCount += agg.skipCount
        row.trackCount++
        if (MissingStore.isMissingKey(agg.key)) row.missingCount++
        if (agg.lastPlayed > row.lastPlayed) row.lastPlayed = agg.lastPlayed
        if (agg.count > row.bestCount && agg.id.isNotBlank()) {
            row.bestCount = agg.count
            row.coverId = agg.id
        } else if (row.coverId.isBlank() && agg.id.isNotBlank()) {
            row.coverId = agg.id
        }
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

    /**
     * 某个时间区间的歌曲明细（活跃度图表点柱子后展示）
     *
     * 优先用 events 里的 spans 与区间求交（精确到秒）；明细已被保留策略清掉时，
     * 退化为"按天聚合"（只有整天落在区间内才计入，避免把跨界的时长算整）。
     */
    @Synchronized
    fun trackDetailsInRange(fromMs: Long, toMs: Long): List<RangeDetailRow> {
        ensureLoaded()
        val dir = dataDir ?: return ArrayList()
        if (toMs <= fromMs) return ArrayList()
        val out = HashMap<String, RangeDetailRow>()
        val zone = java.time.ZoneId.systemDefault()
        val fromDay = java.time.Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val toDay = java.time.Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
        var sawEvents = false
        var day = fromDay.minusDays(1)
        val eventsDir = File(dir, EVENTS_DIR)
        while (!day.isAfter(toDay)) {
            val file = File(eventsDir, day.toString() + ".jsonl")
            if (file.isFile) {
                sawEvents = true
                try {
                    val reader = Files.newBufferedReader(file.toPath(), Charsets.UTF_8)
                    try {
                        while (true) {
                            val line = reader.readLine()
                            if (line == null) break
                            if (line.isBlank()) continue
                            val event = PlayEvent.fromJsonLine(line)
                            if (event == null) continue
                            val spans = normalizedSpans(event)
                            var total = 0L
                            var si = 0
                            while (si + 1 < spans.size) {
                                val start = spans[si]
                                val end = start + spans[si + 1]
                                val overlapStart = if (start > fromMs) start else fromMs
                                val overlapEnd = if (end < toMs) end else toMs
                                if (overlapEnd > overlapStart) total += overlapEnd - overlapStart
                                si += 2
                            }
                            if (total <= 0L) continue
                            val key = TrackDict.keyOf(event.id, event.path)
                            if (key.isEmpty()) continue
                            var row = out[key]
                            if (row == null) {
                                row = RangeDetailRow(key)
                                row.title = event.title
                                row.artist = event.artist
                                out[key] = row
                            }
                            row.ms += total
                            // 计次：这一遍的第一段起点落在选中区间内才算
                            if (spans.size >= 2 && spans[0] >= fromMs && spans[0] < toMs) row.count++
                        }
                    } finally {
                        reader.close()
                    }
                } catch (t: Throwable) {
                    System.err.println("[listen-stats] 读取区间明细失败: " + t.message)
                }
            }
            day = day.plusDays(1)
        }
        if (!sawEvents) {
            // 明细已被归档：退化为按天聚合
            var d2 = fromDay
            while (!d2.isAfter(toDay)) {
                val perDay = dayTracks[d2.toString()]
                if (perDay != null) {
                    for (dt in perDay.values) {
                        var row = out[dt.key]
                        if (row == null) {
                            row = RangeDetailRow(dt.key)
                            row.title = dt.title
                            row.artist = dt.artist
                            out[dt.key] = row
                        }
                        row.ms += dt.listenedMs
                        row.count += dt.count
                    }
                }
                d2 = d2.plusDays(1)
            }
        }
        // 补全标题/艺术家（事件里可能为空，字典里更全）
        for (row in out.values) {
            val entry = TrackDict.get(row.key)
            if (entry != null) {
                if (row.title.isEmpty()) row.title = entry.title
                if (row.artist.isEmpty()) row.artist = entry.artist
            }
        }
        val list = ArrayList<RangeDetailRow>(out.values)
        list.sortWith(Comparator { a, b -> b.ms.compareTo(a.ms) })
        return list
    }

    /**
     * 热力图色阶基准：近 12 个月非零收听日的四分位
     *
     * 基准与"当前查看的年份"无关，所以切年份时颜色稳定、跨年可比；
     * 样本不足 30 天时退化为"全部数据最大值等距"，避免分位数抖动。
     */
    @Synchronized
    fun heatmapBaseline(todayKey: String): HeatmapBaseline {
        ensureLoaded()
        val cutoff = try {
            LocalDate.parse(todayKey).minusMonths(12).toString()
        } catch (t: Throwable) {
            "0000-00-00"
        }
        val samples = ArrayList<Long>(512)
        for (entry in days.entries) {
            if (entry.key < cutoff) continue
            val ms = entry.value.listenedMs
            if (ms > 0L) samples.add(ms)
        }
        if (samples.size < 30) {
            var max = 0L
            for (entry in days.entries) {
                if (entry.value.listenedMs > max) max = entry.value.listenedMs
            }
            return HeatmapBaseline(
                p25 = max * 25L / 100L,
                p50 = max * 50L / 100L,
                p75 = max * 75L / 100L,
                fallback = true,
                sampleDays = samples.size
            )
        }
        samples.sort()
        val n = samples.size
        return HeatmapBaseline(
            p25 = samples[quarterIndex(n, 1)],
            p50 = samples[quarterIndex(n, 2)],
            p75 = samples[quarterIndex(n, 3)],
            fallback = false,
            sampleDays = n
        )
    }

    private fun quarterIndex(n: Int, quarter: Int): Int {
        val index = n * quarter / 4
        return if (index >= n) n - 1 else index
    }

    /** 历史最长连续收听天数 */
    @Synchronized
    fun longestStreak(): Int {
        ensureLoaded()
        val played = ArrayList<LocalDate>()
        for (entry in days.entries) {
            val d = entry.value
            if (d.count <= 0 && d.listenedMs <= 0L) continue
            val date = try {
                LocalDate.parse(entry.key)
            } catch (t: Throwable) {
                continue
            }
            played.add(date)
        }
        if (played.isEmpty()) return 0
        played.sortWith(Comparator { a, b -> a.compareTo(b) })
        var best = 1
        var run = 1
        var i = 1
        while (i < played.size) {
            if (played[i] == played[i - 1].plusDays(1)) run++ else run = 1
            if (run > best) best = run
            i++
        }
        return best
    }

    /** 听得最多的一天（日期） */
    @Synchronized
    fun peakDay(): String {
        ensureLoaded()
        var bestDate = ""
        var bestMs = 0L
        for (entry in days.entries) {
            val ms = entry.value.listenedMs
            if (ms > bestMs) {
                bestMs = ms
                bestDate = entry.key
            }
        }
        return bestDate
    }

    /** 听得最多的一天（时长） */
    @Synchronized
    fun peakMillis(): Long {
        ensureLoaded()
        var best = 0L
        for (entry in days.entries) {
            val ms = entry.value.listenedMs
            if (ms > best) best = ms
        }
        return best
    }

    /** 每日平均收听时长（按「有听歌的天数」平均，对齐 Jethuit 口径） */
    @Synchronized
    fun dailyAverageMillis(): Long {
        ensureLoaded()
        var total = 0L
        var active = 0
        for (entry in days.entries) {
            val ms = entry.value.listenedMs
            if (ms <= 0L) continue
            total += ms
            active++
        }
        return if (active == 0) 0L else total / active
    }

    /** 最常听歌时段（0-23 小时中累计时长最大的那个）；无数据返回 -1 */
    @Synchronized
    fun favoriteHour(): Int {
        ensureLoaded()
        val totals = LongArray(24)
        for (entry in days.entries) {
            val hoursMs = entry.value.hoursMs
            var h = 0
            while (h < 24) {
                totals[h] += hoursMs[h]
                h++
            }
        }
        var bestHour = -1
        var bestMs = 0L
        var h = 0
        while (h < 24) {
            if (totals[h] > bestMs) {
                bestMs = totals[h]
                bestHour = h
            }
            h++
        }
        return bestHour
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
        ensureCacheFresh()
        val cached = cachedSnapshot
        if (cached != null && cachedSnapshotTop == topLimit && cachedSnapshotRecent == recentLimit) {
            return cached
        }

        val byCount = ArrayList<TrackAgg>()
        for (agg in tracks.values) {
            if (agg.count > 0 && !hideInList(agg.key)) byCount.add(agg)
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
            if (agg.lastPlayed > 0L && !hideInList(agg.key)) byRecent.add(agg)
        }
        byRecent.sortWith(Comparator { a, b -> b.lastPlayed.compareTo(a.lastPlayed) })
        val recent = ArrayList<TrackAgg>()
        var recentCount = 0
        while (recentCount < recentLimit && recentCount < byRecent.size) {
            recent.add(byRecent[recentCount].copyOf())
            recentCount++
        }

        var totalSkips = 0
        for (agg in tracks.values) totalSkips += agg.skipCount

        val result = StatsSnapshot(
            totalCount = totalCount,
            totalMs = totalMs,
            trackCount = tracks.size,
            skipCount = totalSkips,
            topTracks = top,
            recentTracks = recent
        )
        cachedSnapshot = result
        cachedSnapshotTop = topLimit
        cachedSnapshotRecent = recentLimit
        return result
    }

    /** 清空（内存 + 磁盘事件日志改名为 .bak，可回滚） */
    @Synchronized
    fun reset(): String {
        ensureLoaded()
        val dir = dataDir
        // 先排空写队列：否则排队中的旧事件会在改名之后又被追加到 events/、daily/
        drainIo()
        tracks.clear()
        days.clear()
        dayTracks.clear()
        totalCount = 0
        totalMs = 0L
        replayedLines = 0
        // 曲目字典必须一起清：否则 reset 前听过的歌还留在内存里，
        // TrackDict.ensure() 命中后不再写新字典（旧文件已改名），
        // 重启后这些曲目全部变成空标题 / 空艺术家
        TrackDict.clear()
        // 缺失记录也跟着清：曲目都没了，留着徽章只会显示一堆「缺失」
        MissingStore.clear()
        // 让面板的 1.5 秒轮询感知到清空
        dataVersion++
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
            out.add(copyDay(sorted[i]))
            i++
        }
        return out
    }

    /**
     * 全部有记录的天（按日期升序）
     *
     * 活跃度页必须用它：热力图的年份视图和时长分布的自定义区间都按日历查值，
     * 用「最近 N 天」当数据源会让更早的日期静默变成「无收听」。
     */
    @Synchronized
    fun allDays(): List<DayAgg> {
        ensureLoaded()
        val sorted = ArrayList<DayAgg>(days.size)
        for (d in days.values) sorted.add(d)
        sorted.sortWith(Comparator { a, b -> a.date.compareTo(b.date) })
        val out = ArrayList<DayAgg>(sorted.size)
        var i = 0
        while (i < sorted.size) {
            out.add(copyDay(sorted[i]))
            i++
        }
        return out
    }

    /** 按天聚合的深拷贝（对外一律返回拷贝，避免 UI 在锁外读到并发修改的中间值） */
    private fun copyDay(src: DayAgg): DayAgg {
        val copy = DayAgg(src.date)
        copy.count = src.count
        copy.listenedMs = src.listenedMs
        var h = 0
        while (h < 24) {
            copy.hours[h] = src.hours[h]
            copy.hoursMs[h] = src.hoursMs[h]
            h++
        }
        return copy
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
        lastLoadError = null
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
        var failedFiles = 0
        var lastFailure = ""
        for (file in list) {
            try {
                val reader = Files.newBufferedReader(file.toPath(), Charsets.UTF_8)
                try {
                    while (true) {
                        val line = reader.readLine()
                        if (line == null) break
                        if (!applyDailyLine(line)) {
                            failedFiles++
                            lastFailure = file.name + "：有无法解析的行"
                        }
                    }
                } finally {
                    reader.close()
                }
            } catch (t: Throwable) {
                failedFiles++
                lastFailure = file.name + "：" + (t.message ?: t.javaClass.simpleName)
                System.err.println("[listen-stats] 读取聚合 " + file.name + " 失败: " + t.message)
            }
        }
        if (failedFiles > 0) {
            lastLoadError = "有 " + failedFiles + " 个聚合文件读取失败（" + lastFailure + "），当前显示的是其余数据"
        }
    }

    /**
     * 解析一行聚合记录并累加到内存
     *
     * @return false = 这一行无法解析（坏行会让上层报"读取异常"，而不是静默丢数据）
     */
    private fun applyDailyLine(line: String): Boolean {
        if (line.isBlank()) return true
        if (line.length < 8 || line[0] != '{') return false
        val date = JsonLite.string(line, "d")
        val key = JsonLite.string(line, "k")
        if (date.isEmpty() || key.isEmpty()) return false
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
        replayedLines++
        return true
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
            var target = File(expired, f.name)
            if (target.exists()) {
                // 同名归档已存在（之前归档过同一天）：加时间戳另存，不覆盖也不删除
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                target = File(expired, f.name + "." + stamp + ".bak")
            }
            if (f.renameTo(target)) moved++
        }
        if (moved > 0) {
            System.err.println("[listen-stats] 已归档 $moved 个超期明细文件")
        }
    }

    /**
     * 把一遍播放拆成「日期 + 小时」的多行聚合记录
     *
     * 跨天时每天各一行：次数与跳过只在**开始那一行**计 1，其余行为 0；
     * `hr` 只在开始行写真实小时，其余行写 -1，避免加载时重复累加小时次数。
     */
    private fun buildDailyLines(event: PlayEvent, key: String): List<String> {
        val spans = normalizedSpans(event)
        val startMs = if (spans.size >= 2) spans[0] else event.t
        val startDate = dayFormat.format(Date(startMs))
        val startHour = hourFormat.format(Date(startMs)).toIntOrNull() ?: 0
        val out = ArrayList<String>(3)
        if (spans.size < 2) {
            out.add(
                lineOf(startDate, key, startHour, startHour, 0L,
                    if (event.counted) 1 else 0, if (event.skipped) 1 else 0, event.t)
            )
            return out
        }
        var first = true
        var si = 0
        while (si + 1 < spans.size) {
            var cursor = spans[si]
            var remain = spans[si + 1]
            var guard = 0
            while (remain > 0L && guard < 400) {
                val zdt = try {
                    java.time.Instant.ofEpochMilli(cursor).atZone(java.time.ZoneId.systemDefault())
                } catch (t: Throwable) {
                    break
                }
                val date = dayFormat.format(Date(cursor))
                val hour = zdt.hour
                val hourEnd = nextHourBoundary(cursor)
                var part = hourEnd - cursor
                if (part > remain) part = remain
                if (part <= 0L) break
                val isStart = first
                out.add(
                    lineOf(
                        // 次数/跳过只归开始那一行；时长所属小时每行都要写，
                        // 否则跨小时那段的时长重新加载后进不了任何小时桶
                        date, key, if (isStart) hour else -1, hour, part,
                        if (isStart && event.counted) 1 else 0,
                        if (isStart && event.skipped) 1 else 0,
                        event.t
                    )
                )
                first = false
                cursor += part
                remain -= part
                guard++
            }
            si += 2
        }
        if (out.isEmpty()) {
            out.add(
                lineOf(startDate, key, startHour, startHour, 0L,
                    if (event.counted) 1 else 0, if (event.skipped) 1 else 0, event.t)
            )
        }
        return out
    }

    /** 事件的听音区间；旧事件没有 spans 时按"结束时间倒推一整段"近似 */
    private fun normalizedSpans(event: PlayEvent): LongArray {
        if (event.spans.size >= 2) return event.spans
        if (event.ms <= 0L) return LongArray(0)
        val start = if (event.t >= event.ms) event.t - event.ms else event.t
        return longArrayOf(start, event.ms)
    }

    /**
     * 下一个整点边界
     *
     * 不能简单用本地时间 truncate+plusHours：夏令时回拨的重叠小时里，它可能解析回更早的瞬间，
     * 导致 part <= 0 而把剩余时长丢掉。这里用 zone rules 的 nextTransition 兜底。
     */
    private fun nextHourBoundary(instantMs: Long): Long {
        val zone = java.time.ZoneId.systemDefault()
        val instant = java.time.Instant.ofEpochMilli(instantMs)
        val zdt = instant.atZone(zone)
        var candidate = zdt.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            .plusHours(1)
            .toInstant()
            .toEpochMilli()
        val transition = zone.rules.nextTransition(instant)
        if (transition != null) {
            val transitionMs = transition.instant.toEpochMilli()
            if (transitionMs > instantMs && transitionMs < candidate) candidate = transitionMs
        }
        if (candidate <= instantMs) candidate = instantMs + 3_600_000L
        return candidate
    }

    /**
     * @param hour 次数/跳过归属的小时（非开始行为 -1，避免加载时重复累加次数）
     * @param durHour 这段时长归属的小时（始终是真实小时，加载时按 `hs` 累加到小时分布）
     */
    private fun lineOf(
        date: String,
        key: String,
        hour: Int,
        durHour: Int,
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
        if (durHour >= 0 && durHour < 24 && partMs > 0L) span.append(durHour).append(':').append(partMs)
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
            val hourEnd = nextHourBoundary(cursor)
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

        val spans = normalizedSpans(event)
        val startMs = if (spans.size >= 2) spans[0] else event.t
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

        // 时长按「区间 ∩ 天/小时」切分：暂停留下的空洞不会被摊到其他小时，跨天也各归各的
        var si = 0
        while (si + 1 < spans.size) {
            splitByDayHour(spans[si], spans[si + 1]) { dateKey, partHour, partMs ->
                var d = days[dateKey]
                if (d == null) {
                    d = DayAgg(dateKey)
                    days[dateKey] = d
                }
                d.listenedMs += partMs
                if (partHour >= 0 && partHour < 24) d.hoursMs[partHour] += partMs
            }
            si += 2
        }

        // 当天明细 / 月周榜：按时长实际落到的日期归属（不再整段算开始日）
        var partIndex = 0
        var pj = 0
        while (pj + 1 < spans.size) {
            splitByDayHour(spans[pj], spans[pj + 1]) { dateKey, _, partMs ->
                var perDay = dayTracks[dateKey]
                if (perDay == null) {
                    perDay = LinkedHashMap()
                    dayTracks[dateKey] = perDay
                }
                var dt = perDay[key]
                if (dt == null) {
                    dt = DayTrackAgg(dateKey, key)
                    dt.title = event.title
                    dt.artist = event.artist
                    perDay[key] = dt
                }
                // 次数/跳过只记在开始日那一行，避免跨天时重复计次
                if (partIndex == 0 && event.counted) dt.count++
                if (partIndex == 0 && event.skipped) dt.skipCount++
                dt.listenedMs += partMs
                if (event.t > dt.lastPlayed) dt.lastPlayed = event.t
                partIndex++
            }
            pj += 2
        }

        if (event.counted) totalCount++
        totalMs += event.ms
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
    c.albumArtist = albumArtist
    c.count = count
    c.listenedMs = listenedMs
    c.skipCount = skipCount
    c.firstPlayed = firstPlayed
    c.lastPlayed = lastPlayed
    c.durationMs = durationMs
    return c
}