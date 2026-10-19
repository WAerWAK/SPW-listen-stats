package com.spwmods.waerwak.listenstats.ui

import com.spwmods.waerwak.listenstats.stats.StatsStore
import java.io.File

/**
 * 面板 UI 状态的持久化（目前只有悬浮圆钮的位置）
 *
 * 存到插件数据目录的 ui-state.json，重启软件后仍能恢复拖动后的位置。
 * 只做最小 schema，避免引入序列化依赖。
 */
object UiStateStore {

    private const val FILE_NAME = "ui-state.json"

    @Volatile
    private var entryX: Float = 0f

    @Volatile
    private var entryY: Float = 0f

    @Volatile
    private var loaded = false

    /** 返回 (dx, dy)，相对右下角默认位置的偏移（单位：px） */
    fun loadEntryOffset(): Pair<Float, Float> {
        if (loaded) return entryX to entryY
        loaded = true
        try {
            val dir = StatsStore.dataDirOrNull()
            if (dir != null) {
                val file = File(dir, FILE_NAME)
                if (file.isFile) {
                    val text = file.readText()
                    val mx = Regex("\"entryX\"\\s*:\\s*(-?[0-9.eE+]+)").find(text)
                    val my = Regex("\"entryY\"\\s*:\\s*(-?[0-9.eE+]+)").find(text)
                    if (mx != null) mx.groupValues[1].toFloatOrNull()?.let { entryX = it }
                    if (my != null) my.groupValues[1].toFloatOrNull()?.let { entryY = it }
                }
            }
        } catch (t: Throwable) {
            // 读取失败就用默认位置
        }
        return entryX to entryY
    }

    fun saveEntryOffset(x: Float, y: Float) {
        entryX = x
        entryY = y
        try {
            val dir = StatsStore.dataDirOrNull() ?: return
            if (!dir.isDirectory) dir.mkdirs()
            File(dir, FILE_NAME).writeText("{\"entryX\":" + x + ",\"entryY\":" + y + "}")
        } catch (t: Throwable) {
            // 写入失败忽略，下次拖动会再试
        }
    }
}
