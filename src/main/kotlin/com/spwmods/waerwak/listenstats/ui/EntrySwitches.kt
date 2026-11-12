package com.spwmods.waerwak.listenstats.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spwmods.waerwak.listenstats.stats.PluginConfig
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.awt.EventQueue
import java.io.File
import java.util.function.Consumer

/**
 * 入口开关（宿主配置页的两个 switch）
 *
 * 宿主配置页保存走的是它自己那份 ConfigHelper，插件拿不到"刚保存"的通知，
 * 所以这里用两条路一起保证"改完立刻生效"：
 *
 * 1. 宿主配置变更监听（有就即时刷新）
 * 2. 600 ms 轮询兜底：每轮 reload 一次 config.json（小文件，开销可忽略）
 *
 * 状态统一在宿主界面线程（EDT）更新：Compose 状态只能在那里写。
 * 开关只控制"是否绘制入口"，hook 本身常驻（不因开关反复注册/卸载）。
 */
@OptIn(UnstableSpwWorkshopApi::class)
object EntrySwitches {

    /** 浮钮是否显示 */
    var floating by mutableStateOf(true)
        private set

    /** 标题栏按钮是否显示 */
    var titlebar by mutableStateOf(true)
        private set

    @Volatile
    private var alive = false

    private var thread: Thread? = null

    private var listener: Consumer<ConfigHelper>? = null

    fun start() {
        stop()
        migrateLegacyEntryState()
        alive = true
        refresh()
        registerListener()
        val t = Thread({
            while (alive) {
                try {
                    Thread.sleep(600L)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (!alive) return@Thread
                refresh()
            }
        }, "listen-stats-entry-switches")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        alive = false
        val t = thread
        thread = null
        if (t != null) t.interrupt()
        unregisterListener()
    }

    /** 读一次配置并投递到 EDT（配置读取在调用线程：轮询线程/监听回调线程） */
    private fun refresh() {
        val nextFloating = PluginConfig.getBoolean(PluginConfig.SHOW_FLOATING_BUTTON, true)
        val nextTitlebar = PluginConfig.getBoolean(PluginConfig.SHOW_TITLEBAR_BUTTON, true)
        val changed = floating != nextFloating || titlebar != nextTitlebar
        if (changed) logChange(nextFloating, nextTitlebar)
        EventQueue.invokeLater(object : Runnable {
            override fun run() {
                if (!alive) return
                if (floating != nextFloating) floating = nextFloating
                if (titlebar != nextTitlebar) titlebar = nextTitlebar
            }
        })
    }

    /** 开关变化时往 hook-diag 记一行（排查“改了没反应”用，不刷屏） */
    private fun logChange(floatingOn: Boolean, titlebarOn: Boolean) {
        try {
            val dir = com.spwmods.waerwak.listenstats.stats.StatsStore.dataDirOrNull() ?: return
            com.spwmods.waerwak.listenstats.stats.LogFiles.append(
                File(dir, "hook-diag.txt"),
                "entry  floating=" + floatingOn + " titlebar=" + titlebarOn + "\n"
            )
        } catch (t: Throwable) {
            // 诊断写不进去不影响功能
        }
    }

    private fun registerListener() {
        val consumer = object : Consumer<ConfigHelper> {
            override fun accept(value: ConfigHelper) {
                if (alive) refresh()
            }
        }
        listener = consumer
        try {
            WorkshopApi.manager.createConfigManager()
                .addConfigChangeListener(PluginConfig.FILE_NAME, consumer)
        } catch (t: Throwable) {
            // 监听器不可用不算错：还有轮询兜底
            System.err.println("[listen-stats] 配置变更监听不可用（改用轮询）: " + t.message)
            listener = null
        }
    }

    private fun unregisterListener() {
        val consumer = listener
        listener = null
        if (consumer == null) return
        try {
            WorkshopApi.manager.createConfigManager().removeConfigChangeListener(consumer)
        } catch (t: Throwable) {
            // 卸载失败不影响退出
        }
    }

    /**
     * 旧版「悬浮按钮-卸载」的值（ui-state.json 的 entryEnabled）一次性迁移到 config.json
     *
     * 只在 config 里还没有这个键时写：用户后来在配置页动过就以配置页为准。
     */
    private fun migrateLegacyEntryState() {
        try {
            if (UiStateStore.loadEntryEnabled()) return
            if (PluginConfig.contains(PluginConfig.SHOW_FLOATING_BUTTON)) return
            PluginConfig.setBoolean(PluginConfig.SHOW_FLOATING_BUTTON, false)
        } catch (t: Throwable) {
            // 迁移失败按默认值处理
        }
    }
}
