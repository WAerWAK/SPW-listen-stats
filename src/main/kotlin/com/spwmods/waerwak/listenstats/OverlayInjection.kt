@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.TrackAgg
import com.spwmods.waerwak.listenstats.ui.UiStateStore
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏 Overlay + 边缘浮动入口
 *
 * Hook 目标（SPW 1.19.0 已 javap 实测）：
 * - `AppNavigationKt.AppNavigation(Composer, int)` 是顶层导航入口，
 *   唯一调用点在混淆类 `androidx.compose.ui.\u06C4`（invokestatic 实测）。
 * - 之前的 `access$Content` 在导航框架**内部**（仅覆盖内容区）；
 *   迁到 `AppNavigation` 后覆盖整个窗口（含侧栏与顶栏），
 *   这是背景材质方案（隐藏宿主内容 + 透明背景）的前提。
 */
object OverlayInjection {

    private const val TARGET_CLASS = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"
    private const val TARGET_METHOD = "AppNavigation"
    private const val TARGET_IN_CLASS = "androidx.compose.ui.\u06C4"

    @Volatile
    private var handle: HookHandle? = null

    /** replaceComposable 的材质桥接层句柄（可选，失败静默降级） */
    @Volatile
    private var materialHandle: HookHandle? = null

    /** 面板开关状态（提到 object 级，快捷键才能切换） */
    val panelOpen = mutableStateOf(false)

    /** 内容最大宽度百分比（50–100，默认 80）：信息页可调，改后立即生效 */
    val contentPercent = mutableStateOf(80)

    /** 全局字体增加值（百分比，0–60，默认 0）：所有字号同比例放大 */
    val fontExtraPercent = mutableStateOf(0)

    /**
     * 悬浮圆钮入口是否显示
     *
     * 配置页的「悬浮按钮-卸载」只隐藏圆钮，**不**卸载注入本身：
     * 顶栏按钮、导航感知、面板都保留，否则面板就再也唤不出来了。
     */
    val entryEnabled = mutableStateOf(true)

    /**
     * 关闭最上层弹层（阈值编辑 / 曲目详情 / 当天明细 / 日历浮窗）。
     * 由 [StatsOverlay] 注册；返回 true 表示确实关了一个弹层。
     */
    @Volatile
    var dismissTopLayer: (() -> Boolean)? = null

    /** 关闭面板（统一入口，导航/返回/浮钮共用） */
    fun closePanel() {
        panelOpen.value = false
    }

    /**
     * 面板内容区在窗口内的几何范围（像素）。
     * 用于判断鼠标点击是否落在面板外侧（左侧导航栏 / 标题栏），点击外侧时自动关闭面板。
     */
    @Volatile
    var contentBoundsInWindow: java.awt.Rectangle? = null

    /** 配置页请求打开面板时指定的初始页（-1 表示不指定） */
    val pendingTab = mutableStateOf(-1)

    /** 快捷键入口：没有面板就装一下，有则开关 */
    fun togglePanel() {
        if (handle == null) {
            val message = install()
            if (handle != null) panelOpen.value = true
            toast(message, handle != null)
            return
        }
        panelOpen.value = !panelOpen.value
    }

    @Volatile
    var lastError: String? = null
        private set

    fun installed(): Boolean = handle != null

    @JvmStatic
    @JvmName("installOverlayFromConfig")
    fun installOverlayFromConfig() {
        val wasInstalled = handle != null
        val message = install()
        entryEnabled.value = true
        try {
            com.spwmods.waerwak.listenstats.ui.UiStateStore.saveEntryEnabled(true)
        } catch (t: Throwable) {
            // 持久化失败不影响本次生效
        }
        if (wasInstalled) {
            toast("悬浮按钮已恢复显示", true)
        } else {
            toast(message, message.startsWith("已注册"))
        }
    }

    @JvmStatic
    @JvmName("uninstallOverlayFromConfig")
    fun uninstallOverlayFromConfig() {
        // 只隐藏圆钮：注入、顶栏按钮、导航感知全部保留，面板仍可由顶栏按钮打开
        entryEnabled.value = false
        try {
            com.spwmods.waerwak.listenstats.ui.UiStateStore.saveEntryEnabled(false)
        } catch (t: Throwable) {
            // 持久化失败不影响本次生效
        }
        if (handle == null) {
            toast("悬浮按钮已隐藏（当前未注入，可在需要时重新安装）", true)
        } else {
            toast("悬浮按钮已隐藏，顶栏按钮仍可打开统计页", true)
        }
    }

