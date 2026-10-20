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

    /** 磨砂背景模糊强度（dp） */
    @Volatile
    private var backdropBlurDp: Int = 48

    /** 内容最大宽度占屏幕宽度的百分比（50–100） */
    @Volatile
    private var contentPercent: Int = 80

    /** 悬浮圆钮入口是否显示 */
    @Volatile
    private var entryEnabled: Boolean = true

    @Volatile
    private var loaded = false

    /** 返回 (dx, dy)，相对右下角默认位置的偏移（单位：px） */
    fun loadEntryOffset(): Pair<Float, Float> {
        ensureLoaded()
        return entryX to entryY
    }

    /** 磨砂背景模糊强度（dp）；未设置时为 48 */
    fun loadBackdropBlur(): Int {
        ensureLoaded()
        return backdropBlurDp
    }

    fun saveBackdropBlur(dp: Int) {
        backdropBlurDp = dp
        writeAll()
    }

    /** 内容最大宽度百分比（未设置时为 80） */
    fun loadContentPercent(): Int {
        ensureLoaded()
        return contentPercent
    }

    fun saveContentPercent(percent: Int) {
        contentPercent = if (percent < 50) 50 else if (percent > 100) 100 else percent
        writeAll()
    }

    /** 悬浮圆钮是否显示（未设置时为 true） */
    fun loadEntryEnabled(): Boolean {
        ensureLoaded()
        return entryEnabled
    }

    fun saveEntryEnabled(enabled: Boolean) {
        entryEnabled = enabled
        writeAll()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val dir = StatsStore.dataDirOrNull()
            if (dir != null) {
                val file = File(dir, FILE_NAME)
                if (file.isFile) {
                    val text = file.readText()
                    val mx = Regex("\"entryX\"\\s*:\\s*(-?[0-9.eE+]+)").find(text)
                    val my = Regex("\"entryY\"\\s*:\\s*(-?[0-9.eE+]+)").find(text)
                    val mb = Regex("\"blurDp\"\\s*:\\s*([0-9]+)").find(text)
                    val mc = Regex("\"contentPercent\"\\s*:\\s*([0-9]+)").find(text)
                    if (mx != null) mx.groupValues[1].toFloatOrNull()?.let { entryX = it }
                    if (my != null) my.groupValues[1].toFloatOrNull()?.let { entryY = it }
                    if (mb != null) mb.groupValues[1].toIntOrNull()?.let { backdropBlurDp = it }
                    if (mc != null) {
                        val v = mc.groupValues[1].toIntOrNull()
                        if (v != null && v >= 50 && v <= 100) contentPercent = v
                    }
                    val me = Regex("\"entryEnabled\"\\s*:\\s*(true|false)").find(text)
                    if (me != null) entryEnabled = me.groupValues[1] == "true"
                }
            }
        } catch (t: Throwable) {
            // 读取失败就用默认值
        }
    }

    fun saveEntryOffset(x: Float, y: Float) {
        entryX = x
        entryY = y
        writeAll()
    }

    private fun writeAll() {
        try {
            val dir = StatsStore.dataDirOrNull() ?: return
            if (!dir.isDirectory) dir.mkdirs()
            File(dir, FILE_NAME).writeText(
                "{\"entryX\":" + entryX + ",\"entryY\":" + entryY +
                    ",\"blurDp\":" + backdropBlurDp + ",\"contentPercent\":" + contentPercent +
                    ",\"entryEnabled\":" + entryEnabled + "}"
            )
        } catch (t: Throwable) {
            // 写入失败忽略，下次修改会再试
        }
    }
}
