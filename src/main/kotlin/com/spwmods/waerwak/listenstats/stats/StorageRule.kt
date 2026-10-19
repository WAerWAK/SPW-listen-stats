@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spwmods.waerwak.listenstats.stats

import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper

/**
 * 存储策略配置
 *
 * 原始事件明细（events 目录下的每日文件，精确到次）只保留最近 N 天；
 * 聚合数据（daily 目录下的按月文件 + tracks.jsonl 曲目字典）永久保留。
 */
object StorageRule {

    private const val KEY_RAW_KEEP_DAYS = "rawEventKeepDays"

    const val LIMIT_MIN_DAYS = 7
    const val LIMIT_MAX_DAYS = 3650

    @Volatile
    var rawEventKeepDays: Int = 90
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
        if (loaded) return
        loaded = true
        try {
            val h = helper() ?: return
            rawEventKeepDays =
                ((h.get(KEY_RAW_KEEP_DAYS, 90) as? Number)?.toInt() ?: 90)
                    .coerceIn(LIMIT_MIN_DAYS, LIMIT_MAX_DAYS)
        } catch (t: Throwable) {
            // 读失败用默认值
        }
    }

    @Synchronized
    fun save(days: Int) {
        rawEventKeepDays = days.coerceIn(LIMIT_MIN_DAYS, LIMIT_MAX_DAYS)
        try {
            val h = helper() ?: return
            h.set(KEY_RAW_KEEP_DAYS, rawEventKeepDays)
            h.save()
        } catch (t: Throwable) {
            // 写失败保持内存值
        }
    }

    fun describe(): String {
        load()
        return rawEventKeepDays.toString() + " 天"
    }
}
