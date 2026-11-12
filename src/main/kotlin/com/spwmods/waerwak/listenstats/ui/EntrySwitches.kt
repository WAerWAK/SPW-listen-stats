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
 * 入口开关（宿主配置页的三个 switch）
 *
 * 宿主配置页保存走的是它自己那份 ConfigHelper，插件拿不到"刚保存"的通知，
 * 所以这里用两条路一起保证"改完立刻生效"：
 *
 * 1. 宿主配置变更监听（有就即时刷新）
 * 2. 600 ms 轮询兜底：每轮 reload 一次 config.json（小文件，开销可忽略）
 *
 * 状态统一在宿主界面线程（EDT）更新：Compose 状态只能在那里写。
 * 开关只控制"是否绘制入口"，hook 本身常驻（不因开关反复注册/卸载）。
 *
 * 侧栏入口与标题栏按钮互斥：侧栏可用且开启时显示侧栏，否则回落到标题栏（都不行就靠配置页入口）。
 * 浮钮独立，不参与互斥。
 */
@OptIn(UnstableSpwWorkshopApi::class)
object EntrySwitches {

    /** 浮钮是否显示 */
    var floating by mutableStateOf(true)
        private set

    /** 配置里请求的标题栏按钮 */
    var titlebarRequested by mutableStateOf(true)
        private set

    /** 配置里请求的左侧栏入口 */
    var sidebarRequested by mutableStateOf(false)
        private set

    /** 标题栏入口 hook 是否已装上 */
    var titlebarReady by mutableStateOf(true)
        private set

    /** 侧栏入口 hook 是否已装上 */
    var sidebarReady by mutableStateOf(false)
        private set

    /**
     * 侧栏入口是否真的绘制出来了
     *
     * 互斥只能基于这个值：开关打开但宿主结构不支持/hook 没触发时，
     * 标题栏按钮必须继续显示，否则两个入口都没了。
     */
    var sidebarShown by mutableStateOf(false)
        private set

    /** 实际是否显示左侧栏入口 */
    val showSidebar: Boolean get() = sidebarReady && sidebarRequested

    /** 实际是否显示标题栏按钮（侧栏真的画出来了才让位） */
    val showTitlebar: Boolean get() = titlebarReady && titlebarRequested && !sidebarShown

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

    /** 侧栏入口装/卸后同步能力标志（EDT 调用） */
    fun markSidebarReady(ready: Boolean) {
        if (sidebarReady != ready) sidebarReady = ready
    }

    fun markTitlebarReady(ready: Boolean) {
        if (titlebarReady != ready) titlebarReady = ready
    }

    /** 侧栏行是否在组合中（由 SidebarEntry 驱动） */
    fun markSidebarShown(shown: Boolean) {
        if (sidebarShown != shown) sidebarShown = shown
    }

    /** 读一次配置并投递到 EDT（配置读取在调用线程：轮询线程/监听回调线程） */
    private fun refresh() {
        val nextFloating = PluginConfig.getBoolean(PluginConfig.SHOW_FLOATING_BUTTON, true)
        val nextTitlebar = PluginConfig.getBoolean(PluginConfig.SHOW_TITLEBAR_BUTTON, true)
        val nextSidebar = PluginConfig.getBoolean(PluginConfig.SHOW_SIDEBAR_ENTRY, false)
        val changed = floating != nextFloating || titlebarRequested != nextTitlebar ||
            sidebarRequested != nextSidebar
        if (changed) logChange(nextFloating, nextTitlebar, nextSidebar)
        EventQueue.invokeLater(object : Runnable {
            override fun run() {
                if (!alive) return
                if (floating != nextFloating) floating = nextFloating
                if (titlebarRequested != nextTitlebar) titlebarRequested = nextTitlebar
                if (sidebarRequested != nextSidebar) sidebarRequested = nextSidebar
            }
        })
    }

    /** 开关变化时往 hook-diag 记一行（排查“改了没反应”用，不刷屏） */
    private fun logChange(floatingOn: Boolean, titlebarOn: Boolean, sidebarOn: Boolean) {
        try {
            val dir = com.spwmods.waerwak.listenstats.stats.StatsStore.dataDirOrNull() ?: return
            com.spwmods.waerwak.listenstats.stats.LogFiles.append(
                File(dir, "hook-diag.txt"),
                "entry  floating=" + floatingOn + " titlebar=" + titlebarOn + " sidebar=" + sidebarOn + "\n"
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
            if (!PluginConfig.contains(PluginConfig.SHOW_FLOATING_BUTTON)) {
                PluginConfig.setBoolean(PluginConfig.SHOW_FLOATING_BUTTON, false)
            }
            // 迁移只做一次：把旧值置回 true，否则下次启动会又把用户后来改的新值覆盖掉
            UiStateStore.saveEntryEnabled(true)
        } catch (t: Throwable) {
            // 迁移失败按默认值处理
        }
    }
}
