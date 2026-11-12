@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spwmods.waerwak.listenstats.stats

import com.spwmods.waerwak.listenstats.stats.PluginConfig

/**
 * 存储策略配置
 *
 * 原始事件明细（events 目录下的每日文件，精确到次）只保留最近 N 天；
 * 聚合数据（daily 目录下的按月文件 + tracks.jsonl 曲目字典）永久保留。
 *
 * 1.5.1.3 起 config.json 的读写统一走 [PluginConfig]（先 reload 再改再存）。
 */
object StorageRule {

    private const val KEY_RAW_KEEP_DAYS = "rawEventKeepDays"

    const val LIMIT_MIN_DAYS = 7
    const val LIMIT_MAX_DAYS = 3650

    @Volatile
    var rawEventKeepDays: Int = 90
        private set

    /**
     * 读取配置
     *
     * 每次都 reload：宿主配置页的控件可能用另一份 ConfigHelper 缓存保存，
     * 不 reload 会一直读到启动时的旧值（为以后加 edittext 预留）。
     */
    @Synchronized
    fun load() {
        if (!PluginConfig.isAvailable()) return
        rawEventKeepDays = PluginConfig.getInt(KEY_RAW_KEEP_DAYS, 90, LIMIT_MIN_DAYS, LIMIT_MAX_DAYS)
    }

    @Synchronized
    fun save(days: Int) {
        rawEventKeepDays = days.coerceIn(LIMIT_MIN_DAYS, LIMIT_MAX_DAYS)
        PluginConfig.edit { h -> h.set(KEY_RAW_KEEP_DAYS, rawEventKeepDays) }
    }

    fun describe(): String {
        load()
        return rawEventKeepDays.toString() + " 天"
    }
}
