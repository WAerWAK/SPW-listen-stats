package com.spwmods.waerwak.listenstats.test

import com.spwmods.waerwak.listenstats.stats.CountRule
import com.spwmods.waerwak.listenstats.stats.LiveSessionStore
import com.spwmods.waerwak.listenstats.stats.MissingStore
import com.spwmods.waerwak.listenstats.stats.PlayEvent
import com.spwmods.waerwak.listenstats.stats.SessionTracker
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.StatsThresholds
import com.spwmods.waerwak.listenstats.stats.TrackAgg
import com.spwmods.waerwak.listenstats.ui.HostUiTargets
import com.spwmods.waerwak.listenstats.ui.discovery.HostBytecodeIndex
import com.spwmods.waerwak.listenstats.ui.discovery.HostUiDiscovery
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import java.io.File
import java.time.ZoneId

/**
 * 数据层回归测试（零依赖：`main` + 断言）
 *
 * 覆盖的是最容易出错、又最难靠人工验收发现的边界：
 *  - 暂停期间不计时、跨天按小时切分
 *  - 回退 seek / 拖回开头 / 单曲循环算作新的一遍
 *  - 位置大跳、睡眠唤醒不编造时长
 *  - 计数门槛的三种模式
 *  - 崩溃恢复的幂等性
 *
 * 运行：`gradle runDataTests`
 */

private var passed = 0
private var failed = 0

private const val MINUTE = 60_000L
private const val SECOND = 1_000L

// ---------------------------------------------------------------- 假时钟

private var fakeNano = 0L
private var fakeWall = 0L

private fun resetClock() {
    fakeNano = 0L
    // 2026-10-19 23:59:30（本地时区），方便构造跨天场景
    fakeWall = localMillis(2026, 10, 19, 23, 59, 30)
}