    /** 配置页：安装顶栏按钮 */
    @JvmStatic
    @JvmName("installTitlebarFromConfig")
    fun installTitlebarFromConfig() {
        val ok = com.spwmods.waerwak.listenstats.ui.TitlebarButton.install()
        if (ok) {
            toast("顶栏按钮已安装（标题栏 Quick 左侧）", true)
        } else {
            toast("安装失败：" + (com.spwmods.waerwak.listenstats.ui.TitlebarButton.lastError ?: "未知原因"), false)
        }
    }

    /** 配置页：卸载顶栏按钮 */
    @JvmStatic
    @JvmName("uninstallTitlebarFromConfig")
    fun uninstallTitlebarFromConfig() {
        try {
            com.spwmods.waerwak.listenstats.ui.TitlebarButton.uninstall()
            toast("顶栏按钮已卸载", true)
        } catch (t: Throwable) {
            toast("卸载失败：" + (t.message ?: t.javaClass.simpleName), false)
        }
    }

    /** 配置页入口：打开统计面板并直接定位到「信息」页 */
    @JvmStatic
    @JvmName("openStatsPanelFromConfig")
    fun openStatsPanelFromConfig() {
        if (handle == null) install()
        if (handle == null) {
            toast("打开失败：" + (lastError ?: "未知原因"), false)
            return
        }
        pendingTab.value = 3
        panelOpen.value = true
        toast("已打开统计面板，可在「信息 → 采集」调整计数门槛", true)
    }

    fun install(): String {
        if (handle != null) return "已注册（无需重复）"
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            lastError = "未授予 CLASS_TRANSFORM"
            return "失败：未授予 CLASS_TRANSFORM 权限"
        }
        return try {
            handle = WorkshopApi.hookRegistrar.afterComposable(
                className = TARGET_CLASS,
                methodName = TARGET_METHOD,
                inClass = TARGET_IN_CLASS
            ) { _: UiHookCall ->
                OverlayHost()
            }
            // 读取 UI 状态（内容宽度百分比等）
            contentPercent.value = UiStateStore.loadContentPercent()
            fontExtraPercent.value = UiStateStore.loadFontExtraPercent()
            entryEnabled.value = UiStateStore.loadEntryEnabled()
            // 导航感知：切页时关面板，返回键优先关弹层/面板（失败不影响主功能）
            com.spwmods.waerwak.listenstats.ui.NavigationHook.install(
                onPageChange = {
                    if (panelOpen.value) closePanel()
                },
                onBack = {
                    val dismiss = dismissTopLayer
                    if (dismiss != null && dismiss()) {
                        true
                    } else if (panelOpen.value) {
                        closePanel()
                        true
                    } else {
                        false
                    }
                }
            )
            // 标题栏入口（失败静默降级，浮钮仍在）
            com.spwmods.waerwak.listenstats.ui.TitlebarButton.install()
            // 背景材质桥接层（可选）：替换宿主 AppNavigation 调用，面板打开时隐藏宿主内容
            // 失败则静默降级为现有截图/不透明背景
            try {
                materialHandle = WorkshopApi.hookRegistrar.replaceComposable(
                    className = TARGET_CLASS,
                    methodName = TARGET_METHOD,
                    inClass = TARGET_IN_CLASS
                ) { _: UiHookCall ->
                    com.spwmods.waerwak.listenstats.ui.HostMaterialLayer()
                }
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 材质桥接层注册失败（降级为不透明背景）: ${t.message}")
                materialHandle = null
            }
            lastError = null
            "已注册：窗口右下角会出现一个「只露一点」的圆"
        } catch (t: Throwable) {
            // 回滚：handle 已赋值时如果只记录错误，installed() 会一直是 true，
            // 之后 install() 直接返回「已注册」，导航感知 / 标题栏按钮再也补装不上
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            try {
                handle?.unhook()
            } catch (e: Throwable) {
                // 忽略
            }
            handle = null
            try {
                materialHandle?.unhook()
            } catch (e: Throwable) {
                // 忽略
            }
            materialHandle = null
            try {
                com.spwmods.waerwak.listenstats.ui.NavigationHook.uninstall()
            } catch (e: Throwable) {
                // 忽略
            }
            try {
                com.spwmods.waerwak.listenstats.ui.TitlebarButton.uninstall()
            } catch (e: Throwable) {
                // 忽略
            }
            "失败：${t.javaClass.simpleName} — ${t.message ?: ""}"
        }
    }

    fun uninstall() {
        try {
            com.spwmods.waerwak.listenstats.ui.NavigationHook.uninstall()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 卸载导航 hook 失败: ${t.message}")
        }
        try {
            com.spwmods.waerwak.listenstats.ui.TitlebarButton.uninstall()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 卸载标题栏按钮失败: ${t.message}")
        }
        try {
            materialHandle?.unhook()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 卸载材质桥接层失败: ${t.message}")
        }
        materialHandle = null
        com.spwmods.waerwak.listenstats.ui.HostMaterialState.bridgeAvailable = false
        com.spwmods.waerwak.listenstats.ui.HostMaterialState.hidden = false
        try {
            handle?.unhook()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 卸载 Overlay 失败: ${t.message}")
        }
        handle = null
    }

    private fun toast(text: String, ok: Boolean) {
        try {
            WorkshopApi.ui.toast(
                text,
                if (ok) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Error
            )
        } catch (t: Throwable) {
            System.err.println("[listen-stats] $text")
        }
    }
}



