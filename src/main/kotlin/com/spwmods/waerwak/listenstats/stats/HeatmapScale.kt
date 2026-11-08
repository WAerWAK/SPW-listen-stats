package com.spwmods.waerwak.listenstats.stats

/**
 * 热力图色阶
 *
 * 按「当天累计收听时长」分级，而不是按播放次数：
 * 听 3 首 10 分钟的歌和听 30 首短歌，前者才该更"热"。
 *
 * 阈值不固定：由调用方传入**当前显示范围内收听时长最高的那一天**作为 `high`，
 * 这样无论用户每天听 5 分钟还是 5 小时，热力图都能用满 4 档深浅。
 * 这里只做纯计算，不改变任何已记录的数据。
 */
/**
 * 热力图色阶基准
 *
 * @property p25/p50/p75 三档边界（近 12 个月非零收听日的四分位）
 * @property fallback true = 样本不足 30 天，退化为"最大值 25%/50%/75% 等距"
 * @property sampleDays 参与计算的天数（信息页/图例可显示）
 */
data class HeatmapBaseline(
    val p25: Long,
    val p50: Long,
    val p75: Long,
    val fallback: Boolean,
    val sampleDays: Int
)

object HeatmapScale {

    /**
     * 时长 → 色阶档位
     *
     * 0 = 无收听；1..4 依次递增。基准是**近 12 个月**的四分位，
     * 所以切换年份时同一时长的颜色不会变，跨年也能比较。
     */
    fun level(millis: Long, baseline: HeatmapBaseline): Int {
        if (millis <= 0L) return 0
        if (baseline.p75 <= 0L) return 4
        return when {
            millis < baseline.p25 -> 1
            millis < baseline.p50 -> 2
            millis < baseline.p75 -> 3
            else -> 4
        }
    }

    /** 图例用：四档的下界（第一档为 0） */
    fun legendBounds(baseline: HeatmapBaseline): LongArray {
        return longArrayOf(baseline.p25, baseline.p50, baseline.p75)
    }
}
