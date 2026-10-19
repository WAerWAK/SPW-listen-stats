@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.spwmods.waerwak.listenstats.stats.SessionTracker

/**
 * 插件主类（M0 骨架）
 *
 * 只做三件事：记录宿主探测信息、注册全局快捷键、保证面板随插件停用而关闭。
 * 不申请 CLASS_TRANSFORM，也不做任何 Hook。
 */
class ListenStatsPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    private var hotkey: AutoCloseable? = null

    override fun start() {
        HostProbe.attach(pluginContext)
        HostProbe.logStartup()
        registerHotkey()

        // 已授权 CLASS_TRANSFORM 时自动安装浮层（未授权则等用户手动触发）
        val injection = if (WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            OverlayInjection.install()
        } else {
            "未授权 CLASS_TRANSFORM（可在配置页手动安装）"
        }

        val hint = if (hotkey != null) {
            "听歌统计已启用（快捷键默认未绑定，可在快捷键设置中自定义）｜浮层：$injection"
        } else {
            "听歌统计已启用（无快捷键权限）：浮层：$injection"
        }
        toast(hint)
    }

    override fun stop() {
        try {
            hotkey?.close()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 注销快捷键失败: ${t.message}")
        }
        hotkey = null
        SessionTracker.flush()
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

    private fun toast(text: String) {
        try {
            WorkshopApi.ui.toast(text, WorkshopApi.Ui.ToastType.Success)
        } catch (t: Throwable) {
            System.err.println("[listen-stats] $text")
        }
    }
}
