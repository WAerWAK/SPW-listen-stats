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
object HeatmapScale {

    /**
     * 时长 → 色阶档位
     *
     * 0 = 无收听；1..4 依次递增。
     * 与 GitHub 热力图一致：4 个有色档位按 25% 等分，
     * 即 75%–100% 的区间都算最高档，不会只有最高的那一天发热。
     */
    fun level(millis: Long, highMillis: Long): Int {
        if (millis <= 0L) return 0
        if (highMillis <= 0L) return 4
        val scaled = millis * 4L
        return when {
            scaled < highMillis -> 1
            scaled < highMillis * 2L -> 2
            scaled < highMillis * 3L -> 3
            else -> 4
        }
    }
}
