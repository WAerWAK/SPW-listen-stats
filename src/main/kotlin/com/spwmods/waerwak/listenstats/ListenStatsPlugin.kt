@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.spwmods.waerwak.listenstats.stats.SessionTracker

/**
 * 插件主类
 *
 * start()：记录宿主探测信息 → 补记上次未结算的会话 → 注册全局快捷键 →
 * 安装面板注入（含导航 hook、标题栏按钮、材质桥接层）。
 * stop()：结算当前这一遍，并卸载全部 hook。
 */
class ListenStatsPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    private var hotkey: AutoCloseable? = null

    override fun start() {
        SessionTracker.resetDiagnostics()
        HostProbe.attach(pluginContext)
        // 备份 manifest 里记录插件版本（排查备份来源用）
        com.spwmods.waerwak.listenstats.stats.StatsStore.pluginVersionHint = pluginContext.pluginVersion
        HostProbe.logStartup()

        // 逐帧计时诊断开关（默认关，信息页可开；只在内存里记环形缓冲）
        try {
            com.spwmods.waerwak.listenstats.stats.TimingDiag.setEnabled(
                com.spwmods.waerwak.listenstats.ui.UiStateStore.loadTimingDiag()
            )
        } catch (t: Throwable) {
            // 读取失败保持默认关闭
        }

        // 播放事件处理线程：曲目解析、计时、快照写盘都在这里，宿主回调只入队
        com.spwmods.waerwak.listenstats.stats.PlaybackPipeline.start()

        // 后台预热统计缓存：首次打开面板不再同步解析 daily/ 与排序
        val warm = Thread({
            try {
                com.spwmods.waerwak.listenstats.stats.StatsStore.prefetch()
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 预热失败: " + t.message)
            }
        }, "listen-stats-prefetch")
        warm.isDaemon = true
        warm.start()

        registerHotkey()

        // 已授权 CLASS_TRANSFORM 时自动安装浮层（未授权则等用户手动触发）
        // 后台安装：首次要扫描宿主字节码，不能卡住插件启动
        if (WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            OverlayInjection.installAsync()
        }
    }

    override fun stop() {
        // 第一步：让在途的宿主扫描/界面准备立即作废（否则扫完还会把 hook 注册进去）
        OverlayInjection.cancelPending()
        try {
            hotkey?.close()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 注销快捷键失败: ${t.message}")
        }
        hotkey = null
        // 先停播放处理线程（排空队列并结算当前这一遍），再排空写线程
        com.spwmods.waerwak.listenstats.stats.PlaybackPipeline.stop()
        SessionTracker.flush()
        SessionTracker.shutdown()
        com.spwmods.waerwak.listenstats.stats.StatsStore.shutdown()
        OverlayInjection.uninstall()
    }

    private fun registerHotkey() {
        try {
            if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) return
            hotkey = WorkshopApi.manager.keyBindingManager.register(
                actionId = "open-stats-panel",
                title = "打开听歌统计面板",
                // 默认不绑定键位，用户可在 SPW 的快捷键设置里自定义
                defaultShortcut = null,
                hasGlobal = true
            ) { OverlayInjection.togglePanel() }
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 注册快捷键失败: ${t.message}")
        }
    }
}