private fun localMillis(
    year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int
): Long {
    return java.time.LocalDateTime.of(year, month, day, hour, minute, second)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

/** 推进假时钟（毫秒） */
private fun advance(ms: Long) {
    fakeNano += ms * 1_000_000L
    fakeWall += ms
}

// ---------------------------------------------------------------- 环境

private var tempRoot: File? = null

private fun freshEnv(
    minSec: Int = 30,
    percent: Int = 50,
    mode: String = "max",
    ignoreSec: Int = 0
): File {
    val dir = File(
        System.getProperty("java.io.tmpdir"),
        "spw-listenstats-test-" + System.nanoTime()
    )
    dir.mkdirs()
    tempRoot = dir
    StatsStore.resetForTesting(dir)
    SessionTracker.resetForTesting()
    resetClock()
    SessionTracker.nanoClock = { fakeNano }
    SessionTracker.wallClock = { fakeWall }
    CountRule.setForTesting(minSec, percent, mode, ignoreSec)
    LiveSessionStore.clear()
    return dir
}

private fun track(
    id: String,
    title: String = "曲目-" + id,
    artist: String = "艺术家-" + id,
    album: String = "专辑-" + id,
    albumArtist: String = "",
    durationMs: Long = 4 * MINUTE
): PlaybackExtensionPoint.MediaItem {
    return PlaybackExtensionPoint.MediaItem(
        title, artist, album, albumArtist, "C:\\Music\\" + id + ".flac",
        id, 2026, "", 1, durationMs, false, true
    )
}

/** 模拟一次位置回调：推进时钟并按每 1 秒 1 次的真实节奏走 */
private fun playFor(item: PlaybackExtensionPoint.MediaItem, fromMs: Long, listenMs: Long) {
    var position = fromMs
    var remain = listenMs
    while (remain > 0L) {
        val step = if (remain > SECOND) SECOND else remain
        advance(step)
        position += step
        SessionTracker.onPosition(position)
        remain -= step
    }
}

private fun totalCount(): Int {
    var total = 0
    val list = StatsStore.allTracks()
    var i = 0
    while (i < list.size) {
        total += list[i].count
        i++
    }
    return total
}

private fun totalListenedMs(): Long {
    var total = 0L
    val list = StatsStore.allTracks()
    var i = 0
    while (i < list.size) {
        total += list[i].listenedMs
        i++
    }
    return total
}

private fun findTrack(id: String): TrackAgg? {
    val list = StatsStore.allTracks()
    var i = 0
    while (i < list.size) {
        if (list[i].id == id) return list[i]
        i++
    }
    return null
}

private fun check(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

private fun checkNear(actual: Long, expected: Long, tolerance: Long, message: String) {
    val diff = if (actual > expected) actual - expected else expected - actual
    if (diff > tolerance) {
        throw AssertionError(message + "（实际 " + actual + "，期望 " + expected + "±" + tolerance + "）")
    }
}

private fun test(name: String, body: () -> Unit) {
    try {
        body()
        passed++
        println("PASS  " + name)
    } catch (t: Throwable) {
        failed++
        println("FAIL  " + name)
        println("      " + (t.message ?: t.javaClass.simpleName))
    }
}

// ---------------------------------------------------------------- 用例

fun main() {
    println("== 听歌统计 · 数据层回归测试 ==")
    println()

    test("暂停期间不计入时长") {
        freshEnv()
        val item = track("pause")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 20 * SECOND)
        // 暂停 60 秒：位置不动，时钟继续走
        SessionTracker.onPlayingChanged(false)
        advance(60 * SECOND)
        SessionTracker.onPosition(20 * SECOND)
        // 恢复播放，再听 10 秒
        SessionTracker.onPlayingChanged(true)
        playFor(item, 20 * SECOND, 10 * SECOND)
        SessionTracker.flush()
        val agg = findTrack("pause")
        check(agg != null, "应该有曲目记录")
        checkNear(agg!!.listenedMs, 30 * SECOND, 2 * SECOND, "暂停不应计入时长")
    }

    test("跨天按小时切分") {
        val dir = freshEnv()
        val item = track("crossday")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        // 23:59:30 开始，听 60 秒 → 30 秒落在 19 日，30 秒落在 20 日
        playFor(item, 0L, 60 * SECOND)
        SessionTracker.flush()

        val day19 = StatsStore.recentDays(400)
        var ms19 = 0L
        var ms20 = 0L
        var i = 0
        while (i < day19.size) {
            val d = day19[i]
            if (d.date == "2026-10-19") ms19 = d.listenedMs
            if (d.date == "2026-10-20") ms20 = d.listenedMs
            i++
        }
        checkNear(ms19, 30 * SECOND, 2 * SECOND, "19 日应分到约 30 秒")
        checkNear(ms20, 30 * SECOND, 2 * SECOND, "20 日应分到约 30 秒")
        check(dir.isDirectory, "数据目录应存在")
    }

    test("已计数后拖回开头算新的一遍") {
        freshEnv(minSec = 10, percent = 0, mode = "max")
        val item = track("restart")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 20 * SECOND)
        check(totalCount() == 0 || totalCount() == 1, "计数应在结算后出现")
        SessionTracker.onSeek(0L)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 20 * SECOND)
        SessionTracker.flush()
        val agg = findTrack("restart")
        check(agg != null, "应该有记录")
        check(agg!!.count == 2, "两遍都应计数，实际 " + agg.count)
    }

    test("位置大幅跳变不编造时长") {
        freshEnv()
        val item = track("jump")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 10 * SECOND)
        // 直接跳到 8 分钟处（超过 MAX_STEP_MS 上限）：不应把这 8 分钟算成收听
        advance(SECOND)
        SessionTracker.onPosition(8 * MINUTE)
        SessionTracker.flush()
        val agg = findTrack("jump")
        check(agg != null, "应该有记录")
        checkNear(agg!!.listenedMs, 10 * SECOND, 2 * SECOND, "跳变不应计入时长")
    }

    test("计数门槛：取较大 / 取较小") {
        CountRule.setForTesting(30, 50, "max")
        // 4 分钟曲目：30 秒 vs 50% = 120 秒 → 取较大 = 120 秒
        check(
            StatsThresholds.countThreshold(4 * MINUTE) == 120 * SECOND,
            "max 模式应为 120 秒，实际 " + StatsThresholds.countThreshold(4 * MINUTE)
        )
        CountRule.setForTesting(30, 50, "min")
        check(
            StatsThresholds.countThreshold(4 * MINUTE) == 30 * SECOND,
            "min 模式应为 30 秒，实际 " + StatsThresholds.countThreshold(4 * MINUTE)
        )
        // 时长未知（0）时只能按秒数门槛
        CountRule.setForTesting(45, 50, "max")
        check(
            StatsThresholds.countThreshold(0L) == 45 * SECOND,
            "时长未知时应使用秒数门槛"
        )
    }

    test("计数门槛影响是否计数") {
        freshEnv(minSec = 60, percent = 0, mode = "max")
        val item = track("threshold", durationMs = 4 * MINUTE)
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        // 只听了 20 秒，未达 60 秒门槛
        playFor(item, 0L, 20 * SECOND)
        SessionTracker.flush()
        val agg = findTrack("threshold")
        check(agg != null, "应该有记录")
        check(agg!!.count == 0, "未达门槛不应计数，实际 " + agg.count)
        check(agg.listenedMs > 0L, "时长仍应记录")
    }

    test("崩溃恢复幂等") {
        val dir = freshEnv()
        // 手工写一个未结算会话：听了 2 分钟
        val file = File(dir, "live-session.json")
        file.writeText(
            "{\"v\":1,\"ms\":" + (2 * MINUTE) +
                ",\"t\":" + (fakeWall - 2 * MINUTE) +
                ",\"dur\":" + (4 * MINUTE) +
                // savedAt 必须是真实时间：LiveSessionStore 用 System.currentTimeMillis() 判 7 天过期，
                // 用假时钟（2026-10-19）会被当成过期残留而丢弃
                ",\"c\":true,\"savedAt\":" + System.currentTimeMillis() +
                ",\"id\":\"recover\",\"ti\":\"恢复测试\",\"ar\":\"测试歌手\",\"al\":\"测试专辑\"}",
            Charsets.UTF_8
        )
        val first = LiveSessionStore.recover()
        check(first, "第一次恢复应该成功")
        val afterFirst = totalListenedMs()
        checkNear(afterFirst, 2 * MINUTE, 2 * SECOND, "恢复的时长应补记")

        val second = LiveSessionStore.recover()
        check(!second, "第二次恢复不应重复计入")
        val afterSecond = totalListenedMs()
        check(afterSecond == afterFirst, "重复恢复不能改变总时长")
    }

    test("跨天落盘后重新加载一致") {
        val dir = freshEnv()
        val item = track("persist")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 60 * SECOND)
        SessionTracker.flush()
        // 等待异步写盘完成
        Thread.sleep(400L)

        // 清空内存（保留同一目录），强制重新从磁盘加载
        StatsStore.resetForTesting(dir)
        StatsStore.dataDirOrNull()

        var ms19 = 0L
        var ms20 = 0L
        val list = StatsStore.recentDays(400)
        var i = 0
        while (i < list.size) {
            val d = list[i]
            if (d.date == "2026-10-19") ms19 = d.listenedMs
            if (d.date == "2026-10-20") ms20 = d.listenedMs
            i++
        }
        checkNear(ms19, 30 * SECOND, 3 * SECOND, "重新加载后 19 日时长")
        checkNear(ms20, 30 * SECOND, 3 * SECOND, "重新加载后 20 日时长")
        check(totalCount() == 1, "重新加载后次数应为 1，实际 " + totalCount())

        // 小时分布不能因为拆行而重复计数
        var hourSum = 0
        val hist = StatsStore.hourHistogramRange("2026-10-19", "2026-10-20")
        var h = 0
        while (h < hist.size) {
            hourSum += hist[h]
            h++
        }
        check(hourSum == 1, "小时次数总和应为 1，实际 " + hourSum)
    }

    test("过期残留不恢复") {
        val dir = freshEnv()
        val file = File(dir, "live-session.json")
        val old = fakeWall - 30L * 24L * 60L * 60L * 1000L
        file.writeText(
            "{\"v\":1,\"ms\":" + MINUTE + ",\"t\":" + (old - MINUTE) +
                ",\"dur\":240000,\"c\":true,\"savedAt\":" + old + ",\"id\":\"stale\"}",
            Charsets.UTF_8
        )
        check(!LiveSessionStore.recover(), "超过 7 天的残留不应恢复")
        check(totalListenedMs() == 0L, "不应补记任何时长")
    }

    test("跨小时时长落盘后仍计入小时分布") {
        val dir = freshEnv()
        // 14:50 开始听 20 分钟（t 为结束时间 15:10），跨过 15 点
        val end = localMillis(2026, 10, 19, 15, 10, 0)
        StatsStore.record(
            PlayEvent(
                t = end,
                id = "crosshour",
                path = "C:\\Music\\crosshour.flac",
                title = "跨小时",
                artist = "歌手",
                album = "专辑",
                ms = 20 * MINUTE,
                dur = 4 * MINUTE,
                counted = true,
                skipped = false
            )
        )
        Thread.sleep(400L)
        val live = StatsStore.hourProfile("2026-10-19", "2026-10-19")
        // 清空内存，强制重新从磁盘加载
        StatsStore.resetForTesting(dir)
        StatsStore.dataDirOrNull()
        val reloaded = StatsStore.hourProfile("2026-10-19", "2026-10-19")
        checkNear(reloaded.ms[14], 10 * MINUTE, 1_000L, "重载后 14 点时长")
        checkNear(reloaded.ms[15], 10 * MINUTE, 1_000L, "重载后 15 点时长")
        checkNear(reloaded.ms[15], live.ms[15], 1_000L, "重载前后 15 点时长应一致")
    }

    test("明细事件保留 path") {
        freshEnv()
        val end = localMillis(2026, 10, 19, 12, 0, 0)
        StatsStore.record(
            PlayEvent(
                t = end,
                id = "pathcase",
                path = "C:\\Music\\pathcase.flac",
                title = "t",
                artist = "a",
                album = "b",
                ms = MINUTE,
                dur = 4 * MINUTE,
                counted = true,
                skipped = false
            )
        )
        Thread.sleep(400L)
        val list = StatsStore.trackHistory("pathcase", 10)
        check(list.size == 1, "应有一条明细，实际 " + list.size)
        check(
            list[0].path == "C:\\Music\\pathcase.flac",
            "明细应保留 path，实际 '" + list[0].path + "'"
        )
    }

    test("恢复时拒绝路径穿越条目") {
        val dir = freshEnv()
        val zip = File(dir, "evil.zip")
        val zos = java.util.zip.ZipOutputStream(zip.outputStream())
        zos.putNextEntry(java.util.zip.ZipEntry("../escape.txt"))
        zos.write("x".toByteArray(Charsets.UTF_8))
        zos.closeEntry()
        zos.close()
        StatsStore.restoreFrom(zip)
        val escaped = File(dir.parentFile, "escape.txt")
        check(!escaped.exists(), "不应写到数据目录之外： " + escaped.absolutePath)
    }

    test("超期明细归档不覆盖已有文件") {
        val dir = freshEnv()
        val events = File(dir, "events")
        events.mkdirs()
        File(events, "2020-01-01.jsonl").writeText("{}\n", Charsets.UTF_8)
        val expired = File(dir, "expired")
        expired.mkdirs()
        val existing = File(expired, "2020-01-01.jsonl")
        existing.writeText("old\n", Charsets.UTF_8)
        // 重置后重新加载会触发 cleanupExpiredEvents
        StatsStore.resetForTesting(dir)
        StatsStore.dataDirOrNull()
        check(existing.isFile, "已有归档不能被删除")
        check(existing.readText(Charsets.UTF_8) == "old\n", "已有归档不能被覆盖")
        val files = expired.listFiles()
        check(files != null && files.size >= 2, "超期文件应以新名字另存，实际 " + (files?.size ?: 0) + " 个")
        check(!File(events, "2020-01-01.jsonl").exists(), "原明细应被移走")
    }

    test("极短播放整条丢弃") {
        freshEnv(minSec = 30, percent = 50, mode = "min", ignoreSec = 5)
        val item = track("tooshort")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 2 * SECOND)
        SessionTracker.flush()
        check(StatsStore.allTracks().isEmpty(), "低于忽略阈值的播放不应产生记录")
        check(StatsStore.replayedLineCount() == 0, "不应写入聚合行")
    }

    test("超过忽略阈值但未达门槛记为跳过") {
        freshEnv(minSec = 30, percent = 50, mode = "min", ignoreSec = 5)
        val item = track("skipped")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 8 * SECOND)
        SessionTracker.flush()
        val agg = findTrack("skipped")
        check(agg != null, "应该有记录")
        check(agg!!.count == 0, "未达门槛不应计数，实际 " + agg.count)
        check(agg.skipCount == 1, "应记为跳过，实际 " + agg.skipCount)
        check(agg.listenedMs > 0L, "时长仍应保留")
    }

    test("清空后继续播放，重启后曲目名不丢") {
        val dir = freshEnv()
        val item = track("after-reset", title = "重置后的歌")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 40 * SECOND)
        SessionTracker.flush()
        check(findTrack("after-reset")?.title == "重置后的歌", "清空前应有标题")

        StatsStore.reset()

        // 清空后重新听同一首：内存字典已清，必须重新写 tracks.jsonl
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 40 * SECOND)
        SessionTracker.flush()

        // 模拟重启：清内存后从磁盘重建
        StatsStore.resetForTesting(dir)
        StatsStore.dataDirOrNull()
        val again = findTrack("after-reset")
        check(again != null, "重启后应能读到曲目")
        check(again!!.title == "重置后的歌", "重启后标题不应丢失（实际：\"" + again.title + "\"）")
    }

    test("恢复失败时回滚原数据") {
        val dir = freshEnv()
        val item = track("keep")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 40 * SECOND)
        SessionTracker.flush()
        // record 是异步落盘的，等写队列把字典刷出去（restoreFrom 内部也会 drain）
        Thread.sleep(400L)
        check(File(dir, "tracks.jsonl").isFile, "应有字典文件")

        // 非 zip 文件：解压必然失败
        val bad = File(dir, "bad.zip")
        bad.writeText("not a zip", Charsets.UTF_8)
        val msg = StatsStore.restoreFrom(bad)
        check(msg.contains("失败") || msg.contains("未恢复"), "应报告失败：" + msg)

        // 原数据必须回到原位，而不是只剩一堆 .bak
        check(File(dir, "tracks.jsonl").isFile, "字典文件应被回滚")
        check(File(dir, "daily").isDirectory, "按天聚合目录应被回滚")
        val again = findTrack("keep")
        check(again != null, "内存应能重新加载原数据")
        check(again!!.count == 1, "原数据次数应保持，实际 " + again.count)
    }

    test("分页页码越界时不崩溃") {
        val dir = freshEnv()
        var i = 0
        while (i < 25) {
            val item = track("p" + i)
            SessionTracker.onTrack(item)
            SessionTracker.onPlayingChanged(true)
            playFor(item, 0L, 40 * SECOND)
            SessionTracker.flush()
            i++
        }
        check(dir.isDirectory, "数据目录应存在")
        val page = StatsStore.trackPageRange(99, 10, 0, false, "", null)
        check(page.total == 25, "总数应为 25，实际 " + page.total)
        check(page.items.isEmpty(), "越界页应返回空列表而不是崩溃")
    }

    test("缺失记录落盘后重启仍能判定") {
        val dir = freshEnv()
        MissingStore.attach(dir)
        check(MissingStore.count() == 0, "初始应无缺失记录")
        check(MissingStore.checkedAt() == 0L, "初始检测时间应为 0")

        MissingStore.setResult(setOf("hexA", "hexB"), setOf("id-1", "id-2"), 1_700_000_000_000L)
        check(MissingStore.isMissingKey("hexA"), "应判定 hexA 缺失")
        check(!MissingStore.isMissingKey("hexC"), "未记录的 key 不应判缺失")
        check(MissingStore.isMissingId("id-2"), "应判定 id-2 缺失")
        check(File(dir, "missing.txt").isFile, "检测结果应落盘")

        // 模拟重启：丢掉内存后从文件重建
        MissingStore.attach(dir)
        check(MissingStore.count() == 2, "重启后缺失数应为 2，实际 " + MissingStore.count())
        check(MissingStore.checkedAt() == 1_700_000_000_000L, "重启后检测时间应一致")
        check(MissingStore.isMissingKey("hexB"), "重启后应仍判定 hexB 缺失")
        check(MissingStore.isMissingId("id-1"), "重启后应仍判定 id-1 缺失")

        MissingStore.clear()
        MissingStore.attach(dir)
        check(MissingStore.count() == 0, "清空后不应再有缺失记录")
        check(!File(dir, "missing.txt").exists(), "清空后应删除记录文件")
    }

    test("隐藏缺失曲目时榜单与概览同步过滤") {
        val dir = freshEnv()
        val keep = track("keep-row", title = "保留的歌")
        SessionTracker.onTrack(keep)
        SessionTracker.onPlayingChanged(true)
        playFor(keep, 0L, 40 * SECOND)
        SessionTracker.flush()
        val gone = track("gone-row", title = "消失的歌")
        SessionTracker.onTrack(gone)
        SessionTracker.onPlayingChanged(true)
        playFor(gone, 0L, 40 * SECOND)
        SessionTracker.flush()

        check(StatsStore.trackPageRange(0, 10, 0, false, "", null).total == 2, "隐藏前应有 2 首")
        val goneAgg = findTrack("gone-row")
        check(goneAgg != null, "应该有消失的歌的记录")

        MissingStore.attach(dir)
        MissingStore.setResult(setOf(goneAgg!!.key), setOf("gone-row"), 1_700_000_100_000L)
        MissingStore.hidden = false
        check(MissingStore.isMissingKey(goneAgg.key), "应判定为缺失")

        MissingStore.hidden = true
        val page = StatsStore.trackPageRange(0, 10, 0, false, "", null)
        check(page.total == 1, "隐藏后应只剩 1 首，实际 " + page.total)
        check(page.items.size == 1 && page.items[0].id == "keep-row", "保留的应是 keep-row")
        val snap = StatsStore.snapshot(10, 10)
        var inTop = false
        var i = 0
        while (i < snap.topTracks.size) {
            if (snap.topTracks[i].id == "gone-row") inTop = true
            i++
        }
        check(!inTop, "概览 Top 榜不应出现缺失曲目")
        var inRecent = false
        var j = 0
        while (j < snap.recentTracks.size) {
            if (snap.recentTracks[j].id == "gone-row") inRecent = true
            j++
        }
        check(!inRecent, "概览最近播放不应出现缺失曲目")

        MissingStore.hidden = false
        check(
            StatsStore.trackPageRange(0, 10, 0, false, "", null).total == 2,
            "关掉隐藏后应恢复 2 首"
        )
        MissingStore.clear()
    }

    test("宿主界面结构化发现与 1.19.3 目标一致") {
        val jarPath = System.getProperty("spw.host.jar")
        if (jarPath == null || !File(jarPath).isFile) {
            println("    （跳过：未提供 -Dspw.host.jar）")
        } else {
            val started = System.currentTimeMillis()
            val index = HostBytecodeIndex.read(File(jarPath).toPath())
            val discovered = HostUiDiscovery.discover(index)
            val elapsed = System.currentTimeMillis() - started
            val golden = HostUiTargets.forVersion("1.19.3")
            check(golden != null, "版本表应登记 1.19.3")
            val t = discovered.targets
            check(t.pageCaller == golden!!.pageCaller, "pageCaller 不一致：" + t.pageCaller + " != " + golden.pageCaller)
            check(t.titlebarCaller == golden.titlebarCaller, "titlebarCaller 不一致：" + t.titlebarCaller + " != " + golden.titlebarCaller)
            check(t.debugCaption == golden.debugCaption, "debugCaption 不一致：" + t.debugCaption + " != " + golden.debugCaption)
            check(t.captionClass == golden.captionClass, "captionClass 不一致：" + t.captionClass + " != " + golden.captionClass)
            check(t.debugCaptionMethod == golden.debugCaptionMethod, "debugCaptionMethod 不一致")
            check(t.captionMethodName == golden.captionMethodName, "captionMethodName 不一致")
            check(discovered.layer != null, "应能发现材质 Layer 位点")
            check(elapsed < 30_000L, "扫描耗时过长：" + elapsed + "ms")
            println("    （扫描 " + index.methods.size + " 个方法，用时 " + elapsed + "ms）")
        }
    }

    test("位置粒度粗于回调频率时不少记") {
        freshEnv(minSec = 30, percent = 50, mode = "min", ignoreSec = 0)
        val item = track("dense", durationMs = 5 * MINUTE)
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        // 宿主：位置按 1 秒粒度跳变，但 onPositionUpdated 每 400ms 回调一次
        var i = 0
        while (i < 150) {
            advance(400L)
            val pos = ((i + 1) * 400L) / 1000L * 1000L
            SessionTracker.onPosition(pos)
            i++
        }
        SessionTracker.flush()
        val agg = findTrack("dense")
        check(agg != null, "应该有记录")
        checkNear(agg!!.listenedMs, 60 * SECOND, 3 * SECOND, "高频回调不应按比例少记")
    }

    test("曲中启用插件也能记满") {
        freshEnv(minSec = 30, percent = 50, mode = "min", ignoreSec = 0)
        val item = track("midenable", durationMs = 5 * MINUTE)
        SessionTracker.onTrack(item)
        // 刻意不发 onPlayingChanged(true)：模拟播放中途启用 / 热重载（宿主不会补发状态）
        playFor(item, 0L, 30 * SECOND)
        SessionTracker.flush()
        val agg = findTrack("midenable")
        check(agg != null, "未收到播放状态也应记录")
        checkNear(agg!!.listenedMs, 30 * SECOND, 3 * SECOND, "曲中启用应记满")
    }

    test("2 倍速按实际听音时间记") {
        freshEnv(minSec = 30, percent = 50, mode = "min", ignoreSec = 0)
        val item = track("speed", durationMs = 5 * MINUTE)
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        var i = 0
        while (i < 30) {
            advance(SECOND)
            SessionTracker.onPosition((i + 1) * 2 * SECOND)
            i++
        }
        SessionTracker.flush()
        val agg = findTrack("speed")
        check(agg != null, "应该有记录")
        checkNear(agg!!.listenedMs, 30 * SECOND, 3 * SECOND, "倍速按墙钟记（30 秒真实时间）")
    }

    test("采样停摆超过上限时丢弃该段") {
        freshEnv(minSec = 30, percent = 50, mode = "min", ignoreSec = 0)
        val item = track("stall", durationMs = 10 * MINUTE)
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 10 * SECOND)
        // 位置照常前进，但 20 秒没有回调（切后台 / 线程被卡）
        advance(20 * SECOND)
        SessionTracker.onPosition(30 * SECOND)
        playFor(item, 30 * SECOND, 10 * SECOND)
        SessionTracker.flush()
        val agg = findTrack("stall")
        check(agg != null, "应该有记录")
        checkNear(agg!!.listenedMs, 20 * SECOND, 3 * SECOND, "停摆的 20 秒不应计入")
    }

    test("暂停缺口不进入听音区间") {
        freshEnv(minSec = 30, percent = 50, mode = "min", ignoreSec = 0)
        val item = track("spans-pause", durationMs = 5 * MINUTE)
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 10 * SECOND)
        SessionTracker.onPlayingChanged(false)
        advance(60 * SECOND)
        SessionTracker.onPosition(10 * SECOND)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 10 * SECOND, 10 * SECOND)
        SessionTracker.flush()
        Thread.sleep(400L)
        val history = StatsStore.trackHistory("spans-pause", 5)
        check(history.size == 1, "应有一条事件，实际 " + history.size)
        val e = history[0]
        check(e.eid.isNotEmpty(), "事件应带会话 id")
        check(e.spanCount() == 2, "应有 2 段区间，实际 " + e.spanCount())
        var sum = 0L
        var i = 0
        while (i + 1 < e.spans.size) {
            sum += e.spans[i + 1]
            i += 2
        }
        checkNear(sum, e.ms, 1L, "区间之和应等于总时长")
        val gap = e.spans[2] - (e.spans[0] + e.spans[1])
        check(gap > 50 * SECOND, "暂停的 60 秒不应落在区间内，实际空隙 " + gap + "ms")
    }

    test("崩溃恢复保留听音区间与会话 id") {
        val dir = freshEnv()
        val now = System.currentTimeMillis()
        val spanStart = now - 120_000L
        val file = File(dir, "live-session.json")
        file.writeText(
            "{\"v\":2,\"ms\":40000,\"t\":" + (now - 60_000L) +
                ",\"dur\":300000,\"c\":true,\"savedAt\":" + now +
                ",\"id\":\"spanrec\",\"eid\":\"e-1\",\"s\":\"" + spanStart + ":40000\"" +
                ",\"ti\":\"恢复区间\"}",
            Charsets.UTF_8
        )
        val ok = LiveSessionStore.recover()
        check(ok, "恢复应该成功")
        Thread.sleep(400L)
        val history = StatsStore.trackHistory("spanrec", 5)
        check(history.size == 1, "应有一条事件，实际 " + history.size)
        val e = history[0]
        check(e.eid == "e-1", "会话 id 应保留，实际 " + e.eid)
        check(e.spanCount() == 1, "区间应保留，实际 " + e.spanCount())
        check(e.spans[0] == spanStart, "区间起点应保留")
        checkNear(e.ms, 40 * SECOND, 1L, "恢复后的总时长")
    }

    test("时长格式四舍五入") {
        check(com.spwmods.waerwak.listenstats.formatMs(29_999L) == "0 分钟", "29.9 秒应进到 0 分钟，实际 " + com.spwmods.waerwak.listenstats.formatMs(29_999L))
        check(com.spwmods.waerwak.listenstats.formatMs(30_000L) == "1 分钟", "30 秒应进位到 1 分钟")
        check(com.spwmods.waerwak.listenstats.formatMs(89_999L) == "1 分钟", "89.9 秒应显示 1 分钟")
        check(com.spwmods.waerwak.listenstats.formatMs(90_000L) == "2 分钟", "90 秒应进位到 2 分钟")
        check(com.spwmods.waerwak.listenstats.formatMsPrecise(149_000L) == "2 分 29 秒", "秒级明细：" + com.spwmods.waerwak.listenstats.formatMsPrecise(149_000L))
        check(com.spwmods.waerwak.listenstats.formatMsPrecise(59_400L) == "59 秒", "不足 1 分钟只显示秒")
    }

    test("备份 v2 带校验和、包含缺失记录，恢复后重置缺失检测") {
        val dir = freshEnv()
        val item = track("backup-v2", title = "备份测试")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 40 * SECOND)
        SessionTracker.flush()
        Thread.sleep(300L)
        MissingStore.attach(dir)
        MissingStore.setResult(setOf("hex-miss"), setOf("id-miss"), 1_700_000_000_000L)
        check(MissingStore.count() == 1, "准备：应有一条缺失记录")

        val backup = File(dir, "backup-v2.zip")
        val message = StatsStore.backupTo(backup)
        check(message.startsWith("已备份"), "备份应成功，实际：" + message)
        val manifest = StatsStore.inspectBackup(backup)
        check(manifest != null, "应有 manifest")
        check(manifest!!.contains("\"format\":\"spw-listen-stats-backup\""), "manifest 应有 format：" + manifest)
        check(manifest.contains("\"v\":2"), "manifest 应为 v2")
        check(manifest.contains("\"sha256\":\""), "manifest 应有 sha256")
        check(manifest.contains("missing.txt"), "includes 应包含 missing.txt")

        val restored = StatsStore.restoreFrom(backup)
        check(restored.startsWith("已恢复"), "恢复应成功，实际：" + restored)
        check(MissingStore.count() == 0, "恢复后缺失检测应重置，实际 " + MissingStore.count())
        val again = findTrack("backup-v2")
        check(again != null, "恢复后曲目应回来")
        checkNear(again!!.listenedMs, 40 * SECOND, 3 * SECOND, "恢复后时长")
    }

    test("备份被篡改时拒绝恢复且不改动原数据") {
        val dir = freshEnv()
        val item = track("tamper", title = "篡改前")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 40 * SECOND)
        SessionTracker.flush()
        Thread.sleep(300L)
        val before = findTrack("tamper")
        check(before != null, "准备：应有记录")

        val backup = File(dir, "original.zip")
        check(StatsStore.backupTo(backup).startsWith("已备份"), "备份应成功")

        // 复制 zip 并篡改 tracks.jsonl 内容
        val tampered = File(dir, "tampered.zip")
        val zin = java.util.zip.ZipFile(backup)
        val zos = java.util.zip.ZipOutputStream(tampered.outputStream())
        val entries = zin.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
            if (entry.name == "tracks.jsonl") {
                zos.write("{\"k\":\"hacked\"}\n".toByteArray(Charsets.UTF_8))
            } else {
                zos.write(zin.getInputStream(entry).readBytes())
            }
            zos.closeEntry()
        }
        zos.close()
        zin.close()

        val result = StatsStore.restoreFrom(tampered)
        check(result.startsWith("备份校验和不匹配") || result.startsWith("恢复失败"), "应拒绝被篡改的备份，实际：" + result)
        val after = findTrack("tamper")
        check(after != null, "原数据不应被改动")
        check(after!!.title == "篡改前", "原数据标题不应变化")
    }

    test("旧版（v1）备份仍可恢复") {
        val dir = freshEnv()
        val legacy = File(dir, "legacy.zip")
        val zos = java.util.zip.ZipOutputStream(legacy.outputStream())
        zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
        zos.write("{\"v\":1,\"exportedAt\":\"2026-10-01 00:00:00\",\"tracks\":1}".toByteArray(Charsets.UTF_8))
        zos.closeEntry()
        zos.putNextEntry(java.util.zip.ZipEntry("tracks.jsonl"))
        zos.write("{\"k\":\"legacykey\",\"id\":\"legacy-id\",\"ti\":\"旧备份\",\"ar\":\"歌手\",\"al\":\"专辑\",\"dur\":240000}\n".toByteArray(Charsets.UTF_8))
        zos.closeEntry()
        zos.close()
        val result = StatsStore.restoreFrom(legacy)
        check(result.startsWith("已恢复"), "旧备份应能恢复，实际：" + result)
        check(File(dir, "tracks.jsonl").isFile, "旧备份的曲目字典应被还原")
    }

    test("读取失败时保留上次数据并记录错误") {
        val dir = freshEnv()
        val item = track("loaderr", title = "读取测试")
        SessionTracker.onTrack(item)
        SessionTracker.onPlayingChanged(true)
        playFor(item, 0L, 40 * SECOND)
        SessionTracker.flush()
        Thread.sleep(300L)
        check(StatsStore.allTracks().isNotEmpty(), "准备：应有正常数据")

        // 塞一个非法 UTF-8 的聚合文件，让读取抛异常
        val daily = File(dir, "daily")
        daily.mkdirs()
        val broken = File(daily, "2099-01.jsonl")
        broken.writeText("{这不是合法的聚合行}\n", Charsets.UTF_8)
        StatsStore.resetForTesting(dir)
        check(StatsStore.allTracks().isNotEmpty(), "上次成功的数据应保留")
        check(StatsStore.loadError() != null, "应记录读取失败，实际 " + StatsStore.loadError())

        broken.delete()
        StatsStore.resetForTesting(dir)
        StatsStore.allTracks()
        check(StatsStore.loadError() == null, "修好后错误应消失，实际 " + StatsStore.loadError())
    }

    println()
    println("结果：通过 " + passed + " / 失败 " + failed)
    cleanup()
    if (failed > 0) kotlin.system.exitProcess(1)
}

private fun cleanup() {
    val dir = tempRoot ?: return
    try {
        val files = dir.listFiles()
        if (files != null) {
            var i = 0
            while (i < files.size) {
                files[i].deleteRecursively()
                i++
            }
        }
        dir.deleteRecursively()
    } catch (t: Throwable) {
        // 清理失败无所谓
    }
}
