package com.spwmods.waerwak.listenstats.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 活跃度页「时长分布」的时间范围预设
 *
 * 取代原来的「日/周/月/年 + 日期导航」：预设更贴近"我想看最近多久"的直觉，
 * 而柱子的粒度由范围长度自动决定（1 天→按小时，一个月内→按天，半年内→按周，更长→按月）。
 */
enum class RangePreset(val label: String) {
    Today("今天"),
    Last7("近 7 天"),
    Last30("近 30 天"),
    ThisMonth("本月"),
    ThisYear("本年"),
    Custom("自定义…")
}

/** 图表的一根柱子 */
class Bucket(
    /** X 轴标签（会抽稀显示） */
    val label: String,
    /** 悬停标题（完整描述） */
    val title: String,
    val ms: Long,
    val count: Int
)

object RangeBuckets {

    /** 预设对应的起止日期（含首尾） */
    fun rangeOf(
        preset: RangePreset,
        today: LocalDate,
        customFrom: LocalDate,
        customTo: LocalDate
    ): Pair<LocalDate, LocalDate> {
        val from: LocalDate
        val to: LocalDate
        when (preset) {
            RangePreset.Today -> {
                from = today
                to = today
            }
            RangePreset.Last7 -> {
                from = today.minusDays(6)
                to = today
            }
            RangePreset.Last30 -> {
                from = today.minusDays(29)
                to = today
            }
            RangePreset.ThisMonth -> {
                from = today.withDayOfMonth(1)
                to = if (today < from) from else today
            }
            RangePreset.ThisYear -> {
                from = today.withDayOfYear(1)
                to = if (today < from) from else today
            }
            else -> {
                var f = customFrom
                var t = customTo
                if (t.isBefore(f)) {
                    val tmp = f
                    f = t
                    t = tmp
                }
                from = f
                to = t
            }
        }
        return Pair(from, to)
    }

    /** 范围天数（含首尾） */
    fun dayCount(from: LocalDate, to: LocalDate): Int {
        val n = ChronoUnit.DAYS.between(from, to).toInt() + 1
        return if (n < 1) 1 else n
    }

    /** 自动粒度：1 天按小时，31 天内按天，180 天内按周，更长按月 */
    fun granularityLabel(from: LocalDate, to: LocalDate): String {
        val days = dayCount(from, to)
        return when {
            days <= 1 -> "按小时"
            days <= 31 -> "按天"
            days <= 180 -> "按周"
            else -> "按月"
        }
    }

    /**
     * 生成柱状图分桶
     *
     * @param hourly 单日时的小时聚合（由 StatsStore.hourProfile 取得）；多日时传 null
     * @param daily  日期 → 当日聚合（用于多日分桶）
     */
    fun build(
        from: LocalDate,
        to: LocalDate,
        hourly: HourProfile?,
        daily: Map<String, DayAgg>
    ): List<Bucket> {
        val days = dayCount(from, to)
        val out = ArrayList<Bucket>(32)
        if (days <= 1 && hourly != null) {
            var h = 0
            while (h < 24) {
                out.add(
                    Bucket(
                        label = h.toString(),
                        title = h.toString() + " 点",
                        ms = hourly.ms[h],
                        count = hourly.counts[h]
                    )
                )
                h++
            }
            return out
        }
        if (days <= 31) {
            var d = from
            while (!d.isAfter(to)) {
                val agg = daily[d.toString()]
                out.add(
                    Bucket(
                        label = (d.monthValue).toString() + "-" + d.dayOfMonth,
                        title = d.toString(),
                        ms = if (agg == null) 0L else agg.listenedMs,
                        count = if (agg == null) 0 else agg.count
                    )
                )
                d = d.plusDays(1)
            }
            return out
        }
        if (days <= 180) {
            // 按周：以周一为起点
            var cursor = from.minusDays((from.dayOfWeek.value - 1).toLong())
            while (cursor.isBefore(to) || cursor == to) {
                var ms = 0L
                var count = 0
                var d = 0
                while (d < 7) {
                    val day = cursor.plusDays(d.toLong())
                    if (!day.isBefore(from) && !day.isAfter(to)) {
                        val agg = daily[day.toString()]
                        if (agg != null) {
                            ms += agg.listenedMs
                            count += agg.count
                        }
                    }
                    d++
                }
                val weekEnd = cursor.plusDays(6)
                out.add(
                    Bucket(
                        label = cursor.monthValue.toString() + "-" + cursor.dayOfMonth,
                        title = cursor.toString() + " ~ " + weekEnd.toString(),
                        ms = ms,
                        count = count
                    )
                )
                cursor = cursor.plusDays(7)
            }
            return out
        }
        // 按月
        var month = from.withDayOfMonth(1)
        val lastMonth = to.withDayOfMonth(1)
        while (!month.isAfter(lastMonth)) {
            var ms = 0L
            var count = 0
            var d = month
            val monthEnd = month.plusMonths(1).minusDays(1)
            while (!d.isAfter(monthEnd)) {
                if (!d.isBefore(from) && !d.isAfter(to)) {
                    val agg = daily[d.toString()]
                    if (agg != null) {
                        ms += agg.listenedMs
                        count += agg.count
                    }
                }
                d = d.plusDays(1)
            }
            out.add(
                Bucket(
                    label = month.monthValue.toString() + " 月",
                    title = month.year.toString() + " 年 " + month.monthValue + " 月",
                    ms = ms,
                    count = count
                )
            )
            month = month.plusMonths(1)
        }
        return out
    }

    /** X 轴最多显示几个标签（随柱子数抽稀） */
    fun axisLabelStep(bucketCount: Int): Int {
        if (bucketCount <= 8) return 1
        if (bucketCount <= 16) return 2
        if (bucketCount <= 32) return 5
        if (bucketCount <= 60) return 10
        return (bucketCount / 8).coerceAtLeast(1)
    }

    /** 范围描述文字（标题右侧的小字） */
    fun describe(from: LocalDate, to: LocalDate): String {
        if (from == to) return from.toString()
        return from.toString() + " ~ " + to.toString()
    }

    /** 每周起始周一（供 UI 判断用） */
    fun mondayOf(date: LocalDate): LocalDate {
        return date.minusDays((date.dayOfWeek.value - 1).toLong())
    }

    /** 周末（周日） */
    fun sundayOf(date: LocalDate): LocalDate {
        val offset = 7 - date.dayOfWeek.value
        return date.plusDays(offset.toLong())
    }

    /** 首日星期（用于图例抽稀，目前未使用但保留以避免未来重复实现） */
    fun weekdayOf(date: LocalDate): DayOfWeek = date.dayOfWeek
}