/** 配置页按钮入口 */
object StatsActions {

    @JvmStatic
    @JvmName("resetStats")
    fun resetStats() {
        toast(StatsStore.reset(), true)
    }


    /** 弹出对话框：备份全部数据 */
    @JvmStatic
    @JvmName("backupDataDialog")
    fun backupDataDialog() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        javax.swing.SwingUtilities.invokeLater {
            try {
                val fd = java.awt.FileDialog(null as java.awt.Frame?, "备份听歌统计数据", java.awt.FileDialog.SAVE)
                fd.file = "listen-stats-backup-" + stamp + ".zip"
                fd.isVisible = true
                val dir = fd.directory
                val name = fd.file
                if (dir != null && name != null) {
                    val result = StatsStore.backupTo(java.io.File(dir, name))
                    toast(result, result.startsWith("已备份"))
                }
            } catch (t: Throwable) {
                toast("备份失败：" + t.message, false)
            }
        }
    }

    /** 弹出对话框：从备份恢复（先展示备份内容摘要，确认后才覆盖） */
    @JvmStatic
    @JvmName("restoreDataDialog")
    fun restoreDataDialog() {
        javax.swing.SwingUtilities.invokeLater {
            try {
                val fd = java.awt.FileDialog(null as java.awt.Frame?, "选择备份文件", java.awt.FileDialog.LOAD)
                fd.isVisible = true
                val dir = fd.directory
                val name = fd.file
                if (dir == null || name == null) return@invokeLater
                val file = java.io.File(dir, name)

                // 读 manifest 摘要：让用户知道这个备份里有什么（旧版备份没有 manifest）
                val manifest = try {
                    StatsStore.inspectBackup(file)
                } catch (t: Throwable) {
                    null
                }
                val summary = if (manifest == null) {
                    "备份文件：" + file.name + "\n（旧版备份，无内容摘要）"
                } else {
                    val tracks = com.spwmods.waerwak.listenstats.stats.JsonLite.long(manifest, "tracks")
                    val days = com.spwmods.waerwak.listenstats.stats.JsonLite.long(manifest, "dailyRows")
                    val events = com.spwmods.waerwak.listenstats.stats.JsonLite.long(manifest, "events")
                    val at = com.spwmods.waerwak.listenstats.stats.JsonLite.string(manifest, "exportedAt", "—")
                    val first = com.spwmods.waerwak.listenstats.stats.JsonLite.string(manifest, "firstDay", "")
                    val last = com.spwmods.waerwak.listenstats.stats.JsonLite.string(manifest, "lastDay", "")
                    val span = if (first.isBlank() && last.isBlank()) "—" else first + " ~ " + last
                    "备份文件：" + file.name + "\n" +
                        "导出时间：" + at + "\n" +
                        "曲目字典：" + tracks + " 条\n" +
                        "日聚合行：" + days + " 行\n" +
                        "播放明细：" + events + " 条\n" +
                        "日期范围：" + span
                }
                val message = summary + "\n\n" +
                    "恢复会用备份内容替换当前统计：\n" +
                    "当前数据会改名为 .bak 保留（不删除）。\n\n确认恢复吗？"
                val choice = javax.swing.JOptionPane.showConfirmDialog(
                    null, message, "恢复听歌统计",
                    javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE
                )
                if (choice != javax.swing.JOptionPane.YES_OPTION) return@invokeLater

                val result = StatsStore.restoreFrom(file)
                toast(result, result.startsWith("已恢复"))
            } catch (t: Throwable) {
                toast("恢复失败：" + t.message, false)
            }
        }
    }

    /** 弹出系统保存对话框，让用户自选导出位置 */
    @JvmStatic
    @JvmName("exportCsvDialog")
    fun exportCsvDialog() {
        val tracks = StatsStore.allTracks()
        if (tracks.isEmpty()) {
            toast("没有数据可导出", false)
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        javax.swing.SwingUtilities.invokeLater {
            try {
                val fd = java.awt.FileDialog(
                    null as java.awt.Frame?,
                    "导出 CSV",
                    java.awt.FileDialog.SAVE
                )
                fd.file = "listen-stats-" + stamp + ".csv"
                fd.isVisible = true
                val dir = fd.directory
                val name = fd.file
                if (dir != null && name != null) {
                    writeCsv(java.io.File(dir, name), tracks)
                }
            } catch (t: Throwable) {
                toast("导出失败：" + t.message, false)
            }
        }
    }

    private fun writeCsv(file: java.io.File, tracks: List<TrackAgg>) {
        val sb = StringBuilder(4096)
        sb.append('\uFEFF')
        sb.append("曲目,艺术家,专辑,次数,累计分钟,跳过次数,首次播放,最近播放,路径\n")
        var i = 0
        while (i < tracks.size) {
            val t = tracks[i]
            sb.append(csv(t.title)).append(',')
            sb.append(csv(t.artist)).append(',')
            sb.append(csv(t.album)).append(',')
            sb.append(t.count).append(',')
            sb.append(t.listenedMs / 60_000L).append(',')
            sb.append(t.skipCount).append(',')
            sb.append(timeOf(t.firstPlayed)).append(',')
            sb.append(timeOf(t.lastPlayed)).append(',')
            sb.append(csv(if (t.path.isBlank()) t.id else t.path)).append('\n')
            i++
        }
        try {
            java.nio.file.Files.write(file.toPath(), sb.toString().toByteArray(Charsets.UTF_8))
            toast("已导出：" + file.absolutePath, true)
        } catch (t: Throwable) {
            toast("导出失败：" + t.message, false)
        }
    }

    private fun csv(raw: String): String {
        if (raw.indexOf(',') < 0 && raw.indexOf('"') < 0 && raw.indexOf('\n') < 0) return raw
        return "\"" + raw.replace("\"", "\"\"") + "\""
    }

    private fun timeOf(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        return fmt.format(Date(timestamp))
    }

    private fun toast(text: String, ok: Boolean) {
        try {
            WorkshopApi.ui.toast(
                text,
                if (ok) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Warning
            )
        } catch (t: Throwable) {
            System.err.println("[listen-stats] $text")
        }
    }
}

// ---------------------------------------------------------------- UI



/**
 * 弹窗外点击关闭（外层）
 *
 * 注意：`Modifier.onPointerEvent` **不会**因为事件已被子节点消费就跳过回调，
 * 必须在回调里自己检查 `isConsumed`。否则弹窗内点按钮（翻页 / 步进 / 保存）
 * 会被当成「点在弹窗外面」，把弹窗直接关掉。
 */
internal fun Modifier.dismissOnOutsidePress(onDismiss: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        var consumed = false
        var i = 0
        while (i < event.changes.size) {
            if (event.changes[i].isConsumed) consumed = true
            i++
        }
        if (!consumed) onDismiss()
    }



/** 弹窗本体：把按下标记为已消费，让外层知道这次点在弹窗里 */
internal fun Modifier.consumePress(): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        var i = 0
        while (i < event.changes.size) {
            event.changes[i].consume()
            i++
        }
    }


