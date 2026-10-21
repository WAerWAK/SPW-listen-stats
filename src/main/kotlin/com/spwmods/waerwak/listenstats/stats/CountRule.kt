@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spwmods.waerwak.listenstats.stats

import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper

/**
 * 计数门槛规则（可在插件配置页 / 统计面板信息页调整）
 *
 * 规则：门槛 = 最少秒数 与 曲目时长 × 百分比 之间取「较大」或「较小」。
 * 三个参数存在插件的 config.json 里，宿主配置页与面板共用同一份。
 */
object CountRule {

    private const val KEY_MIN_SEC = "countMinSeconds"
    private const val KEY_PERCENT = "countPercent"
    private const val KEY_MODE = "countMode" // "max" / "min"

    const val LIMIT_MIN_SEC = 1
    const val LIMIT_MAX_SEC = 600

    @Volatile
    var minSeconds: Int = 5
        private set

    @Volatile
    var percent: Int = 50
        private set

    @Volatile
    var mode: String = "max"
        private set

    @Volatile
    private var loaded = false

    private fun helper(): ConfigHelper? = try {
        WorkshopApi.manager.createConfigManager().getConfig("config.json")
    } catch (t: Throwable) {
        null
    }

    @Synchronized
    fun load() {
        loaded = true
        try {
            val h = helper() ?: return
            // 每次 reload：宿主配置页的控件可能用另一份 ConfigHelper 缓存保存，
            // 不 reload 会读到启动时的旧值（为以后加 edittext 预留）。
            h.reload()
            minSeconds = ((h.get(KEY_MIN_SEC, 5) as? Number)?.toInt() ?: 5).coerceIn(LIMIT_MIN_SEC, LIMIT_MAX_SEC)
            percent = ((h.get(KEY_PERCENT, 50) as? Number)?.toInt() ?: 50).coerceIn(0, 100)
            mode = if ((h.get(KEY_MODE, "max") as? String) == "min") "min" else "max"
        } catch (t: Throwable) {
            // 读失败就用默认值
        }
    }

    @Synchronized
    fun save(newMinSec: Int, newPercent: Int, newMode: String) {
        minSeconds = newMinSec.coerceIn(LIMIT_MIN_SEC, LIMIT_MAX_SEC)
        percent = newPercent.coerceIn(0, 100)
        mode = if (newMode == "min") "min" else "max"
        try {
            val h = helper() ?: return
            h.set(KEY_MIN_SEC, minSeconds)
            h.set(KEY_PERCENT, percent)
            h.set(KEY_MODE, mode)
            h.save()
        } catch (t: Throwable) {
            // 写失败保持内存值，下次再试
        }
    }

    /** 直接设定门槛（仅测试用；会跳过 config.json 读取） */
    @Synchronized
    internal fun setForTesting(newMinSec: Int, newPercent: Int, newMode: String) {
        minSeconds = newMinSec.coerceIn(LIMIT_MIN_SEC, LIMIT_MAX_SEC)
        percent = newPercent.coerceIn(0, 100)
        mode = if (newMode == "min") "min" else "max"
        loaded = true
    }

    /** 计数门槛（毫秒） */
    fun thresholdMs(durationMs: Long): Long {
        load()
        val bySeconds = minSeconds.toLong() * 1000L
        if (durationMs <= 0L) return bySeconds
        val byPercent = durationMs * percent.toLong() / 100L
        if (mode == "min") {
            return if (byPercent < bySeconds) byPercent else bySeconds
        }
        return if (byPercent > bySeconds) byPercent else bySeconds
    }

    /** 当前规则的文字描述 */
    fun describe(): String {
        load()
        val modeText = if (mode == "min") "取较小" else "取较大"
        return minSeconds.toString() + " 秒 或 " + percent + "%，" + modeText
    }
}
