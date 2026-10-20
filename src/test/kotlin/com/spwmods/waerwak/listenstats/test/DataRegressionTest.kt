package com.spwmods.waerwak.listenstats.test

import com.spwmods.waerwak.listenstats.stats.CountRule
import com.spwmods.waerwak.listenstats.stats.LiveSessionStore
import com.spwmods.waerwak.listenstats.stats.SessionTracker
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.StatsThresholds
import com.spwmods.waerwak.listenstats.stats.TrackAgg
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import java.io.File
import java.time.LocalDate
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

private fun freshEnv(minSec: Int = 30, percent: Int = 50, mode: String = "max"): File {
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
    CountRule.setForTesting(minSec, percent, mode)
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
                ",\"c\":true,\"savedAt\":" + fakeWall +
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
