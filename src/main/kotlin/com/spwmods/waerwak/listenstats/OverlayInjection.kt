@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spwmods.waerwak.listenstats.stats.AggregatePage
import com.spwmods.waerwak.listenstats.stats.DayAgg
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.TrackAgg
import com.spwmods.waerwak.listenstats.stats.TrackPage
import com.spwmods.waerwak.listenstats.stats.StatsThresholds
import com.spwmods.waerwak.listenstats.stats.CountRule
import com.spwmods.waerwak.listenstats.stats.StorageRule
import com.spwmods.waerwak.listenstats.stats.HeatmapScale
import com.spwmods.waerwak.listenstats.stats.DayTrackAgg
import com.spwmods.waerwak.listenstats.stats.PlayEvent
import com.spwmods.waerwak.listenstats.stats.Bucket
import com.spwmods.waerwak.listenstats.stats.RangeBuckets
import com.spwmods.waerwak.listenstats.stats.RangePreset
import com.spwmods.waerwak.listenstats.ui.HostAppearance
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import com.spwmods.waerwak.listenstats.ui.UiStateStore
import com.spwmods.waerwak.listenstats.ui.HostFont
import com.spwmods.waerwak.listenstats.ui.rememberCover
import com.spwmods.waerwak.listenstats.ui.saltPalette
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.text.SimpleDateFormat
import java.time.LocalDate
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
            lastError = "${t.javaClass.simpleName}: ${t.message}"
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


    @JvmStatic
    @JvmName("exportCsv")
    fun exportCsv() {
        val dir = StatsStore.dataDirOrNull()
        if (dir == null) {
            toast("没有数据目录", false)
            return
        }
        val tracks = StatsStore.allTracks()
        if (tracks.isEmpty()) {
            toast("没有数据可导出", false)
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = java.io.File(dir, "listen-stats-" + stamp + ".csv")
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
            toast("已导出 CSV：" + file.absolutePath, true)
        } catch (t: Throwable) {
            toast("导出失败：" + t.message, false)
        }
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

private const val PAGE_SIZE = 12
private val TABS = arrayOf("概览", "排行", "活跃度", "信息")

@Composable
private fun OverlayHost() {
    var hovered by remember { mutableStateOf(false) }
    val opened = OverlayInjection.panelOpen.value

    // 关闭面板（含浮钮状态复位）
    val closePanel: () -> Unit = {
        OverlayInjection.panelOpen.value = false
        hovered = false
    }

    // 面板外部关闭时也复位悬停态（Navigator hook 与 AWT 监听两条路径都走这里）
    LaunchedEffect(opened) {
        if (!opened) hovered = false
    }

    // 面板打开时：点击内容区之外（左侧导航栏等）自动关闭面板。
    // 首选的精确感知由 [NavigationHook] 完成，这里是 hook 不可用时的兜底。
    DisposableEffect(opened) {
        if (!opened) {
            return@DisposableEffect onDispose { }
        }
        val listener = java.awt.event.AWTEventListener { event ->
            if (event is java.awt.event.MouseEvent &&
                event.id == java.awt.event.MouseEvent.MOUSE_PRESSED &&
                event.button == java.awt.event.MouseEvent.BUTTON1
            ) {
                val bounds = OverlayInjection.contentBoundsInWindow
                val window = com.spwmods.waerwak.listenstats.ui.HostWindow.find()
                if (bounds != null && window != null) {
                    val origin = window.locationOnScreen
                    val wx = event.xOnScreen - origin.x
                    val wy = event.yOnScreen - origin.y
                    // 顶部标题栏（含左上角应用图标/收起按钮、右上角 Quick/头像/全屏等）不触发关闭
                    val inTitlebar = wy < bounds.y
                    val outside = wx < bounds.x ||
                        wx > bounds.x + bounds.width ||
                        wy > bounds.y + bounds.height
                    if (outside && !inTitlebar) {
                        closePanel()
                    }
                }
            }
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                listener,
                java.awt.AWTEvent.MOUSE_EVENT_MASK
            )
        } catch (t: Throwable) {
            // 无 GUI 环境或权限不足：忽略，退化为只能手动关闭
        }
        onDispose {
            try {
                java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            } catch (t: Throwable) {
                // 忽略
            }
        }
    }

    // 同步宿主的深浅色与字体
    val dark = HostAppearance.isDarkTheme()
    // 材质模式（桥接层生效 + DWM 材质）：卡片/控件用半透明覆盖层，让窗口材质透出
    val materialActive = com.spwmods.waerwak.listenstats.ui.HostMaterialState.bridgeAvailable &&
        HostAppearance.isMaterialStyle()
    val palette = saltPalette(dark, materialActive)
    val hostFont = remember { HostFont.family() }

    // 全局字体增加值：通过覆盖 Density.fontScale 实现（sp 变大，dp 不变）
    val fontExtra = OverlayInjection.fontExtraPercent.value
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, fontExtra) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * (1f + fontExtra / 100f)
        )
    }

    // 每次打开面板：清空封面失败冷却，并隐藏宿主内容（背景材质桥接层）
    LaunchedEffect(opened) {
        if (opened) {
            com.spwmods.waerwak.listenstats.ui.CoverCache.resetFailures()
            com.spwmods.waerwak.listenstats.ui.HostMaterialState.hidden = true
        } else {
            com.spwmods.waerwak.listenstats.ui.HostMaterialState.hidden = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = palette.highlight)) {
        CompositionLocalProvider(
            LocalSaltPalette provides palette,
            LocalTextStyle provides androidx.compose.ui.text.TextStyle(fontFamily = hostFont),
            // 字体大小设置：只改 fontScale，dp 布局不受影响
            androidx.compose.ui.platform.LocalDensity provides scaledDensity
        ) {
            // 无论面板开关，都记录一次 overlay 覆盖的内容区矩形：
            // 用于判断鼠标点击是否落在面板外侧（左侧导航栏 / 标题栏 / 播放条）
            Box(
                Modifier.fillMaxSize().onGloballyPositioned { coords ->
                    val origin = coords.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                    val sz = coords.size
                    if (sz.width > 0 && sz.height > 0) {
                        OverlayInjection.contentBoundsInWindow = java.awt.Rectangle(
                            origin.x.toInt(),
                            origin.y.toInt(),
                            sz.width,
                            sz.height
                        )
                    }
                }
            ) {
                if (opened) {
                    StatsOverlay(onClose = closePanel)
                } else {
                    // ❗ 关键：未打开时不能有任何不透明背景，否则会盖住宿主页面
                    Box(Modifier.fillMaxSize()) {
                        if (OverlayInjection.entryEnabled.value) {
                            EdgeEntry(
                                hovered = hovered,
                                onHoverChange = { hovered = it },
                                onClick = {
                                    OverlayInjection.panelOpen.value = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 可拖动的边缘圆钮：默认在右下角，hover 滑出，按住可拖到任意位置 */
@Composable
private fun EdgeEntry(hovered: Boolean, onHoverChange: (Boolean) -> Unit, onClick: () -> Unit) {
    var dragOffset by remember {
        val saved = UiStateStore.loadEntryOffset()
        mutableStateOf(Offset(saved.first, saved.second))
    }
    var dragging by remember { mutableStateOf(false) }
    var dragDistance by remember { mutableStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val btnPx = with(density) { 52.dp.toPx() }
    val baseGapPx = with(density) { 74.dp.toPx() }
    val edgePx = with(density) { 24.dp.toPx() }

    // 按钮当前近似位置（相对容器左上角）
    val curLeft = containerSize.width - btnPx + dragOffset.x
    val curTop = containerSize.height - btnPx - baseGapPx + dragOffset.y
    val nearLeft = containerSize.width > 0 && curLeft < edgePx
    val nearRight = containerSize.width > 0 && curLeft > containerSize.width - edgePx - btnPx
    val nearTop = containerSize.height > 0 && curTop < edgePx
    val nearBottom = containerSize.height > 0 && curTop > containerSize.height - edgePx - btnPx
    // 靠近哪边就往哪边隐藏；在中间则完全静止
    val shown = hovered || dragging
    val targetX = when {
        shown -> 0.dp
        nearRight -> 32.dp
        nearLeft -> (-32).dp
        else -> 0.dp
    }
    val targetY = when {
        shown -> 0.dp
        nearBottom -> 32.dp
        nearTop -> (-32).dp
        else -> 0.dp
    }
    val shiftX by animateDpAsState(
        targetValue = targetX,
        animationSpec = tween(durationMillis = 220),
        label = "edge-x"
    )
    val shiftY by animateDpAsState(
        targetValue = targetY,
        animationSpec = tween(durationMillis = 220),
        label = "edge-y"
    )
    val p = LocalSaltPalette.current

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { containerSize = it.size }) {
        Surface(
            onClick = {
                // 拖动过就不再当成点击（否则拖完会立刻打开面板）
                if (dragDistance > 8f) {
                    dragDistance = 0f
                } else {
                    dragDistance = 0f
                    onClick()
                }
            },
            shape = SaltDesign.circle,
            color = if (hovered || dragging) p.circleBackgroundStrong else p.circleBackground,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, SolidColor(if (hovered || dragging) p.highlight else p.circleBorder)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = shiftX, y = (-74).dp + shiftY)
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .size(52.dp)
                .onPointerEvent(PointerEventType.Press) {
                    dragging = true
                    dragDistance = 0f
                }
                .onPointerEvent(PointerEventType.Release) {
                    dragging = false
                    UiStateStore.saveEntryOffset(dragOffset.x, dragOffset.y)
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    if (dragging) {
                        val change = event.changes.firstOrNull()
                        if (change != null) {
                            val delta = change.position - change.previousPosition
                            dragDistance += kotlin.math.abs(delta.x) + kotlin.math.abs(delta.y)
                            val next = dragOffset + delta
                            // 限制在窗口内（按钮完整可见，不会被拖出屏幕）
                            if (containerSize.width > 0 && containerSize.height > 0) {
                                val baseLeft = containerSize.width - btnPx
                                val baseTop = containerSize.height - btnPx - baseGapPx
                                var nx = next.x
                                var ny = next.y
                                val left = baseLeft + nx
                                if (left < 0f) nx = -baseLeft
                                val maxLeft = containerSize.width - btnPx
                                if (left > maxLeft) nx = maxLeft - baseLeft
                                val top = baseTop + ny
                                if (top < 0f) ny = -baseTop
                                val maxTop = containerSize.height - btnPx
                                if (top > maxTop) ny = maxTop - baseTop
                                dragOffset = Offset(nx, ny)
                            } else {
                                dragOffset = next
                            }
                        }
                    }
                }
                .onPointerEvent(PointerEventType.Enter) { if (!dragging) onHoverChange(true) }
                .onPointerEvent(PointerEventType.Exit) { if (!dragging) onHoverChange(false) }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ChartGlyph(if (hovered || dragging) p.highlight else p.text)
            }
        }
    }
}

/** 自绘柱状图图标（emoji 彩色位图与 UI 风格不协调） */
@Composable
private fun ChartGlyph(color: Color) {
    Row(
        modifier = Modifier.size(20.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        GlyphBar(7.dp, color)
        GlyphBar(15.dp, color)
        GlyphBar(11.dp, color)
    }
}

@Composable
private fun GlyphBar(height: androidx.compose.ui.unit.Dp, color: Color) {
    Surface(
        modifier = Modifier.width(4.dp).height(height),
        shape = SaltDesign.shapeSmall,
        color = color
    ) {}
}

/** Esc 全局监听（AWT 层，不依赖 Compose 焦点） */
private object EscGate {

    @Volatile
    var handler: (() -> Unit)? = null

    private var dispatcher: java.awt.KeyEventDispatcher? = null

    fun install() {
        if (dispatcher != null) return
        val d = java.awt.KeyEventDispatcher { event ->
            if (event.id == java.awt.event.KeyEvent.KEY_PRESSED &&
                event.keyCode == java.awt.event.KeyEvent.VK_ESCAPE
            ) {
                val h = handler
                if (h != null) {
                    h.invoke()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        dispatcher = d
        try {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(d)
        } catch (t: Throwable) {
            dispatcher = null
        }
    }

    fun uninstall() {
        val d = dispatcher ?: return
        dispatcher = null
        try {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(d)
        } catch (t: Throwable) {
            // 忽略
        }
    }
}

@Composable
private fun StatsOverlay(onClose: () -> Unit) {
    var tab by remember {
        val requested = OverlayInjection.pendingTab.value
        OverlayInjection.pendingTab.value = -1
        mutableStateOf(if (requested >= 0) requested else 0)
    }
    var tick by remember { mutableStateOf(0) }
    // 实时刷新只驱动概览/排行/信息页；活跃度页（371 个热力格子）不跟随实时刷新，
    // 避免播放中每 1.5 秒重建整张热力图。
    var liveTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        var last = StatsStore.dataVersion
        while (true) {
            kotlinx.coroutines.delay(1500L)
            val current = StatsStore.dataVersion
            if (current != last) {
                last = current
                liveTick = liveTick + 1
            }
        }
    }
    val liveKey = tick * 100000 + liveTick
    var showThresholdEditor by remember { mutableStateOf(false) }
    var showRetentionEditor by remember { mutableStateOf(false) }
    var showFontSizeEditor by remember { mutableStateOf(false) }
    var dayDetailDate by remember { mutableStateOf("") }

    // Esc / 返回键：逐层关闭（弹层 → 面板）。
    // 三个入口（返回键、AWT 全局 Esc、Compose 焦点 Esc）共用同一份层级，
    // 避免某个弹层在某个入口漏掉 → 按 Esc 把整个面板关掉、弹层状态却还残留。
    val closeTopLayer: () -> Boolean = {
        var handled = true
        if (showThresholdEditor) {
            showThresholdEditor = false
        } else if (showRetentionEditor) {
            showRetentionEditor = false
        } else if (showFontSizeEditor) {
            showFontSizeEditor = false
        } else if (dayDetailDate.isNotEmpty()) {
            dayDetailDate = ""
        } else if (TrackDetailState.open.value != null) {
            TrackDetailState.open.value = null
        } else if (GroupDetailState.open.value.isNotEmpty()) {
            GroupDetailState.open.value = ""
        } else if (CalState.open.value) {
            CalState.open.value = false
        } else {
            handled = false
        }
        handled
    }

    OverlayInjection.dismissTopLayer = { closeTopLayer() }
    DisposableEffect(Unit) {
        onDispose { OverlayInjection.dismissTopLayer = null }
    }

    EscGate.handler = {
        if (!closeTopLayer()) onClose()
    }
    DisposableEffect(Unit) {
        EscGate.install()
        onDispose { EscGate.uninstall() }
    }

    // Esc：逐层关闭（弹层 → 面板）
    val escRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            escRequester.requestFocus()
        } catch (t: Throwable) {
            // 拿不到焦点时 Esc 不生效，不影响其他功能
        }
    }

    // 背景模式判定要提前到根 Surface 之前：根 Surface 的颜色会盖住内部所有背景绘制
    val bridgeAvailable = com.spwmods.waerwak.listenstats.ui.HostMaterialState.bridgeAvailable
    val materialStyle = HostAppearance.isMaterialStyle()
    val transparentRoot = bridgeAvailable && materialStyle
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (transparentRoot) Color.Transparent else LocalSaltPalette.current.background
    ) {
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { coords ->
                // 记录内容区在窗口内的范围：点击落在范围外（左侧导航栏等）时自动关闭面板
                val origin = coords.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                val sz = coords.size
                OverlayInjection.contentBoundsInWindow = java.awt.Rectangle(
                    origin.x.toInt(),
                    origin.y.toInt(),
                    sz.width,
                    sz.height
                )
            }
            .focusRequester(escRequester)
            .focusTarget()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) {
                    false
                } else {
                    if (!closeTopLayer()) onClose()
                    true
                }
            }
    ) {
    // 背景：材质样式 → 透明（让 DWM 亚克力/云母透出）；经典样式 → 纯色。截图方案已移除
    val bridgeOn = com.spwmods.waerwak.listenstats.ui.HostMaterialState.bridgeAvailable
    if (!(bridgeOn && materialStyle)) {
        val darkBg = LocalSaltPalette.current.isDark
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(if (darkBg) Color(0xFF232323) else Color(0xFFF8F8F8))
            }
        )
    }
    // 底部留 76dp：避开浮动胶囊迷你播放条（面板内容不被遮挡）
    Column(
        Modifier.fillMaxSize().padding(
            start = SaltDesign.padding,
            end = SaltDesign.padding,
            top = SaltDesign.padding,
            bottom = 87.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 内容宽度：按当前可用宽的比例（百分比可在信息页调整）
        val dLocal = LocalDensity.current
        val percent = OverlayInjection.contentPercent.value
        var availPx by remember { mutableStateOf(0f) }
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { availPx = it.size.width.toFloat() },
            contentAlignment = Alignment.TopCenter
        ) {
            val availDp = with(dLocal) { availPx.toDp() }
            // 百分比相对「当前内容区可用宽」，使 90% 与 100% 有明确差异
            val ratio = percent / 100f
            var contentW = availDp * ratio
            if (contentW > availDp) contentW = availDp
            if (contentW < 280.dp) contentW = if (availDp < 280.dp) availDp else 280.dp
            val sideGap = if (availDp > contentW) (availDp - contentW) / 2 else 0.dp
            Column(Modifier.width(contentW)) {
                HeaderBar(onClose = onClose, onRefresh = { tick = tick + 1 })
                Spacer(Modifier.height(12.dp))
                TabBar(selected = tab, onSelect = { tab = it })
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    ScrollArea(resetKey = tab, barRightExtra = sideGap) {
                        when (tab) {
                            0 -> OverviewTab(liveKey)
                            1 -> RankingTab(liveKey)
                            2 -> CalendarTab(tick, onSelectDay = { dayDetailDate = it })
                            else -> InfoTab(
                                liveKey,
                                onEditThreshold = { showThresholdEditor = true },
                                onEditRetention = { showRetentionEditor = true },
                                onEditFontSize = { showFontSizeEditor = true }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                FooterBar()
            }
        }
    }
    if (showThresholdEditor) {
        ThresholdEditor(
            onDismiss = {
                showThresholdEditor = false
                tick = tick + 1
            }
        )
    }
    if (showRetentionEditor) {
        RetentionEditor(
            onDismiss = {
                showRetentionEditor = false
                tick = tick + 1
            }
        )
    }
    if (showFontSizeEditor) {
        FontSizeEditor(
            onDismiss = {
                showFontSizeEditor = false
                tick = tick + 1
            }
        )
    }
    if (dayDetailDate.isNotEmpty()) {
        DayDetailDialog(date = dayDetailDate, onDismiss = { dayDetailDate = "" })
    }
    val detailKey = TrackDetailState.open.value
    if (detailKey != null) {
        TrackDetailDialog(trackKey = detailKey, onDismiss = { TrackDetailState.open.value = null })
    }
    val groupPayload = GroupDetailState.open.value
    if (groupPayload.isNotEmpty()) {
        GroupDetailDialog(payload = groupPayload, onDismiss = { GroupDetailState.open.value = "" })
    }
    if (CalState.open.value) {
        CalendarPicker(
            initial = CalState.seed.value,
            maxDate = LocalDate.now(),
            onPick = { date ->
                CalState.result.value = date
                CalState.open.value = false
            },
            onDismiss = { CalState.open.value = false }
        )
    }
    }
    }
}

@Composable
private fun HeaderBar(onClose: () -> Unit, onRefresh: () -> Unit) {
    val p = LocalSaltPalette.current
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onClose,
            shape = SaltDesign.circle,
            color = Color.Transparent,
            modifier = Modifier.size(30.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BackGlyph(p.text)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "听歌统计",
            fontSize = SaltDesign.fontSizeTitle,
            fontWeight = SaltDesign.weightTitle,
            color = p.text
        )
        Spacer(Modifier.width(10.dp))
        Text(
            HostProbe.pluginContext()?.pluginVersion ?: "dev",
            fontSize = SaltDesign.fontSizeSub,
            color = p.subText
        )
        Spacer(Modifier.weight(1f))
        AccentButton("刷新", onRefresh)
    }
}

/** 强调色实心按钮（主操作，更显眼） */
@Composable
private fun AccentButton(label: String, onClick: () -> Unit) {
    val p = LocalSaltPalette.current
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeMedium,
        color = p.highlight,
        shadowElevation = 4.dp,
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RefreshGlyph(p.onHighlight)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = SaltDesign.fontSizeMain, color = p.onHighlight)
        }
    }
}

/** 刷新图标：圆环 + 三角箭头（自绘） */
@Composable
private fun RefreshGlyph(color: Color) {
    Box(
        Modifier.size(14.dp).drawBehind {
            val s = size.minDimension
            val sw = s * 0.14f
            drawArc(
                color = color,
                startAngle = 55f,
                sweepAngle = 275f,
                useCenter = false,
                topLeft = Offset(sw / 2f, sw / 2f),
                size = Size(s - sw, s - sw),
                style = Stroke(width = sw)
            )
            val path = Path()
            path.moveTo(s * 0.60f, 0f)
            path.lineTo(s * 0.98f, s * 0.30f)
            path.lineTo(s * 0.54f, s * 0.38f)
            path.close()
            drawPath(path, color)
        }
    )
}

/** SPW 式返回箭头（自绘，避免依赖被裁剪的图标 API） */
@Composable
private fun BackGlyph(color: Color) {
    Box(
        Modifier.size(16.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.11f
            drawLine(color, Offset(s * 0.64f, s * 0.14f), Offset(s * 0.27f, s * 0.5f), strokeWidth = w)
            drawLine(color, Offset(s * 0.27f, s * 0.5f), Offset(s * 0.64f, s * 0.86f), strokeWidth = w)
        }
    )
}


/** SPW 外观选择器风格：图标 + 文字卡片，选中项强调色边框与文字 */
@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalSaltPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var i = 0
        while (i < TABS.size) {
            val index = i
            val active = index == selected
            Surface(
                onClick = { onSelect(index) },
                shape = SaltDesign.shapeMedium,
                color = if (active) p.subBackground else Color.Transparent,
                border = BorderStroke(
                    1.dp,
                    SolidColor(if (active) p.highlight else p.subText.copy(alpha = 0.18f))
                ),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    Modifier.height(50.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    TabIcon(index, if (active) p.highlight else p.subText)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        TABS[index],
                        fontSize = 20.sp,
                        color = if (active) p.highlight else p.subText
                    )
                }
            }
            i++
        }
    }
}

/** 自绘的 Tab 图标（0 概览 / 1 排行 / 2 活跃度 / 3 设置） */
@Composable
private fun TabIcon(kind: Int, color: Color) {
    Box(
        Modifier.size(20.dp).drawBehind {
            val s = size.minDimension
            val stroke = s * 0.1f
            when (kind) {
                0 -> {
                    // 四宫格
                    val c = s * 0.4f
                    val gap = s - c * 2
                    drawRect(color, Offset(0f, 0f), Size(c, c))
                    drawRect(color, Offset(c + gap, 0f), Size(c, c))
                    drawRect(color, Offset(0f, c + gap), Size(c, c))
                    drawRect(color, Offset(c + gap, c + gap), Size(c, c))
                }
                1 -> {
                    // 柱状图（排行）
                    val w = s * 0.2f
                    val gap = (s - w * 3) / 2f
                    drawRect(color, Offset(0f, s * 0.55f), Size(w, s * 0.45f))
                    drawRect(color, Offset(w + gap, s * 0.28f), Size(w, s * 0.72f))
                    drawRect(color, Offset((w + gap) * 2f, 0f), Size(w, s))
                }
                2 -> {
                    // 热力格 3×3
                    val c = s * 0.24f
                    val gap = (s - c * 3f) / 2f
                    var r = 0
                    while (r < 3) {
                        var col = 0
                        while (col < 3) {
                            drawRect(
                                color,
                                Offset(col * (c + gap), r * (c + gap)),
                                Size(c, c)
                            )
                            col++
                        }
                        r++
                    }
                }
                else -> {
                    // 信息：圆圈 + 中心点 + 竖线
                    val cx = s / 2f
                    drawCircle(
                        color,
                        radius = s * 0.42f,
                        center = Offset(cx, cx),
                        style = Stroke(width = stroke)
                    )
                    drawCircle(color, radius = s * 0.07f, center = Offset(cx, s * 0.34f))
                    drawRect(color, Offset(cx - s * 0.05f, s * 0.48f), Size(s * 0.1f, s * 0.24f))
                }
            }
        }
    )
}

// ---------------------------------------------------------------- Tab: 概览

@Composable
private fun OverviewTab(tick: Int) {
    val snapshot = remember(tick) { StatsStore.snapshot(10, 10) }
    val streak = remember(tick) { StatsStore.currentStreak() }
    val longest = remember(tick) { StatsStore.longestStreak() }
    // 累计收听的三态格式：0=时分 1=天时分 2=天数小数（点击循环）
    var durationMode by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        SaltGroup {
            GroupHeader("总览")
            Row(Modifier.fillMaxWidth()) {
                // 累计收听：点击循环切换三种格式
                KpiCellParts(
                    label = "累计收听",
                    parts = durationParts(snapshot.totalMs, durationMode),
                    modifier = Modifier.weight(1f),
                    onClick = { durationMode = (durationMode + 1) % 3 }
                )
                KpiCell("总播放次数", snapshot.totalCount.toString(), Modifier.weight(1f))
                KpiCell("曲目数", snapshot.trackCount.toString(), Modifier.weight(1f))
                KpiCellParts(
                    label = "连续收听",
                    parts = listOf(streak.toString() to "天"),
                    modifier = Modifier.weight(1f)
                )
                KpiCellParts(
                    label = "最长连续",
                    parts = listOf(longest.toString() to "天"),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SaltGroup {
                    GroupHeader("最近播放")
                    if (snapshot.recentTracks.isEmpty()) {
                        EmptyHint("暂无最近播放")
                    }
                    var i = 0
                    while (i < snapshot.recentTracks.size) {
                        if (i > 0) RowDivider()
                        val t = snapshot.recentTracks[i]
                        CompactListRow(
                            coverId = t.id,
                            title = t.title.ifBlank { "未知曲目" },
                            summary = t.artist,
                            value = formatLastPlayed(t.lastPlayed)
                        )
                        i++
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                SaltGroup {
                    GroupHeader("最常听 Top 10")
                    if (snapshot.topTracks.isEmpty()) {
                        EmptyHint("还没有计数记录")
                    }
                    var i = 0
                    while (i < snapshot.topTracks.size) {
                        if (i > 0) RowDivider()
                        val t = snapshot.topTracks[i]
                        CompactListRow(
                            coverId = t.id,
                            title = t.title.ifBlank { "未知曲目" },
                            summary = t.artist,
                            value = t.count.toString() + " 次"
                        )
                        i++
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // 听歌小结（三项，对齐 Jethuit）
        val peakDate = remember(tick) { StatsStore.peakDay() }
        val peakMs = remember(tick) { StatsStore.peakMillis() }
        val avgMs = remember(tick) { StatsStore.dailyAverageMillis() }
        val favHour = remember(tick) { StatsStore.favoriteHour() }
        SaltGroup {
            GroupHeader("听歌小结")
            Row(Modifier.fillMaxWidth()) {
                SummaryStat(
                    label = if (peakDate.isNotBlank()) "听得最多 · " + peakDate else "听得最多",
                    parts = if (peakMs > 0L) durationParts(peakMs, 0) else listOf("—" to ""),
                    modifier = Modifier.weight(1f)
                )
                SummaryStat(
                    label = "每日平均收听",
                    parts = if (avgMs > 0L) durationParts(avgMs, 0) else listOf("—" to ""),
                    modifier = Modifier.weight(1f)
                )
                SummaryStat(
                    label = "最常听歌时段",
                    // 数字 26sp，尾巴 :00 用 2/3 字号（与其它汉字单位一致）
                    parts = if (favHour >= 0) {
                        listOf(
                            pad2(favHour) to ":00 — ",
                            pad2((favHour + 1) % 24) to ":00"
                        )
                    } else {
                        listOf("—" to "")
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 补零到两位 */
private fun pad2(value: Int): String {
    // 手动补零：不经 String.format，避免宿主裁剪相关重载
    return if (value < 10) "0" + value.toString() else value.toString()
}

/**
 * 把时长拆成「数值 + 单位」片段，供数字/单位分级字号渲染
 *
 * mode 0 = 时/分（不足一小时只显示分）
 * mode 1 = 天/时/分
 * mode 2 = 天数小数（保留两位）
 *
 * 分钟四舍五入（满 30 秒进位）；显示层处理，不影响已记录数据。
 */
private fun durationParts(ms: Long, mode: Int): List<Pair<String, String>> {
    if (ms <= 0L) return listOf("0" to "分")
    val totalMinutes = ms / 60_000L + if (ms % 60_000L >= 30_000L) 1L else 0L
    if (mode == 2) {
        // 天数保留两位小数（用整数运算避免精度误差）
        val hundredths = (ms * 100L + 43_200_000L) / 86_400_000L
        val whole = hundredths / 100L
        val frac = hundredths % 100L
        val fracText = if (frac < 10L) "0" + frac.toString() else frac.toString()
        return listOf((whole.toString() + "." + fracText) to "天")
    }
    val days = totalMinutes / 1440L
    val hours = (totalMinutes % 1440L) / 60L
    val minutes = totalMinutes % 60L
    if (mode == 1) {
        // 「天」总是显示（不足一天也显示 0 天），与「时」保持一致的三段式
        val list = ArrayList<Pair<String, String>>(3)
        list.add(days.toString() to "天")
        list.add(hours.toString() to "时")
        if (minutes > 0L) list.add((if (minutes < 10) "0" + minutes else minutes.toString()) to "分")
        return list
    }
    if (hours > 0L) {
        val list = ArrayList<Pair<String, String>>(2)
        list.add(hours.toString() to "时")
        if (minutes > 0L) list.add((if (minutes < 10) "0" + minutes else minutes.toString()) to "分")
        return list
    }
    return listOf(minutes.toString() to "分")
}

/** 数值 + 单位分字号（单位取数字的一半），可选点击（仅内容区可点，不含标题） */
@Composable
private fun KpiCellParts(
    label: String,
    parts: List<Pair<String, String>>,
    modifier: Modifier,
    onClick: (() -> Unit)? = null
) {
    val p = LocalSaltPalette.current
    Column(modifier) {
        Text(label, fontSize = SaltDesign.fontSizeSub, color = p.labelAccent)
        Spacer(Modifier.height(6.dp))
        val row: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.Bottom) {
                var i = 0
                while (i < parts.size) {
                    val part = parts[i]
                    Text(
                        part.first,
                        fontSize = SaltDesign.fontSizeKpi,
                        fontWeight = SaltDesign.weightKpi,
                        color = p.highlight
                    )
                    if (part.second.isNotEmpty()) {
                        Spacer(Modifier.width(2.dp))
                        Text(
                            part.second,
                            fontSize = SaltDesign.fontSizeKpiUnit,
                            color = p.highlight,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    if (i < parts.size - 1) Spacer(Modifier.width(2.dp))
                    i++
                }
            }
        }
        if (onClick != null) {
            // 只让数值行可点；局部关闭 48dp 最小交互区，避免把宽度挤开
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp
            ) {
                Surface(
                    onClick = onClick,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = SaltDesign.shapeSmall
                ) {
                    row()
                }
            }
        } else {
            row()
        }
    }
}

/** 听歌小结单项（标签在上、数值在下；汉字单位取数字的 2/3） */
@Composable
private fun SummaryStat(
    label: String,
    parts: List<Pair<String, String>>,
    modifier: Modifier
) {
    val p = LocalSaltPalette.current
    Column(modifier) {
        Text(label, fontSize = SaltDesign.fontSizeSub, color = p.labelAccent)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            var i = 0
            while (i < parts.size) {
                val part = parts[i]
                Text(
                    part.first,
                    fontSize = SaltDesign.fontSizeKpi,
                    fontWeight = SaltDesign.weightKpi,
                    color = p.highlight
                )
                if (part.second.isNotEmpty()) {
                    Spacer(Modifier.width(1.dp))
                    Text(
                        part.second,
                        fontSize = SaltDesign.fontSizeKpiUnit,
                        color = p.highlight,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                i++
            }
        }
    }
}

/** SaltUI 分组的标题行：强调色竖条 + 标题 */
@Composable
private fun GroupHeader(title: String) {
    Row(
        Modifier.padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = SaltDesign.shapeInner,
            color = LocalSaltPalette.current.highlight,
            modifier = Modifier.size(width = 3.dp, height = 16.dp)
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            fontSize = SaltDesign.fontSizeSectionTitle,
            fontWeight = SaltDesign.weightSectionTitle,
            color = LocalSaltPalette.current.text
        )
    }
}

/** 紧凑版条目（窄列用）：封面 + 标题/副标题 + 右侧单值 */
@Composable
private fun CompactListRow(coverId: String, title: String, summary: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackCover(coverId, 30.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(
                title,
                fontSize = SaltDesign.fontSizeMain,
                lineHeight = 17.sp,
                color = LocalSaltPalette.current.text
            )
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    summary,
                    fontSize = SaltDesign.fontSizeSub,
                    lineHeight = 14.sp,
                    color = LocalSaltPalette.current.subText
                )
            }
        }
        Text(value, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
    }
}

@Composable
private fun KpiCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.labelAccent)
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            fontSize = SaltDesign.fontSizeKpi,
            fontWeight = SaltDesign.weightKpi,
            color = LocalSaltPalette.current.highlight
        )
    }
}

/** SaltUI 风格的圆角分组容器（RoundedColumn 的简化版） */
@Composable
private fun SaltGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SaltDesign.shapeLarge,
        color = LocalSaltPalette.current.subBackground
    ) {
        Column(
            Modifier.padding(horizontal = SaltDesign.padding, vertical = SaltDesign.subPadding),
            content = content
        )
    }
}

/** 行分隔线：SPW 原版无行间分隔线，只保留等高空白（1dp 占位避免行距跳动） */
@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp))
}

// ---------------------------------------------------------------- Tab: 排行（歌曲 / 专辑 / 艺术家 合并）

@Composable
private fun RankingTab(tick: Int) {
    var kind by remember { mutableStateOf(0) }
    // 0=次数 1=时长 2=最近
    var sortBy by remember { mutableStateOf(0) }
    var range by remember { mutableStateOf(0) }
    var songsPage by remember { mutableStateOf(0) }
    var albumsPage by remember { mutableStateOf(0) }
    var artistsPage by remember { mutableStateOf(0) }
    // 每页条数（10/15/20/30/50，默认 10）
    var pageSize by remember { mutableStateOf(10) }
    val searchState = remember { TextFieldState("") }
    val query = searchState.text.toString()
    val today = LocalDate.now()
    val sinceDate: String? = when (range) {
        1 -> today.minusDays(30).toString()
        2 -> today.minusDays(7).toString()
        else -> null
    }

    Column(Modifier.fillMaxWidth()) {
        // 上排：榜单切换（3/5）+ 搜索框（2/5）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(3f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RangeTab("总榜", range == 0, Modifier.weight(1f)) { range = 0; songsPage = 0; albumsPage = 0; artistsPage = 0 }
                RangeTab("月榜", range == 1, Modifier.weight(1f)) { range = 1; songsPage = 0; albumsPage = 0; artistsPage = 0 }
                RangeTab("周榜", range == 2, Modifier.weight(1f)) { range = 2; songsPage = 0; albumsPage = 0; artistsPage = 0 }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(2f)) {
                SearchField(searchState)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 下排：视图（2/5 靠左）+ 排序（2/5 靠右），中间留 1/5
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(2f)) {
                UnderlineTab("歌曲", kind == 0, Modifier.weight(1f), height = 32.dp) { kind = 0; songsPage = 0 }
                UnderlineTab("专辑", kind == 1, Modifier.weight(1f), height = 32.dp) { kind = 1; albumsPage = 0 }
                UnderlineTab("艺术家", kind == 2, Modifier.weight(1f), height = 32.dp) { kind = 2; artistsPage = 0 }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip("次数", sortBy == 0, Modifier.weight(1f)) { sortBy = 0; songsPage = 0; albumsPage = 0; artistsPage = 0 }
                SortChip("时长", sortBy == 1, Modifier.weight(1f)) { sortBy = 1; songsPage = 0; albumsPage = 0; artistsPage = 0 }
                SortChip("最近", sortBy == 2, Modifier.weight(1f)) { sortBy = 2; songsPage = 0; albumsPage = 0; artistsPage = 0 }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (kind) {
            0 -> TrackListView(songsPage, tick, sortBy, sinceDate, query, pageSize, { songsPage = it }) { pageSize = it }
            1 -> AggregateListView(true, albumsPage, tick, sortBy, sinceDate, query, pageSize, { albumsPage = it }) { pageSize = it }
            else -> AggregateListView(false, artistsPage, tick, sortBy, sinceDate, query, pageSize, { artistsPage = it }) { pageSize = it }
        }
    }
}

/** 搜索框（Material3 OutlinedTextField + 新版 TextFieldState，支持中文输入法） */
@Composable
private fun SearchField(state: TextFieldState) {
    val p = LocalSaltPalette.current
    Surface(shape = SaltDesign.shapeMedium, color = p.subBackground, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchGlyph(p.subText)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                state = state,
                modifier = Modifier.weight(1f),
                lineLimits = TextFieldLineLimits.SingleLine,
                contentPadding = PaddingValues(0.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.text
                ),
                placeholder = {
                    Text("搜索歌曲/歌手/专辑", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = p.highlight,
                    focusedTextColor = p.text,
                    unfocusedTextColor = p.text,
                    focusedPlaceholderColor = p.subText,
                    unfocusedPlaceholderColor = p.subText
                )
            )
        }
    }
}

/** 放大镜图标 */
@Composable
private fun SearchGlyph(color: Color) {
    Box(
        Modifier.size(13.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.12f
            drawCircle(
                color,
                radius = s * 0.30f,
                center = Offset(s * 0.42f, s * 0.42f),
                style = Stroke(width = w)
            )
            drawLine(color, Offset(s * 0.63f, s * 0.63f), Offset(s * 0.95f, s * 0.95f), strokeWidth = w)
        }
    )
}

/** 榜单切换按钮（Tab 卡片样式，无图标） */
@Composable
private fun RangeTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val p = LocalSaltPalette.current
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeMedium,
        color = if (selected) p.subBackground else Color.Transparent,
        border = BorderStroke(
            1.dp,
            SolidColor(if (selected) p.highlight else p.subText.copy(alpha = 0.18f))
        ),
        modifier = modifier
    ) {
        Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeMain,
                color = if (selected) p.highlight else p.subText
            )
        }
    }
}


@Composable
private fun TrackListView(
    page: Int,
    tick: Int,
    sortBy: Int,
    sinceDate: String?,
    query: String,
    pageSize: Int,
    onPage: (Int) -> Unit,
    onPageSize: (Int) -> Unit
) {
    val data = remember(page, tick, sortBy, sinceDate, query, pageSize) {
        StatsStore.trackPageRange(page, pageSize, sortBy, query, sinceDate)
    }
    Column(Modifier.fillMaxWidth()) {
        SaltGroup {
            ListHeader("曲目", "次数", "时长", "上次播放")
            if (data.items.isEmpty()) {
                EmptyHint("还没有数据：听几首歌再来")
            }
            var i = 0
            while (i < data.items.size) {
                if (i > 0) RowDivider()
                val t = data.items[i]
                ListRow(
                    rank = page * pageSize + i + 1,
                    coverId = t.id,
                    title = t.title.ifBlank { "未知曲目" },
                    summary = t.artist + (if (t.album.isBlank()) "" else " · " + t.album),
                    count = t.count.toString() + " 次",
                    duration = formatMs(t.listenedMs),
                    lastPlayed = formatLastPlayed(t.lastPlayed),
                    trackKey = t.key,
                    onClick = { key -> TrackDetailState.open.value = key }
                )
                i++
            }
        }
        Spacer(Modifier.height(10.dp))
        Pager(page = page, pageCount = data.pageCount, onPage = onPage, total = data.total,
            pageSize = pageSize, onPageSize = onPageSize)
    }
}

@Composable
private fun AggregateListView(
    byAlbum: Boolean,
    page: Int,
    tick: Int,
    sortBy: Int,
    sinceDate: String?,
    query: String,
    pageSize: Int,
    onPage: (Int) -> Unit,
    onPageSize: (Int) -> Unit
) {
    val data = remember(byAlbum, page, tick, sortBy, sinceDate, query, pageSize) {
        StatsStore.aggregatePageRange(page, pageSize, byAlbum, sinceDate, query, sortBy)
    }
    Column(Modifier.fillMaxSize()) {
        SaltGroup {
            ListHeader(if (byAlbum) "专辑" else "艺术家", "次数", "时长", "上次播放")
            if (data.items.isEmpty()) {
                EmptyHint(if (byAlbum) "还没有专辑数据" else "还没有艺术家数据")
            }
            var i = 0
            while (i < data.items.size) {
                if (i > 0) RowDivider()
                val row = data.items[i]
                ListRow(
                    rank = page * pageSize + i + 1,
                    coverId = row.coverId,
                    title = row.name,
                    summary = if (row.owner.isNotEmpty()) {
                        row.owner + " · " + row.trackCount + " 首曲目"
                    } else {
                        row.trackCount.toString() + " 首曲目"
                    },
                    count = row.count.toString() + " 次",
                    duration = formatMs(row.listenedMs),
                    lastPlayed = formatLastPlayed(row.lastPlayed),
                    trackKey = "",
                    onClick = { },
                    onRowClick = {
                        GroupDetailState.open.value = (if (byAlbum) "album" else "artist") +
                            "|" + row.name + "|" + row.owner
                    }
                )
                i++
            }
        }
        Spacer(Modifier.height(10.dp))
        Pager(page = page, pageCount = data.pageCount, onPage = onPage, total = data.total,
            pageSize = pageSize, onPageSize = onPageSize)
    }
}

// ---------------------------------------------------------------- Tab: 日历

@Composable
private fun CalendarTab(tick: Int, onSelectDay: (String) -> Unit) {
    // 近一年：53 周 × 7 天，列首周一、列尾周日
    val days = remember(tick) { StatsStore.recentDays(400) }
    val map = HashMap<String, DayAgg>()
    var i = 0
    while (i < days.size) {
        map[days[i].date] = days[i]
        i++
    }

    // 时长分布状态
    var preset by remember { mutableStateOf(RangePreset.Today) }
    var customFrom by remember { mutableStateOf(LocalDate.now().minusDays(29)) }
    var customTo by remember { mutableStateOf(LocalDate.now()) }
    var byTime by remember { mutableStateOf(true) }
    var chartType by remember { mutableStateOf(0) } // 0=柱状 1=折线 2=曲线

    val pickResult = CalState.result.value
    LaunchedEffect(pickResult) {
        if (pickResult != null) {
            if (CalState.pickTarget.value == 1) {
                customTo = pickResult
            } else {
                customFrom = pickResult
            }
            CalState.result.value = null
            CalState.pickTarget.value = 0
            preset = RangePreset.Custom
        }
    }

    var contentWidthPx by remember { mutableStateOf(0) }
    val dLocal = LocalDensity.current
    // 两个组件与内容同宽（内容本身已限制在屏幕宽度的可配置百分比内）
    val targetWidth = if (contentWidthPx > 0) {
        with(dLocal) { contentWidthPx.toDp() }
    } else {
        0.dp
    }
    // 设计宽：仅用于信息页展示（格子尺寸现在按实际内容宽计算）

    Column(
        Modifier.fillMaxSize().onGloballyPositioned { contentWidthPx = it.size.width },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ActivityHeatmap(
            daily = map,
            tick = tick,
            width = targetWidth,
            onSelectDay = onSelectDay
        )
        Spacer(Modifier.height(26.dp))
        if (targetWidth > 0.dp) {
            DurationChart(
                width = targetWidth,
                tick = tick,
                preset = preset,
                customFrom = customFrom,
                customTo = customTo,
                byTime = byTime,
                chartType = chartType,
                daily = map,
                onPreset = { preset = it },
                onPickFrom = {
                    CalState.pickTarget.value = 0
                    CalState.seed.value = customFrom
                    CalState.open.value = true
                },
                onPickTo = {
                    CalState.pickTarget.value = 1
                    CalState.seed.value = customTo
                    CalState.open.value = true
                },
                onByTime = { byTime = it },
                onChartType = { chartType = it }
            )
        }
    }
}

/** 年份下拉项 */
private class YearOption(val value: Int, val label: String)

/** 热力图：悬浮定位准确 + 描边高亮 + 跟随鼠标的提示卡片 + 年份切换 */
@Composable
private fun ActivityHeatmap(
    daily: Map<String, DayAgg>,
    tick: Int,
    width: androidx.compose.ui.unit.Dp,
    onSelectDay: (String) -> Unit
) {
    val p = LocalSaltPalette.current
    val dLocal = LocalDensity.current
    val today = LocalDate.now()

    // -1 = 近一年；否则是具体年份
    var yearMode by remember { mutableStateOf(-1) }
    var yearPickerOpen by remember { mutableStateOf(false) }
    var containerPx by remember { mutableStateOf(0f) }
    var hoverKey by remember { mutableStateOf("") }
    var hoverX by remember { mutableStateOf(0f) }
    var hoverY by remember { mutableStateOf(0f) }

    // 年份选项：只列出「有数据」的年份（无数据不显示）
    val years = remember(tick, daily.size) {
        val set = sortedSetOf<Int>()
        for (entry in daily.entries) {
            val v = entry.value
            if (v.listenedMs <= 0L && v.count <= 0) continue
            val y = entry.key.substringBefore('-').toIntOrNull()
            if (y != null && y >= 2000 && y <= today.year + 1) set.add(y)
        }
        val list = ArrayList<YearOption>(set.size + 1)
        list.add(YearOption(-1, "近一年"))
        val arr = ArrayList<Int>(set)
        var idx = arr.size
        while (idx > 0) {
            val y = arr[idx - 1]
            list.add(YearOption(y, y.toString() + " 年"))
            idx--
        }
        list
    }

    // 完整网格范围（对齐到周一 ~ 周日）
    val fullStart: LocalDate
    val fullEnd: LocalDate
    if (yearMode < 0) {
        val start = today.minusDays(364)
        fullStart = start.minusDays((start.dayOfWeek.value - 1).toLong())
        fullEnd = today.plusDays((7 - today.dayOfWeek.value).toLong())
    } else {
        val first = LocalDate.of(yearMode, 1, 1)
        val last = LocalDate.of(yearMode, 12, 31)
        fullStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
        fullEnd = last.plusDays((7 - last.dayOfWeek.value).toLong())
    }
    val fullWeeks = ((fullEnd.toEpochDay() - fullStart.toEpochDay()) / 7L).toInt() + 1

    // 动态色阶上限：当前显示范围内「收听时长最高的那一天」
    // 这样无论每天听几分钟还是几小时，热力图总有完整的深浅层次
    val highMs = remember(tick, yearMode) {
        var best = 0L
        for (entry in daily.entries) {
            val date = try {
                LocalDate.parse(entry.key)
            } catch (t: Throwable) {
                continue
            }
            if (date.isBefore(fullStart) || date.isAfter(fullEnd)) continue
            if (date.isAfter(today)) continue
            if (yearMode >= 0 && date.year != yearMode) continue
            val ms = entry.value.listenedMs
            if (ms > best) best = ms
        }
        best
    }

    val labelCol = 24.dp
    val labelGap = 4.dp
    val gap = 3.dp

    // 格子尺寸：按当前内容宽铺满完整周数，限制在 14–22dp；宽度不够时减列
    var cellDp = 16.dp
    var weeks = fullWeeks
    var gridStart = fullStart
    val labelPx = with(dLocal) { (labelCol + labelGap).toPx() }
    val gapPx = with(dLocal) { gap.toPx() }
    if (containerPx > 0f) {
        val usable = containerPx - labelPx
        if (usable > 0f) {
            val ideal = (usable - gapPx * (fullWeeks - 1)) / fullWeeks
            var cell = with(dLocal) { ideal.toDp() }
            if (cell > 22.dp) cell = 22.dp
            if (cell < 14.dp) cell = 14.dp
            cellDp = cell
            val cellPxNow = with(dLocal) { cellDp.toPx() }
            val fit = ((usable + gapPx) / (cellPxNow + gapPx)).toInt()
            if (fit < fullWeeks) {
                weeks = if (fit < 8) 8 else fit
                // 保留最近的 weeks 周（从末尾往前截断）
                gridStart = fullEnd.minusDays((weeks - 1L) * 7L + 6L)
            } else {
                weeks = fullWeeks
                gridStart = fullStart
            }
        }
    }
    val gridWidth = (cellDp + gap) * weeks - gap
    val gridHeight = (cellDp + gap) * 7 - gap
    val cellPx = with(dLocal) { cellDp.toPx() }
    // 月份 / 星期标签固定字号，不跟随「字体大小」设置（用 fontScale 反向补偿）
    val fixedLabelSize = ((cellDp.value * 0.62f).coerceIn(11f, 15f) / dLocal.fontScale).sp
    val labelSize = fixedLabelSize

    // 统计摘要
    val summary = remember(tick, yearMode) {
        var activeDays = 0
        var totalMs = 0L
        val from = if (yearMode < 0) today.minusDays(364) else LocalDate.of(yearMode, 1, 1)
        val to = if (yearMode < 0) today else LocalDate.of(yearMode, 12, 31)
        for (entry in daily.entries) {
            val d = try {
                LocalDate.parse(entry.key)
            } catch (t: Throwable) {
                continue
            }
            if (d.isBefore(from) || d.isAfter(to)) continue
            if (entry.value.listenedMs > 0L || entry.value.count > 0) {
                activeDays++
                totalMs += entry.value.listenedMs
            }
        }
        val label = if (yearMode < 0) "近一年" else yearMode.toString() + " 年"
        label + " · " + activeDays + " 个听歌日 · " + formatMs(totalMs)
    }

    Column(Modifier.width(width)) {
        // 标题行：左侧统计摘要，右侧年份下拉（用 DropdownMenu，不参与布局）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                summary,
                fontSize = SaltDesign.fontSizeSub,
                color = p.subText,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.height(28.dp).width(104.dp)) {
                Surface(
                    onClick = { yearPickerOpen = !yearPickerOpen },
                    shape = SaltDesign.shapeSmall,
                    color = p.subBackground,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val current = if (yearMode < 0) "近一年" else yearMode.toString() + " 年"
                        Text(current, fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.width(6.dp))
                        ChevronGlyph(left = false, color = p.subText, rotateDown = true)
                    }
                }
                DropdownMenu(
                    expanded = yearPickerOpen,
                    onDismissRequest = { yearPickerOpen = false },
                    modifier = Modifier.width(136.dp),
                    // 不给 containerColor 会取 MaterialTheme 的 surface——浅色主题下可能是深色，
                    // 与我们的浅色文本撞成黑底黑字
                    containerColor = p.dialogSurface
                ) {
                    var oi = 0
                    while (oi < years.size && oi < 8) {
                        val opt = years[oi]
                        DropdownMenuItem(
                            text = {
                                Text(
                                    opt.label,
                                    fontSize = SaltDesign.fontSizeSub,
                                    color = if (opt.value == yearMode) p.highlight else p.text
                                )
                            },
                            onClick = {
                                yearMode = opt.value
                                yearPickerOpen = false
                                hoverKey = ""
                            }
                        )
                        oi++
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier.fillMaxWidth().onGloballyPositioned { containerPx = it.size.width.toFloat() }
        ) {
            Row {
                // 星期标签（一 / 三 / 五 / 日），字号随格子缩放
                Column(
                    Modifier.width(labelCol).padding(top = 20.dp).height(gridHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    var d = 0
                    while (d < 7) {
                        val label = when (d) {
                            0 -> "一"
                            2 -> "三"
                            4 -> "五"
                            6 -> "日"
                            else -> ""
                        }
                        Text(label, fontSize = labelSize, color = p.subText)
                        d++
                    }
                }
                Spacer(Modifier.width(labelGap))
                Column {
                    // 月份标签
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        var w = 0
                        var lastMonth = -1
                        while (w < weeks) {
                            val m = gridStart.plusDays(w * 7L).monthValue
                            Box(Modifier.width(cellDp).height(16.dp)) {
                                if (m != lastMonth) {
                                    Text(
                                        m.toString(),
                                        fontSize = labelSize,
                                        lineHeight = (labelSize.value + 2f).sp,
                                        color = p.subText
                                    )
                                }
                            }
                            lastMonth = m
                            w++
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 整块网格一次绘制，命中靠坐标反推（精确到格）
                    Box(
                        Modifier
                            .width(gridWidth)
                            .height(gridHeight)
                            .onPointerEvent(PointerEventType.Move) { event ->
                                val change = event.changes.firstOrNull()
                                if (change != null) {
                                    val pos = change.position
                                    val step = cellPx + gapPx
                                    val col = (pos.x / step).toInt()
                                    val row = (pos.y / step).toInt()
                                    val insideX = pos.x - col * step <= cellPx
                                    val insideY = pos.y - row * step <= cellPx
                                    if (col >= 0 && col < weeks && row >= 0 && row < 7 && insideX && insideY) {
                                        val date = gridStart.plusDays(col * 7L + row)
                                        val inRange = yearMode < 0 || date.year == yearMode
                                        if (inRange && !date.isAfter(today)) {
                                            hoverKey = date.toString()
                                            hoverX = pos.x
                                            hoverY = pos.y
                                        } else {
                                            hoverKey = ""
                                        }
                                    } else {
                                        hoverKey = ""
                                    }
                                }
                            }
                            .onPointerEvent(PointerEventType.Exit) { hoverKey = "" }
                            .onPointerEvent(PointerEventType.Press) { event ->
                                val change = event.changes.firstOrNull()
                                if (change != null) {
                                    val pos = change.position
                                    val step = cellPx + gapPx
                                    val col = (pos.x / step).toInt()
                                    val row = (pos.y / step).toInt()
                                    val insideX = pos.x - col * step <= cellPx
                                    val insideY = pos.y - row * step <= cellPx
                                    if (col >= 0 && col < weeks && row >= 0 && row < 7 && insideX && insideY) {
                                        val date = gridStart.plusDays(col * 7L + row)
                                        val inRange = yearMode < 0 || date.year == yearMode
                                        if (inRange && !date.isAfter(today)) onSelectDay(date.toString())
                                    }
                                }
                            }
                            .drawBehind {
                                val radius = androidx.compose.ui.geometry.CornerRadius(cellPx * 0.18f, cellPx * 0.18f)
                                var w = 0
                                while (w < weeks) {
                                    var d = 0
                                    while (d < 7) {
                                        val date = gridStart.plusDays(w * 7L + d)
                                        val inRange = yearMode < 0 || date.year == yearMode
                                        if (inRange && !date.isAfter(today)) {
                                            val key = date.toString()
                                            val agg = daily[key]
                                            val ms = if (agg == null) 0L else agg.listenedMs
                                            val level = HeatmapScale.level(ms, highMs)
                                            val x = w * (cellPx + gapPx)
                                            val y = d * (cellPx + gapPx)
                                            drawRoundRect(
                                                color = heatColor(level, p.isDark),
                                                topLeft = Offset(x, y),
                                                size = Size(cellPx, cellPx),
                                                cornerRadius = radius
                                            )
                                        }
                                        d++
                                    }
                                    w++
                                }
                                // 悬浮格描边
                                if (hoverKey.isNotEmpty()) {
                                    val hoverDate = try {
                                        LocalDate.parse(hoverKey)
                                    } catch (t: Throwable) {
                                        null
                                    }
                                    if (hoverDate != null) {
                                        val col = ((hoverDate.toEpochDay() - gridStart.toEpochDay()) / 7L).toInt()
                                        val row = ((hoverDate.toEpochDay() - gridStart.toEpochDay()) % 7L).toInt()
                                        if (col >= 0 && col < weeks) {
                                            val x = col * (cellPx + gapPx)
                                            val y = row * (cellPx + gapPx)
                                            drawRoundRect(
                                                color = p.highlight,
                                                topLeft = Offset(x, y),
                                                size = Size(cellPx, cellPx),
                                                cornerRadius = radius,
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 1.6f * density
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                    )
                }
            }

            // 悬浮卡片：绝对定位覆盖，不参与布局（避免把下方组件顶下去）
            if (hoverKey.isNotEmpty()) {
                val hoverDate = try {
                    LocalDate.parse(hoverKey)
                } catch (t: Throwable) {
                    null
                }
                if (hoverDate != null) {
                    val agg = daily[hoverKey]
                    val ms = if (agg == null) 0L else agg.listenedMs
                    val count = if (agg == null) 0 else agg.count
                    val cardW = 168.dp
                    val cardWPx = with(dLocal) { cardW.toPx() }
                    val cardHPx = with(dLocal) { 64.dp.toPx() }
                    val labelPx = with(dLocal) { (labelCol + labelGap).toPx() }
                    // 鼠标位置换算到「网格左上角」作为基准
                    var leftPx = labelPx + hoverX + cellPx + 10f
                    if (containerPx > 0f && leftPx + cardWPx > containerPx) {
                        leftPx = labelPx + hoverX - cardWPx - 10f
                    }
                    if (leftPx < 0f) leftPx = 0f
                    var topPx = 20.dp.value * dLocal.density + hoverY
                    if (topPx + cardHPx > gridHeight.value * dLocal.density + 20.dp.value * dLocal.density) {
                        topPx = 20.dp.value * dLocal.density + hoverY - cardHPx - 8f
                    }
                    if (topPx < 0f) topPx = 0f
                    Surface(
                        shape = SaltDesign.shapeMedium,
                        color = p.dialogSurface,
                        shadowElevation = 14.dp,
                        border = BorderStroke(1.dp, SolidColor(p.subText.copy(alpha = 0.18f))),
                        modifier = Modifier
                            .offset(x = with(dLocal) { leftPx.toDp() }, y = with(dLocal) { topPx.toDp() })
                            .width(cardW)
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                hoverKey + "（" + weekdayText(hoverKey) + "）",
                                fontSize = SaltDesign.fontSizeSub,
                                color = p.text
                            )
                            Spacer(Modifier.height(4.dp))
                            Row {
                                Text("听歌时长", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                                Spacer(Modifier.weight(1f))
                                Text(formatMs(ms), fontSize = SaltDesign.fontSizeSub, color = p.highlight)
                            }
                            Row {
                                Text("播放次数", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                                Spacer(Modifier.weight(1f))
                                Text(count.toString() + " 次", fontSize = SaltDesign.fontSizeSub, color = p.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 时长分布：范围预设 + 自动粒度 + 柱/折线/曲线 + Y 轴刻度 */
@Composable
private fun DurationChart(
    width: androidx.compose.ui.unit.Dp,
    tick: Int,
    preset: RangePreset,
    customFrom: LocalDate,
    customTo: LocalDate,
    byTime: Boolean,
    chartType: Int,
    daily: Map<String, DayAgg>,
    onPreset: (RangePreset) -> Unit,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onByTime: (Boolean) -> Unit,
    onChartType: (Int) -> Unit
) {
    val p = LocalSaltPalette.current
    val dLocal = LocalDensity.current
    val today = LocalDate.now()
    val range = RangeBuckets.rangeOf(preset, today, customFrom, customTo)
    val from = range.first
    val to = range.second
    val days = RangeBuckets.dayCount(from, to)

    val hourly = if (days <= 1) {
        remember(from, tick) { StatsStore.hourProfile(from.toString(), to.toString()) }
    } else {
        null
    }
    val buckets = remember(from, to, tick, byTime) {
        RangeBuckets.build(from, to, hourly, daily)
    }

    val values = FloatArray(buckets.size)
    var rawMax = 0f
    var total = 0.0
    var i = 0
    while (i < buckets.size) {
        val v = if (byTime) buckets[i].ms.toFloat() else buckets[i].count.toFloat()
        values[i] = v
        total += v.toDouble()
        if (v > rawMax) rawMax = v
        i++
    }
    val axisMax = niceAxisMax(rawMax)
    val average = if (buckets.size > 0) (total / buckets.size).toFloat() else 0f

    var started by remember(from, to, byTime) { mutableStateOf(false) }
    LaunchedEffect(from, to, byTime) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 380),
        label = "bucket-appear"
    )

    var hovered by remember(from, to) { mutableStateOf(-1) }
    var hoverLeftPx by remember { mutableStateOf(0f) }
    var hoverBarW by remember { mutableStateOf(0f) }
    var plotPx by remember { mutableStateOf(0f) }
    // 鼠标在图表区内的位置（用于跟随式浮窗）
    var mouseX by remember { mutableStateOf(-1f) }
    var mouseY by remember { mutableStateOf(0f) }

    val axisTitle = if (byTime) axisUnitMs(axisMax) else "次"

    Column(Modifier.width(width)) {
        // 预设 + 图表类型
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                var s = 0
                while (s < 6) {
                    val option = presetOptions[s]
                    val active = preset == option
                    Surface(
                        onClick = { onPreset(option) },
                        shape = SaltDesign.shapeMedium,
                        color = if (active) p.subBackground else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            SolidColor(if (active) p.highlight else p.subText.copy(alpha = 0.18f))
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                option.label,
                                fontSize = SaltDesign.fontSizeSub,
                                color = if (active) p.highlight else p.subText
                            )
                        }
                    }
                    s++
                }
            }
            Spacer(Modifier.width(10.dp))
            // 柱 / 折线 / 曲线
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                var ct = 0
                while (ct < 3) {
                    val active = chartType == ct
                    val kind = ct
                    Surface(
                        onClick = { onChartType(kind) },
                        shape = SaltDesign.shapeSmall,
                        color = if (active) p.subBackground else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            SolidColor(if (active) p.highlight else p.subText.copy(alpha = 0.18f))
                        )
                    ) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            ChartTypeGlyph(kind, if (active) p.highlight else p.subText)
                        }
                    }
                    ct++
                }
            }
        }

        if (preset == RangePreset.Custom) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onPickFrom,
                    shape = SaltDesign.shapeSmall,
                    color = p.subBackground,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("开始 " + customFrom.toString(), fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.width(6.dp))
                        CalendarGlyph(p.highlight.copy(alpha = 0.9f))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = onPickTo,
                    shape = SaltDesign.shapeSmall,
                    color = p.subBackground,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("结束 " + customTo.toString(), fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.width(6.dp))
                        CalendarGlyph(p.highlight.copy(alpha = 0.9f))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 说明行：普通描述 + 强调色的平均值（两者分开以便分别着色）
            Text(
                RangeBuckets.describe(from, to) + " · " + RangeBuckets.granularityLabel(from, to),
                fontSize = SaltDesign.fontSizeSub,
                color = p.subText
            )
            if (average > 0f && buckets.size > 1) {
                val unit = if (!byTime) " 次" else if (axisMax >= 3_600_000f) " 小时" else " 分钟"
                Text(
                    " · 平均 " + axisTickLabel(average, byTime, axisMax, axisMax / 4f) + unit,
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.highlight
                )
            }
            Spacer(Modifier.weight(1f))
            Row {
                UnderlineTab("次数", !byTime, Modifier.width(54.dp), height = 28.dp) { onByTime(false) }
                UnderlineTab("时长", byTime, Modifier.width(54.dp), height = 28.dp) { onByTime(true) }
            }
        }

        Spacer(Modifier.height(10.dp))
        // 轴单位标题单独一行，不占刻度区高度（之前放在刻度列内会把刻度整体推偏 22dp）
        Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                axisTitle,
                fontSize = 10.sp,
                color = p.subText,
                modifier = Modifier.width(40.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            // Y 轴刻度：与图表同高（170dp），文字垂直中心对齐网格线
            val labelLineH = 13.dp
            Box(Modifier.width(40.dp).height(170.dp)) {
                var g = 4
                while (g >= 0) {
                    val tickValue = axisMax * g / 4f
                    // g=4 → y=0（顶），g=0 → y=170dp（底）
                    val centerY = 170.dp * (4 - g) / 4f
                    Text(
                        axisTickLabel(tickValue, byTime, axisMax, axisMax / 4f),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = p.subText,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = centerY - labelLineH / 2)
                    )
                    g--
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.fillMaxWidth().height(170.dp)
                        .onGloballyPositioned { plotPx = it.size.width.toFloat() }
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val chg = event.changes.firstOrNull()
                            if (chg != null) {
                                mouseX = chg.position.x
                                mouseY = chg.position.y
                            }
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            mouseX = -1f
                            hovered = -1
                        }
                ) {
                    // 网格线
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            val stepY = size.height / 4f
                            var g = 0
                            while (g <= 4) {
                                val y = size.height - stepY * g
                                drawLine(
                                    color = p.subText.copy(alpha = if (g == 0) 0.35f else 0.14f),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1f
                                )
                                g++
                            }
                        }
                    )
                    if (chartType == 0) {
                        Row(
                            Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            var b = 0
                            while (b < buckets.size) {
                                val bucket = buckets[b]
                                val value = values[b]
                                val index = b
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(170.dp)
                                        .onGloballyPositioned { coords ->
                                            if (hovered == index) {
                                                val pos = coords.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                                                hoverLeftPx = pos.x
                                                hoverBarW = coords.size.width.toFloat()
                                            }
                                        }
                                        .onPointerEvent(PointerEventType.Enter) {
                                            hovered = index
                                            hoverLeftPx = -1f
                                        }
                                        .onPointerEvent(PointerEventType.Exit) {
                                            if (hovered == index) hovered = -1
                                        }
                                        .drawBehind {
                                            val ratio = if (axisMax > 0f) value / axisMax else 0f
                                            val h = size.height * ratio * progress
                                            if (h <= 0f) return@drawBehind
                                            val barW = size.width * 0.66f
                                            val left = (size.width - barW) / 2f
                                            val active = hovered == index
                                            drawRoundRect(
                                                color = if (active) p.highlight else p.highlight.copy(alpha = 0.6f),
                                                topLeft = Offset(left, size.height - h),
                                                size = Size(barW, h),
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * density, 2.5f * density)
                                            )
                                        }
                                )
                                b++
                            }
                        }
                    } else {
                        // 折线 / 曲线
                        Box(
                            Modifier.fillMaxSize().drawBehind {
                                if (buckets.isEmpty()) return@drawBehind
                                val n = buckets.size
                                val stepX = if (n > 1) size.width / (n - 1).toFloat() else size.width
                                val pts = ArrayList<Offset>(n)
                                var b2 = 0
                                while (b2 < n) {
                                    val ratio = if (axisMax > 0f) values[b2] / axisMax else 0f
                                    val y = size.height - size.height * ratio * progress
                                    pts.add(Offset(if (n > 1) b2 * stepX else size.width / 2f, y))
                                    b2++
                                }
                                val path = if (chartType == 1) {
                                    val pl = androidx.compose.ui.graphics.Path()
                                    pl.moveTo(pts[0].x, pts[0].y)
                                    var q = 1
                                    while (q < pts.size) {
                                        pl.lineTo(pts[q].x, pts[q].y)
                                        q++
                                    }
                                    pl
                                } else {
                                    monotonePath(pts)
                                }
                                // 面积
                                val fill = androidx.compose.ui.graphics.Path()
                                fill.addPath(path)
                                fill.lineTo(pts[pts.size - 1].x, size.height)
                                fill.lineTo(pts[0].x, size.height)
                                fill.close()
                                drawPath(fill, p.highlight.copy(alpha = 0.13f))
                                drawPath(
                                    path,
                                    p.highlight,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * density)
                                )
                                // 平均线统一在叠加层画（虚线 + 标签），这里不再重复绘制
                            }
                        )
                        // 折线图的悬停：按最近点
                        Row(Modifier.fillMaxSize()) {
                            var b3 = 0
                            while (b3 < buckets.size) {
                                val index = b3
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(170.dp)
                                        .onGloballyPositioned { coords ->
                                            if (hovered == index) {
                                                val pos = coords.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                                                hoverLeftPx = pos.x
                                                hoverBarW = coords.size.width.toFloat()
                                            }
                                        }
                                        .onPointerEvent(PointerEventType.Enter) {
                                            hovered = index
                                            hoverLeftPx = -1f
                                        }
                                        .onPointerEvent(PointerEventType.Exit) {
                                            if (hovered == index) hovered = -1
                                        }
                                )
                                b3++
                            }
                        }
                    }
                    // 叠加层：平均虚线 + 十字光标（在柱/线之上、浮窗之下）
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            if (buckets.isEmpty()) return@drawBehind
                            // 平均虚线
                            if (average > 0f && buckets.size > 1 && axisMax > 0f) {
                                val avgY = size.height - size.height * (average / axisMax).coerceIn(0f, 1f)
                                drawLine(
                                    color = p.subText.copy(alpha = 0.6f),
                                    start = Offset(0f, avgY),
                                    end = Offset(size.width, avgY),
                                    strokeWidth = 1.2f,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(6f, 5f)
                                    )
                                )
                                // 平均线只画虚线；数值改在图表上方说明行里显示（避免在图表内压住图形）
                            }
                            // 十字光标：竖向虚线 + 实心数据点
                            if (hovered >= 0 && hovered < buckets.size) {
                                val n = buckets.size
                                val cx = if (chartType == 0 || n == 1) {
                                    (hovered + 0.5f) * size.width / n
                                } else {
                                    hovered * size.width / (n - 1).toFloat()
                                }
                                drawLine(
                                    color = p.subText.copy(alpha = 0.45f),
                                    start = Offset(cx, 0f),
                                    end = Offset(cx, size.height),
                                    strokeWidth = 1f,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(3f, 4f)
                                    )
                                )
                                val ratio = if (axisMax > 0f) values[hovered] / axisMax else 0f
                                val cy = size.height - size.height * ratio.coerceIn(0f, 1f)
                                drawCircle(
                                    color = p.highlight,
                                    radius = 4f,
                                    center = Offset(cx, cy)
                                )
                            }
                        }
                    )
                    // 悬浮卡片：跟随鼠标（绝对定位，不参与布局）
                    if (hovered >= 0 && hovered < buckets.size && mouseX >= 0f) {
                        val bucket = buckets[hovered]
                        val cardW = 176.dp
                        val cardWPx = with(dLocal) { cardW.toPx() }
                        var leftPx = mouseX + 16f
                        if (plotPx > 0f && leftPx + cardWPx > plotPx) {
                            leftPx = mouseX - cardWPx - 16f
                        }
                        if (leftPx < 0f) leftPx = 0f
                        var topPx = mouseY - with(dLocal) { 84.dp.toPx() }
                        if (topPx < 0f) topPx = mouseY + 16f
                        Surface(
                            shape = SaltDesign.shapeMedium,
                            color = p.dialogSurface,
                            shadowElevation = 14.dp,
                            border = BorderStroke(1.dp, SolidColor(p.subText.copy(alpha = 0.18f))),
                            modifier = Modifier
                                .offset(
                                    x = with(dLocal) { leftPx.toDp() },
                                    y = with(dLocal) { topPx.toDp() }
                                )
                                .width(cardW)
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(bucket.title, fontSize = SaltDesign.fontSizeSub, color = p.text)
                                Spacer(Modifier.height(4.dp))
                                Row {
                                    Text("次数", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                                    Spacer(Modifier.weight(1f))
                                    Text(bucket.count.toString() + " 次", fontSize = SaltDesign.fontSizeSub, color = p.text)
                                }
                                Row {
                                    Text("时长", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                                    Spacer(Modifier.weight(1f))
                                    Text(formatMs(bucket.ms), fontSize = SaltDesign.fontSizeSub, color = p.highlight)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // X 轴标签
                val step = RangeBuckets.axisLabelStep(buckets.size)
                Row(Modifier.fillMaxWidth()) {
                    var b4 = 0
                    while (b4 < buckets.size) {
                        val show = b4 % step == 0 || b4 == buckets.size - 1
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (show) {
                                Text(buckets[b4].label, fontSize = 10.sp, color = p.subText, maxLines = 1)
                            }
                        }
                        b4++
                    }
                }
            }
        }

        // 悬浮卡片在图表区内部绘制（见上方 Box），这里保持空
    }
}

private val presetOptions = arrayOf(
    RangePreset.Today,
    RangePreset.Last7,
    RangePreset.Last30,
    RangePreset.ThisMonth,
    RangePreset.ThisYear,
    RangePreset.Custom
)

/** 柱 / 折线 / 曲线 的小图标 */
@Composable
private fun ChartTypeGlyph(kind: Int, color: Color) {
    Box(
        Modifier.size(16.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.11f
            when (kind) {
                0 -> {
                    val barW = s * 0.16f
                    drawRect(color, Offset(s * 0.16f, s * 0.55f), Size(barW, s * 0.4f))
                    drawRect(color, Offset(s * 0.42f, s * 0.35f), Size(barW, s * 0.6f))
                    drawRect(color, Offset(s * 0.68f, s * 0.15f), Size(barW, s * 0.8f))
                }
                1 -> {
                    drawLine(color, Offset(s * 0.12f, s * 0.78f), Offset(s * 0.40f, s * 0.46f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.40f, s * 0.46f), Offset(s * 0.62f, s * 0.66f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.62f, s * 0.66f), Offset(s * 0.88f, s * 0.22f), strokeWidth = w)
                }
                else -> {
                    drawLine(color, Offset(s * 0.12f, s * 0.72f), Offset(s * 0.30f, s * 0.40f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.30f, s * 0.40f), Offset(s * 0.52f, s * 0.74f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.52f, s * 0.74f), Offset(s * 0.70f, s * 0.30f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.70f, s * 0.30f), Offset(s * 0.88f, s * 0.52f), strokeWidth = w)
                }
            }
        }
    )
}

/** 把最大值抬到「整」刻度（1/2/5 × 10^n），并留 12% 顶部余量避免最高柱贴顶 */
private fun niceAxisMax(value: Float): Float {
    if (value <= 0f) return 1f
    val padded = value * 1.12f
    val exp = Math.floor(Math.log10(padded.toDouble())).toInt()
    var base = 1.0
    var e = 0
    while (e < exp) {
        base *= 10.0
        e++
    }
    val norm = padded / base
    val niceNorm = when {
        norm <= 1.0 -> 1.0
        norm <= 2.0 -> 2.0
        norm <= 5.0 -> 5.0
        else -> 10.0
    }
    return (niceNorm * base).toFloat()
}

/** Y 轴单位标题：只有小时 / 分钟两档（短文案，避免 40dp 列宽截断） */
private fun axisUnitMs(axisMax: Float): String {
    return if (axisMax >= 3_600_000f) "小时" else "分钟"
}

/** 毫秒值换算成当前轴单位下的数值 */
private fun axisValueMs(value: Float, axisMax: Float): Double {
    val unit = if (axisMax >= 3_600_000f) 3_600_000.0 else 60_000.0
    return value / unit
}

/**
 * 按刻度间隔的小数位格式化，避免「0 / 0 / 0 / 1」这种截断重复
 *
 * 例：step=0.5 → 输出 0、0.5、1、1.5、2；step=10 → 输出 0、10、20
 */
private fun formatAxisNumber(value: Double, step: Double): String {
    val safeStep = if (step <= 0.0) 1.0 else step
    val digits = try {
        java.math.BigDecimal.valueOf(safeStep).stripTrailingZeros().scale().coerceIn(0, 2)
    } catch (t: Throwable) {
        0
    }
    return try {
        java.math.BigDecimal.valueOf(value)
            .setScale(digits, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    } catch (t: Throwable) {
        value.toLong().toString()
    }
}

/** Y 轴刻度文字（单位由标题承担，这里只给数字） */
private fun axisTickLabel(value: Float, byTime: Boolean, axisMax: Float, step: Float): String {
    if (!byTime) return value.toInt().toString()
    if (value <= 0f) return "0"
    return formatAxisNumber(axisValueMs(value, axisMax), axisValueMs(step, axisMax))
}

/** monotone cubic 插值：平滑但不会过冲（不产生假峰值） */
private fun monotonePath(pts: List<androidx.compose.ui.geometry.Offset>): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    if (pts.isEmpty()) return path
    if (pts.size == 1) {
        path.moveTo(pts[0].x, pts[0].y)
        return path
    }
    path.moveTo(pts[0].x, pts[0].y)
    var i = 0
    while (i < pts.size - 1) {
        val p0 = if (i > 0) pts[i - 1] else pts[i]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = if (i + 2 < pts.size) pts[i + 2] else p2
        val c1x = p1.x + (p2.x - p0.x) / 6f
        val c1y = p1.y + (p2.y - p0.y) / 6f
        val c2x = p2.x - (p3.x - p1.x) / 6f
        val c2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
        i++
    }
    return path
}



/** 日期导航条：‹ [标签 📅] ›，中间点击打开日历 */
/** 小日历图标 */
@Composable
private fun CalendarGlyph(color: Color) {
    Box(
        Modifier.size(13.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.10f
            drawRoundRect(
                color,
                topLeft = Offset(w, s * 0.16f),
                size = Size(s - w * 2f, s * 0.72f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f),
                style = Stroke(width = w)
            )
            drawLine(
                color,
                Offset(w, s * 0.40f),
                Offset(s - w, s * 0.40f),
                strokeWidth = w
            )
            drawLine(color, Offset(s * 0.32f, 0f), Offset(s * 0.32f, s * 0.22f), strokeWidth = w)
            drawLine(color, Offset(s * 0.68f, 0f), Offset(s * 0.68f, s * 0.22f), strokeWidth = w)
        }
    )
}

/** 左右/向下箭头（自绘 V 形） */
@Composable
private fun ChevronGlyph(left: Boolean, color: Color, rotateDown: Boolean = false) {
    Box(
        Modifier.size(14.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.12f
            if (rotateDown) {
                drawLine(color, Offset(s * 0.18f, s * 0.38f), Offset(s * 0.5f, s * 0.66f), strokeWidth = w)
                drawLine(color, Offset(s * 0.5f, s * 0.66f), Offset(s * 0.82f, s * 0.38f), strokeWidth = w)
            } else if (left) {
                drawLine(color, Offset(s * 0.62f, s * 0.15f), Offset(s * 0.30f, s * 0.5f), strokeWidth = w)
                drawLine(color, Offset(s * 0.30f, s * 0.5f), Offset(s * 0.62f, s * 0.85f), strokeWidth = w)
            } else {
                drawLine(color, Offset(s * 0.38f, s * 0.15f), Offset(s * 0.70f, s * 0.5f), strokeWidth = w)
                drawLine(color, Offset(s * 0.70f, s * 0.5f), Offset(s * 0.38f, s * 0.85f), strokeWidth = w)
            }
        }
    )
}

/** 日历弹窗状态（由面板层渲染） */
private object CalState {
    val open = mutableStateOf(false)
    val seed = mutableStateOf(LocalDate.now())
    val result = mutableStateOf<LocalDate?>(null)
    /** 自定义范围选择目标：0 = 开始日期，1 = 结束日期 */
    val pickTarget = mutableStateOf(0)
}

/** 月历选择器（选一天） */
@Composable
private fun CalendarPicker(
    initial: LocalDate,
    maxDate: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalSaltPalette.current
    var month by remember { mutableStateOf(initial.withDayOfMonth(1)) }
    val firstMonday = month.minusDays((month.dayOfWeek.value - 1).toLong())
    val maxMonthStart = maxDate.withDayOfMonth(1)
    val canForward = month.isBefore(maxMonthStart)

    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = SaltDesign.shapeLarge,
            color = p.dialogSurface,
            shadowElevation = 18.dp,
            modifier = Modifier.onPointerEvent(PointerEventType.Press) { e -> e.changes.forEach { ch -> ch.consume() } }
        ) {
            Column(Modifier.width(360.dp).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = { month = month.minusMonths(1) },
                        shape = SaltDesign.shapeSmall,
                        color = Color.Transparent,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ChevronGlyph(left = true, color = p.subText)
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            month.year.toString() + " 年 " + month.monthValue + " 月",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text
                        )
                    }
                    Surface(
                        onClick = { if (canForward) month = month.plusMonths(1) },
                        shape = SaltDesign.shapeSmall,
                        color = Color.Transparent,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ChevronGlyph(
                                left = false,
                                color = if (canForward) p.subText else p.subText.copy(alpha = 0.25f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    var c = 0
                    while (c < 7) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(weekdayShort(c), fontSize = 11.sp, color = p.subText)
                        }
                        c++
                    }
                }
                Spacer(Modifier.height(4.dp))
                var r = 0
                while (r < 6) {
                    Row(Modifier.fillMaxWidth()) {
                        var c = 0
                        while (c < 7) {
                            val date = firstMonday.plusDays((r * 7 + c).toLong())
                            val inMonth = date.monthValue == month.monthValue
                            val future = date.isAfter(maxDate)
                            val selected = date == initial
                            Box(
                                Modifier.weight(1f).height(34.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    onClick = { if (!future) onPick(date) },
                                    shape = SaltDesign.circle,
                                    color = if (selected) p.highlight else Color.Transparent,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            date.dayOfMonth.toString(),
                                            fontSize = 12.sp,
                                            color = when {
                                                selected -> p.onHighlight
                                                future -> p.subText.copy(alpha = 0.22f)
                                                inMonth -> p.text
                                                else -> p.subText.copy(alpha = 0.45f)
                                            }
                                        )
                                    }
                                }
                            }
                            c++
                        }
                    }
                    r++
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}

private fun weekdayShort(index: Int): String = when (index) {
    0 -> "一"
    1 -> "二"
    2 -> "三"
    3 -> "四"
    4 -> "五"
    5 -> "六"
    else -> "日"
}

/** 热力图配色：按「当天收听时长」档位（0=无，1–4 递增）而非次数 */
private fun heatColor(level: Int, dark: Boolean): Color {
    if (dark) {
        return when {
            level <= 0 -> Color(0x14FFFFFF)
            level == 1 -> Color(0x4D4CC2FF)
            level == 2 -> Color(0x804CC2FF)
            level == 3 -> Color(0xC04CC2FF)
            else -> Color(0xFF4CC2FF)
        }
    }
    return when {
        level <= 0 -> Color(0x14000000)
        level == 1 -> Color(0x4D0470E6)
        level == 2 -> Color(0x990470E6)
        level == 3 -> Color(0xCC0470E6)
        else -> Color(0xFF0470E6)
    }
}

/** 可滚动区域（宿主裁掉了 verticalScroll / LazyColumn，这里用滚轮 + 拖拽 + 偏移自实现）
 *  [resetKey] 变化时重置滚动位置（每个 Tab 各自独立） */
@Composable
private fun ScrollArea(
    resetKey: Any,
    barRightExtra: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    var viewportH by remember { mutableStateOf(0) }
    var contentH by remember { mutableStateOf(0) }
    var offsetY by remember(resetKey) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val rawMax = (contentH - viewportH).coerceAtLeast(0).toFloat()
    // 差值很小时（不到 60px）不开启滚动：宁可底部少显示一点，也不让顶部被裁
    val maxOffset = if (rawMax < 60f) 0f else rawMax
    val effOffset = if (offsetY > maxOffset) maxOffset else if (offsetY < 0f) 0f else offsetY
    val step = if (viewportH > 0) (viewportH / 4f).coerceAtLeast(60f) else 60f
    var scrollEvents by remember(resetKey) { mutableStateOf(0) }
    val p = LocalSaltPalette.current
    // 滚动条隐藏（用户反馈无滚动条观感更好）
    val barColor = Color.Transparent

    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned {
                    viewportH = it.size.height
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    scrollEvents = scrollEvents + 1
                    // 注意：onPointerEvent 的回调会被 pointerInput 冻结在首次组合，
                    // 所以这里必须从 State 实时读取，不能用组合期的局部变量
                    val vh = viewportH
                    val ch = contentH
                    val max = (ch - vh).coerceAtLeast(0).toFloat()
                    if (max > 0f) {
                        val stepNow = (vh / 4f).coerceAtLeast(60f)
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        offsetY = (offsetY + dy * stepNow).coerceIn(0f, max)
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        // 以无界高度测量（允许溢出），但报告高度不超过父约束，并从 (0,0) 放置
                        val placeable = measurable.measure(
                            constraints.copy(maxHeight = Constraints.Infinity)
                        )
                        layout(placeable.width, placeable.height.coerceAtMost(constraints.maxHeight)) {
                            placeable.place(0, 0)
                        }
                    }
                    .graphicsLayer {
                        val vh = viewportH
                        val ch = contentH
                        val max = (ch - vh).coerceAtLeast(0).toFloat()
                        val off = if (offsetY > max) max else if (offsetY < 0f) 0f else offsetY
                        translationY = -off
                    }
            ) {
                Box(
                    Modifier.fillMaxWidth().onSizeChanged { contentH = it.height }
                ) {
                    content()
                }
            }
            // 滚动条 + 顶/底渐变：全部在绘制阶段按 State 实时计算，避免被组合期快照冻结
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val vh = viewportH.toFloat()
                    val ch = contentH.toFloat()
                    val max = (ch - vh).coerceAtLeast(0f)
                    if (max > 0f && ch > 0f) {
                        val off = if (offsetY > max) max else if (offsetY < 0f) 0f else offsetY
                        val barW = 8.dp.toPx()
                        val barH = (size.height * (vh / ch)).coerceAtLeast(32.dp.toPx())
                        val barY = (off / max) * (size.height - barH)
                        // 内容居中时向外偏移，使滚动条始终贴近窗口右边缘
                        val extra = barRightExtra.toPx()
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(size.width + extra - barW - 4.dp.toPx(), barY),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(barW / 2f)
                        )
                    }
                }
            )
        }
    }
}

/** 计数门槛编辑器（面板内弹层） */
@Composable
private fun ThresholdEditor(onDismiss: () -> Unit) {
    var minSec by remember { mutableStateOf(CountRule.minSeconds) }
    var pct by remember { mutableStateOf(CountRule.percent) }
    var mode by remember { mutableStateOf(CountRule.mode) }
    val p = LocalSaltPalette.current

    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = SaltDesign.shapeLarge,
            color = p.dialogSurface,
            shadowElevation = 18.dp
        ) {
            Column(Modifier.width(460.dp).padding(20.dp)) {
                GroupHeader("计数门槛")
                StepperRow(
                    label = "最少时长",
                    value = minSec.toString() + " 秒",
                    canMinus = minSec > CountRule.LIMIT_MIN_SEC,
                    canPlus = minSec < CountRule.LIMIT_MAX_SEC,
                    onMinus = { minSec = minSec - 1 },
                    onPlus = { minSec = minSec + 1 }
                )
                Spacer(Modifier.height(6.dp))
                StepperRow(
                    label = "曲目百分比",
                    value = pct.toString() + " %",
                    canMinus = pct > 0,
                    canPlus = pct < 100,
                    onMinus = { pct = pct - 5 },
                    onPlus = { pct = pct + 5 }
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "取值方式",
                        fontSize = SaltDesign.fontSizeMain,
                        color = p.text,
                        modifier = Modifier.weight(1f)
                    )
                    SortChip("取较大", mode == "max", Modifier.width(96.dp)) { mode = "max" }
                    Spacer(Modifier.width(8.dp))
                    SortChip("取较小", mode == "min", Modifier.width(96.dp)) { mode = "min" }
                }
                Spacer(Modifier.height(14.dp))
                val preview = if (mode == "min") {
                    "听满 " + minSec + " 秒 与 " + pct + "% 时长的较小者"
                } else {
                    "听满 " + minSec + " 秒 与 " + pct + "% 时长的较大者"
                }
                Text(preview, fontSize = SaltDesign.fontSizeSub, color = p.labelAccent)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("取消", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        onClick = {
                            CountRule.save(minSec, pct, mode)
                            onDismiss()
                        },
                        shape = SaltDesign.shapeSmall,
                        color = p.highlight,
                        modifier = Modifier.width(110.dp)
                    ) {
                        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                            Text("保存", fontSize = SaltDesign.fontSizeMain, color = p.onHighlight)
                        }
                    }
                }
            }
        }
    }
}

/** 曲目详情弹层状态 */
private object TrackDetailState {
    val open = mutableStateOf<String?>(null)
}

/** 分组详情弹窗的打开状态（专辑 / 艺术家） */
private object GroupDetailState {
    /** 非空表示打开；内容为 "album|name|owner" 或 "artist|name|" */
    val open = mutableStateOf("")
}

/** 专辑 / 艺术家详情弹窗：组内曲目列表 + 汇总 */
@Composable
private fun GroupDetailDialog(payload: String, onDismiss: () -> Unit) {
    val p = LocalSaltPalette.current
    val parts = payload.split('|')
    val byAlbum = parts.size > 0 && parts[0] == "album"
    val name = if (parts.size > 1) parts[1] else ""
    val owner = if (parts.size > 2) parts[2] else ""
    val items = remember(payload) { StatsStore.tracksOfGroup(byAlbum, name, owner) }
    var page by remember(payload) { mutableStateOf(0) }
    val pageSize = 10
    val pageCount = if (items.isEmpty()) 1 else (items.size + pageSize - 1) / pageSize
    val safePage = if (page >= pageCount) pageCount - 1 else page

    var totalCount = 0
    var totalMs = 0L
    var i = 0
    while (i < items.size) {
        totalCount += items[i].count
        totalMs += items[i].listenedMs
        i++
    }

    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = SaltDesign.shapeLarge,
            color = p.dialogSurface,
            shadowElevation = 18.dp,
            modifier = Modifier.onPointerEvent(PointerEventType.Press) { e -> e.changes.forEach { ch -> ch.consume() } }
        ) {
            Column(Modifier.width(620.dp).padding(20.dp)) {
                GroupHeader(if (byAlbum) "专辑" else "艺术家")
                Text(
                    name + (if (owner.isNotEmpty()) "（" + owner + "）" else ""),
                    fontSize = 22.sp,
                    color = p.text
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    totalCount.toString() + " 次 · " + formatMs(totalMs) + " · " + items.size + " 首曲目",
                    fontSize = SaltDesign.fontSizeMain,
                    color = p.labelAccent
                )
                Spacer(Modifier.height(12.dp))
                if (items.isEmpty()) {
                    EmptyHint("没有可显示的曲目")
                }
                var from = safePage * pageSize
                var shown = 0
                while (from < items.size && shown < pageSize) {
                    val t = items[from]
                    if (shown > 0) RowDivider()
                    Row(
                        Modifier.fillMaxWidth().height(46.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrackCover(t.id, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                t.title.ifBlank { "未知曲目" },
                                fontSize = SaltDesign.fontSizeMain,
                                lineHeight = 16.sp,
                                color = p.text
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                t.artist,
                                fontSize = SaltDesign.fontSizeSub,
                                lineHeight = 13.sp,
                                color = p.subText
                            )
                        }
                        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(t.count.toString() + " 次", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        }
                        Box(Modifier.width(74.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(formatMs(t.listenedMs), fontSize = SaltDesign.fontSizeSub, color = p.subText)
                        }
                    }
                    from++
                    shown++
                }
                if (pageCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SmallButton("上一页", safePage > 0) { page = safePage - 1 }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (safePage + 1).toString() + " / " + pageCount,
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallButton("下一页", safePage < pageCount - 1) { page = safePage + 1 }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}

/** 曲目详情弹层 */
@Composable
private fun TrackDetailDialog(trackKey: String, onDismiss: () -> Unit) {
    val p = LocalSaltPalette.current
    val agg = remember(trackKey) { StatsStore.trackByKey(trackKey) }
    var history by remember(trackKey) { mutableStateOf<ArrayList<PlayEvent>>(ArrayList()) }
    var loading by remember(trackKey) { mutableStateOf(true) }
    LaunchedEffect(trackKey) {
        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            StatsStore.trackHistory(agg?.id ?: "", 20)
        }
        val target = ArrayList<PlayEvent>()
        var i = 0
        while (i < list.size) {
            target.add(list[i])
            i++
        }
        history = target
        loading = false
    }

    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = SaltDesign.shapeLarge,
            color = p.dialogSurface,
            shadowElevation = 18.dp,
            modifier = Modifier.onPointerEvent(PointerEventType.Press) { e -> e.changes.forEach { ch -> ch.consume() } }
        ) {
            Column(Modifier.width(580.dp).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TrackCover(agg?.id ?: "", 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            agg?.title?.ifBlank { "未知曲目" } ?: "未知曲目",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text
                        )
                        Spacer(Modifier.height(2.dp))
                        val artist = agg?.artist ?: ""
                        val album = agg?.album ?: ""
                        Text(
                            artist + (if (album.isBlank()) "" else " · " + album),
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    KpiCell("总播放", (agg?.count ?: 0).toString() + " 次", Modifier.weight(1f))
                    KpiCell("累计时长", formatMs(agg?.listenedMs ?: 0L), Modifier.weight(1f))
                    KpiCell("跳过", (agg?.skipCount ?: 0).toString() + " 次", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "首次 " + dayText(agg?.firstPlayed ?: 0L) + " · 最近 " + formatLastPlayed(agg?.lastPlayed ?: 0L),
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.subText
                )
                Spacer(Modifier.height(14.dp))
                GroupHeader("最近播放记录")
                if (loading) {
                    Text(
                        "正在读取播放记录…",
                        fontSize = SaltDesign.fontSizeSub,
                        color = p.subText
                    )
                } else if (history.isEmpty()) {
                    Text(
                        "保留期内没有逐次明细（明细只保留最近 N 天，可在信息页调整）",
                        fontSize = SaltDesign.fontSizeSub,
                        color = p.subText
                    )
                }
                var i = 0
                while (i < history.size) {
                    if (i > 0) RowDivider()
                    val e = history[i]
                    Row(
                        Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(140.dp)) {
                            Text(
                                formatDateTime(e.t),
                                fontSize = SaltDesign.fontSizeSub,
                                color = p.subText
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            Text(
                                formatMs(e.ms),
                                fontSize = SaltDesign.fontSizeSub,
                                color = p.text
                            )
                        }
                        Box(Modifier.width(70.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                if (e.skipped) "跳过" else if (e.counted) "计入" else "未计入",
                                fontSize = SaltDesign.fontSizeSub,
                                color = if (e.skipped) p.error else p.subText
                            )
                        }
                    }
                    i++
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}

private fun formatDateTime(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    return fmt.format(Date(timestamp))
}

private fun dayText(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    return fmt.format(Date(timestamp))
}

/** 某天的播放明细弹层 */
@Composable
private fun DayDetailDialog(date: String, onDismiss: () -> Unit) {
    val p = LocalSaltPalette.current
    val items = remember(date) { StatsStore.dayDetail(date) }
    var page by remember(date) { mutableStateOf(0) }
    val pageSize = 10
    val pageCount = if (items.isEmpty()) 1 else (items.size + pageSize - 1) / pageSize
    val safePage = if (page >= pageCount) pageCount - 1 else page
    var totalCount = 0
    var totalMs = 0L
    var i = 0
    while (i < items.size) {
        totalCount += items[i].count
        totalMs += items[i].listenedMs
        i++
    }

    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = SaltDesign.shapeLarge,
            color = p.dialogSurface,
            shadowElevation = 18.dp,
            modifier = Modifier.onPointerEvent(PointerEventType.Press) { e -> e.changes.forEach { ch -> ch.consume() } }
        ) {
            Column(Modifier.width(560.dp).padding(20.dp)) {
                GroupHeader(date + "（" + weekdayText(date) + "）")
                Text(
                    totalCount.toString() + " 次 · " + formatMs(totalMs) + " · " + items.size + " 首曲目",
                    fontSize = SaltDesign.fontSizeMain,
                    color = p.labelAccent
                )
                Spacer(Modifier.height(12.dp))
                if (items.isEmpty()) {
                    EmptyHint("当天没有播放记录")
                }
                var from = safePage * pageSize
                var shown = 0
                while (from < items.size && shown < pageSize) {
                    val item = items[from]
                    if (shown > 0) RowDivider()
                    val coverId = remember(item.key) {
                        val agg = StatsStore.trackByKey(item.key)
                        if (agg == null) "" else agg.id
                    }
                    Row(
                        Modifier.fillMaxWidth().height(46.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrackCover(coverId, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                item.title.ifBlank { "未知曲目" },
                                fontSize = SaltDesign.fontSizeMain,
                                lineHeight = 17.sp,
                                color = p.text
                            )
                            if (item.artist.isNotBlank()) {
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    item.artist,
                                    fontSize = SaltDesign.fontSizeSub,
                                    lineHeight = 14.sp,
                                    color = p.subText
                                )
                            }
                        }
                        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(item.count.toString() + " 次", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        }
                        Box(Modifier.width(74.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(formatMs(item.listenedMs), fontSize = SaltDesign.fontSizeSub, color = p.subText)
                        }
                    }
                    from++
                    shown++
                }
                if (pageCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "共 " + items.size + " 首",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.weight(1f))
                        SmallButton("上一页", safePage > 0) { page = safePage - 1 }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (safePage + 1).toString() + " / " + pageCount,
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallButton("下一页", safePage < pageCount - 1) { page = safePage + 1 }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}

/** 全局字体大小编辑弹层（所有字号同比例放大） */
@Composable
private fun FontSizeEditor(onDismiss: () -> Unit) {
    var extra by remember { mutableStateOf(OverlayInjection.fontExtraPercent.value) }
    val p = LocalSaltPalette.current
    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = SaltDesign.shapeLarge,
            color = p.dialogSurface,
            shadowElevation = 18.dp,
            modifier = Modifier.onPointerEvent(PointerEventType.Press) { e -> e.changes.forEach { ch -> ch.consume() } }
        ) {
            Column(Modifier.width(480.dp).padding(20.dp)) {
                GroupHeader("字体大小")
                StepperRow(
                    label = "字体增加值",
                    value = if (extra <= 0) "默认" else "+" + extra + "%",
                    canMinus = extra > 0,
                    canPlus = extra < 60,
                    onMinus = { extra = (extra - 5).coerceAtLeast(0) },
                    onPlus = { extra = (extra + 5).coerceAtMost(60) }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "所有文字按相同比例放大，布局与间距不受影响；热力图的月份与星期标签固定大小，不跟随此设置",
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.subText
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("取消", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        onClick = {
                            OverlayInjection.fontExtraPercent.value = extra
                            UiStateStore.saveFontExtraPercent(extra)
                            onDismiss()
                        },
                        shape = SaltDesign.shapeSmall,
                        color = p.highlight,
                        modifier = Modifier.width(110.dp)
                    ) {
                        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                            Text("保存", fontSize = SaltDesign.fontSizeMain, color = p.onHighlight)
                        }
                    }
                }
            }
        }
    }
}

/** 明细保留天数编辑弹层 */
@Composable
private fun RetentionEditor(onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(StorageRule.rawEventKeepDays) }
    val p = LocalSaltPalette.current
    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = SaltDesign.shapeLarge,
            color = p.dialogSurface,
            shadowElevation = 18.dp,
            modifier = Modifier.onPointerEvent(PointerEventType.Press) { e -> e.changes.forEach { ch -> ch.consume() } }
        ) {
            Column(Modifier.width(480.dp).padding(20.dp)) {
                GroupHeader("明细保留天数")
                StepperRow(
                    label = "保留最近",
                    value = days.toString() + " 天",
                    canMinus = days > StorageRule.LIMIT_MIN_DAYS,
                    canPlus = days < StorageRule.LIMIT_MAX_DAYS,
                    onMinus = { days = (days - 7).coerceAtLeast(StorageRule.LIMIT_MIN_DAYS) },
                    onPlus = { days = (days + 7).coerceAtMost(StorageRule.LIMIT_MAX_DAYS) }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "超过该天数的原始播放明细会移到 expired 目录（不删除）；聚合数据永久保留",
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.subText
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("取消", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        onClick = {
                            StorageRule.save(days)
                            onDismiss()
                        },
                        shape = SaltDesign.shapeSmall,
                        color = p.highlight,
                        modifier = Modifier.width(110.dp)
                    ) {
                        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                            Text("保存", fontSize = SaltDesign.fontSizeMain, color = p.onHighlight)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    canMinus: Boolean,
    canPlus: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    val p = LocalSaltPalette.current
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = SaltDesign.fontSizeMain, color = p.text, modifier = Modifier.weight(1f))
        StepButton("−", canMinus, onMinus)
        Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
            Text(value, fontSize = SaltDesign.fontSizeMain, color = p.highlight)
        }
        StepButton("+", canPlus, onPlus)
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalSaltPalette.current
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeSmall,
        color = if (enabled) p.subBackground else Color.Transparent,
        modifier = Modifier.size(32.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                glyph,
                fontSize = SaltDesign.fontSizeMain,
                color = if (enabled) p.text else p.subText.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun InfoTab(
    tick: Int,
    onEditThreshold: () -> Unit,
    onEditRetention: () -> Unit,
    onEditFontSize: () -> Unit
) {
    val p = LocalSaltPalette.current
    val snap = remember(tick) { HostProbe.snapshot() }
    val dir = StatsStore.dataDirOrNull()?.absolutePath ?: "-"
    val trackCount = StatsStore.snapshot(1, 0).trackCount
    val eventLines = StatsStore.replayedLineCount()
    val bridgeOn = com.spwmods.waerwak.listenstats.ui.HostMaterialState.bridgeAvailable

    Column(Modifier.fillMaxWidth()) {
        // 两列瀑布流，各列内部紧挨
        // 左列：提示 → 插件 → 采集 → 宿主
        // 右列：显示 → 数据 → 诊断
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                // ------------------------------------------------ 提示（置顶）
                SaltGroup {
                    GroupHeader("提示")
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            "以下操作需在「模组管理 → 听歌统计」中完成：",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("· 导出 CSV / 备份数据 / 恢复数据 / 清空统计", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.height(2.dp))
                        Text("· 悬浮按钮的安装与卸载", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.height(2.dp))
                        Text("· 顶栏按钮的安装与卸载", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "打开统计页的全局快捷键需在「设置 → 键盘快捷键」中绑定（默认未绑定）",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.text
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "计数门槛、明细保留天数、字体大小、内容宽度可在本页调整",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 插件
                SaltGroup {
                    GroupHeader("插件")
                    ItemRow("名称", "听歌统计")
                    RowDivider()
                    ItemRow("版本", snap.pluginVersion)
                    RowDivider()
                    ItemRow("插件 ID", snap.pluginId)
                    RowDivider()
                    ItemRow("渠道", snap.channel)
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 采集（采集规则 + 实时状态）
                SaltGroup {
                    GroupHeader("采集")
                    AdjustableRow(
                        label = "计数门槛",
                        value = CountRule.describe(),
                        onClick = onEditThreshold
                    )
                    RowDivider()
                    ItemRow("播放状态", if (snap.isPlaying) "播放中" else "未播放")
                    RowDivider()
                    ItemRow("位置回调", snap.positionCallbacks.toString() + " 次")
                    RowDivider()
                    ItemRow("当前曲目", snap.lastTrackText)
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 宿主
                SaltGroup {
                    GroupHeader("宿主")
                    ItemRow("SPW 版本", snap.spwVersion)
                    RowDivider()
                    ItemRow("Kotlin", snap.kotlinVersion)
                    RowDivider()
                    ItemRow("Compose", snap.composeVersion)
                }
            }
            Column(Modifier.weight(1f)) {
                // ------------------------------------------------ 显示（字体 / 字号 / 宽度）
                SaltGroup {
                    GroupHeader("显示")
                    ItemRow("字体", HostFont.lastDiagnostics)
                    RowDivider()
                    AdjustableRow(
                        label = "字体大小",
                        value = if (OverlayInjection.fontExtraPercent.value <= 0) "默认"
                        else "+" + OverlayInjection.fontExtraPercent.value + "%",
                        onClick = onEditFontSize
                    )
                    RowDivider()
                    Row(
                        Modifier.fillMaxWidth().height(34.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "内容宽度上限",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            var w = 50
                            while (w <= 100) {
                                val target = w
                                SegmentButton(
                                    label = target.toString(),
                                    selected = OverlayInjection.contentPercent.value == target
                                ) {
                                    OverlayInjection.contentPercent.value = target
                                    UiStateStore.saveContentPercent(target)
                                }
                                w += 10
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 数据（统计量 + 保留策略 + 位置）
                SaltGroup {
                    GroupHeader("数据")
                    ItemRow("曲目数", trackCount.toString() + " 首")
                    RowDivider()
                    ItemRow("事件行数", eventLines.toString())
                    RowDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("存储占用", fontSize = SaltDesign.fontSizeMain, color = p.text)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            StatsStore.storageSummary(),
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                    }
                    RowDivider()
                    AdjustableRow(
                        label = "明细保留",
                        value = StorageRule.describe(),
                        onClick = onEditRetention
                    )
                    RowDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("数据目录", fontSize = SaltDesign.fontSizeMain, color = p.text)
                        Spacer(Modifier.height(4.dp))
                        Text(dir, fontSize = SaltDesign.fontSizeSub, color = p.subText)
                    }
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 诊断（运行状态）
                SaltGroup {
                    GroupHeader("诊断")
                    ItemRow(
                        "背景",
                        if (bridgeOn) {
                            if (HostAppearance.isMaterialStyle()) "跟随窗口材质（透明）" else "经典纯色"
                        } else {
                            "降级：桥接层未生效，使用纯色背景"
                        }
                    )
                    RowDivider()
                    ItemRow(
                        "材质桥接",
                        if (bridgeOn) {
                            "已生效 · 隐藏=" + com.spwmods.waerwak.listenstats.ui.HostMaterialState.hidden
                        } else {
                            "未生效（replaceComposable 未进入组合）"
                        }
                    )
                    RowDivider()
                    ItemRow("封面缓存", com.spwmods.waerwak.listenstats.ui.CoverCache.statsText())
                    RowDivider()
                    ItemRow("标题栏按钮", com.spwmods.waerwak.listenstats.ui.TitlebarButton.statusText())
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // ------------------------------------------------ 权限（全宽）
        SaltGroup {
            GroupHeader("权限")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionChip("读取音乐库", "获取曲目信息", snap.libraryRead, 0, Modifier.weight(1f))
                PermissionChip("全局快捷键", "打开统计面板", snap.keyBindings, 1, Modifier.weight(1f))
                PermissionChip("界面注入", "显示统计浮层", snap.classTransform, 2, Modifier.weight(1f))
            }
            RowDivider()
            Text(
                "亮色 = 已授权，暗色 = 未授权",
                fontSize = SaltDesign.fontSizeSub,
                color = p.subText,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
    }
}

/** 「标签 + 当前值 + 调整按钮」一行（计数门槛 / 明细保留 / 字体大小共用） */
@Composable
private fun AdjustableRow(label: String, value: String, onClick: () -> Unit) {
    val p = LocalSaltPalette.current
    Row(
        Modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = SaltDesign.fontSizeMain,
            color = p.text,
            modifier = Modifier.weight(1f)
        )
        Text(value, fontSize = SaltDesign.fontSizeSub, color = p.subText)
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onClick,
            shape = SaltDesign.shapeSmall,
            color = p.highlight.copy(alpha = 0.16f)
        ) {
            Box(
                Modifier.height(24.dp).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("调整", fontSize = SaltDesign.fontSizeSub, color = p.highlight)
            }
        }
    }
}

/** 权限卡片：与顶部 Tab 同一种卡片样式，已授权 = 强调色激活态 */
@Composable
private fun PermissionChip(
    label: String,
    purpose: String,
    granted: Boolean,
    iconKind: Int,
    modifier: Modifier = Modifier
) {
    val p = LocalSaltPalette.current
    val tint = if (granted) p.highlight else p.subText.copy(alpha = 0.65f)
    Surface(
        shape = SaltDesign.shapeMedium,
        color = if (granted) p.highlight.copy(alpha = 0.10f) else Color.Transparent,
        border = BorderStroke(
            1.dp,
            SolidColor(if (granted) p.highlight else p.subText.copy(alpha = 0.22f))
        ),
        modifier = modifier
    ) {
        Row(
            Modifier.height(50.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PermIcon(iconKind, tint)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, fontSize = SaltDesign.fontSizeMain, color = tint)
                Spacer(Modifier.height(2.dp))
                Text(
                    purpose,
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.subText.copy(alpha = if (granted) 1f else 0.6f)
                )
            }
        }
    }
}

/** 权限图标：0 音符 / 1 键盘 / 2 图层 */
@Composable
private fun PermIcon(kind: Int, color: Color) {
    Box(
        Modifier.size(20.dp).drawBehind {
            val s = size.minDimension
            val stroke = s * 0.1f
            when (kind) {
                0 -> {
                    // 音符
                    drawCircle(color, radius = s * 0.13f, center = Offset(s * 0.3f, s * 0.78f))
                    drawRect(color, Offset(s * 0.4f, s * 0.12f), Size(stroke * 1.4f, s * 0.68f))
                    drawRect(color, Offset(s * 0.4f, s * 0.12f), Size(s * 0.42f, stroke * 1.6f))
                }
                1 -> {
                    // 键盘：外框 + 三个小键
                    drawRoundRect(
                        color,
                        topLeft = Offset(0f, s * 0.26f),
                        size = Size(s, s * 0.48f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.1f),
                        style = Stroke(width = stroke)
                    )
                    drawRect(color, Offset(s * 0.16f, s * 0.46f), Size(s * 0.14f, stroke * 1.4f))
                    drawRect(color, Offset(s * 0.42f, s * 0.46f), Size(s * 0.14f, stroke * 1.4f))
                    drawRect(color, Offset(s * 0.68f, s * 0.46f), Size(s * 0.14f, stroke * 1.4f))
                }
                else -> {
                    // 图层：两个错位矩形
                    drawRoundRect(
                        color,
                        topLeft = Offset(s * 0.06f, s * 0.1f),
                        size = Size(s * 0.7f, s * 0.5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f),
                        style = Stroke(width = stroke)
                    )
                    drawRoundRect(
                        color,
                        topLeft = Offset(s * 0.26f, s * 0.4f),
                        size = Size(s * 0.7f, s * 0.5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f),
                        style = Stroke(width = stroke)
                    )
                }
            }
        }
    )
}

/** SaltUI Item：左侧标题 + 右侧值 */
@Composable
private fun ItemRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = SaltDesign.fontSizeMain,
            color = LocalSaltPalette.current.text,
            modifier = Modifier.weight(1f)
        )
        Text(value, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
    }
}

// ---------------------------------------------------------------- 通用小组件

/** SaltUI Item 风格的表头：强调色竖条 + 大标题 + 两列右对齐 */
@Composable
private fun ListHeader(mainTitle: String, value1: String, value2: String, value3: String = "") {
    Row(
        Modifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = SaltDesign.shapeSmall,
            color = LocalSaltPalette.current.highlight,
            modifier = Modifier.size(width = 4.dp, height = 18.dp)
        ) {}
        Spacer(Modifier.width(10.dp))
        Text(
            mainTitle,
            fontSize = 20.sp,
            color = LocalSaltPalette.current.text,
            modifier = Modifier.weight(1f)
        )
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
            Text(value1, fontSize = 16.sp, color = LocalSaltPalette.current.subText)
        }
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
            Text(value2, fontSize = 16.sp, color = LocalSaltPalette.current.subText)
        }
        if (value3.isNotEmpty()) {
            Box(Modifier.width(112.dp), contentAlignment = Alignment.CenterEnd) {
                Text(value3, fontSize = 16.sp, color = LocalSaltPalette.current.subText)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** 曲目封面（无封面时显示占位音符） */
@Composable
private fun TrackCover(coverId: String, diameter: androidx.compose.ui.unit.Dp) {
    val p = LocalSaltPalette.current
    val bitmap = rememberCover(coverId)
    Surface(
        shape = SaltDesign.shapeCover,
        color = p.subBackground,
        modifier = Modifier.size(diameter)
    ) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                if (bitmap != null) {
                    drawImage(
                        image = bitmap,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    )
                } else {
                    val s = size.minDimension
                    val c = p.subText.copy(alpha = 0.30f)
                    drawCircle(c, radius = s * 0.13f, center = Offset(s * 0.38f, s * 0.68f))
                    drawRect(c, Offset(s * 0.46f, s * 0.24f), Size(s * 0.07f, s * 0.46f))
                    drawRect(c, Offset(s * 0.46f, s * 0.24f), Size(s * 0.24f, s * 0.08f))
                }
            }
        ) {}
    }
}

/** SaltUI Item 风格的两行条目：序号 + 封面 + 标题/副标题 + 右侧两列数值 */
@Composable
private fun ListRow(
    rank: Int,
    coverId: String,
    title: String,
    summary: String,
    count: String,
    duration: String,
    lastPlayed: String = "",
    trackKey: String,
    onClick: (String) -> Unit,
    onRowClick: (() -> Unit)? = null
) {
    Surface(
        onClick = {
            if (onRowClick != null) {
                onRowClick()
            } else if (trackKey.isNotBlank()) {
                onClick(trackKey)
            }
        },
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                rank.toString(),
                fontSize = SaltDesign.fontSizeSub,
                color = LocalSaltPalette.current.subText,
                modifier = Modifier.width(24.dp)
            )
            TrackCover(coverId, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    title,
                    fontSize = SaltDesign.fontSizeMain,
                    lineHeight = 17.sp,
                    color = LocalSaltPalette.current.text
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    summary,
                    fontSize = SaltDesign.fontSizeSub,
                    lineHeight = 14.sp,
                    color = LocalSaltPalette.current.subText
                )
            }
            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                Text(count, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.text)
            }
            Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                Text(duration, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
            }
            if (lastPlayed.isNotEmpty()) {
                Box(Modifier.width(112.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(lastPlayed, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
                }
            }
        }
    }
}



@Composable
private fun Pager(
    page: Int,
    pageCount: Int,
    onPage: (Int) -> Unit,
    total: Int,
    pageSize: Int = 10,
    onPageSize: ((Int) -> Unit)? = null
) {
    var sizeOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "共 " + total + " 条",
            fontSize = SaltDesign.fontSizeSub,
            color = LocalSaltPalette.current.subText
        )
        if (onPageSize != null) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.height(26.dp)) {
                Surface(
                    onClick = { sizeOpen = !sizeOpen },
                    shape = SaltDesign.shapeSmall,
                    color = LocalSaltPalette.current.subBackground
                ) {
                    Row(
                        Modifier.height(26.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pageSize.toString() + " 条/页",
                            fontSize = SaltDesign.fontSizeSub,
                            color = LocalSaltPalette.current.text
                        )
                        Spacer(Modifier.width(5.dp))
                        ChevronGlyph(left = false, color = LocalSaltPalette.current.subText, rotateDown = true)
                    }
                }
                DropdownMenu(
                    expanded = sizeOpen,
                    onDismissRequest = { sizeOpen = false },
                    modifier = Modifier.width(120.dp),
                    containerColor = LocalSaltPalette.current.dialogSurface
                ) {
                    var oi = 0
                    while (oi < pageSizeOptions.size) {
                        val size = pageSizeOptions[oi]
                        DropdownMenuItem(
                            text = {
                                Text(
                                    size.toString() + " 条/页",
                                    fontSize = SaltDesign.fontSizeSub,
                                    color = if (size == pageSize) LocalSaltPalette.current.highlight
                                    else LocalSaltPalette.current.text
                                )
                            },
                            onClick = {
                                sizeOpen = false
                                onPageSize(size)
                            }
                        )
                        oi++
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        SmallButton("上一页", page > 0) { onPage(page - 1) }
        Spacer(Modifier.width(8.dp))
        Text(
            (page + 1).toString() + " / " + pageCount,
            fontSize = SaltDesign.fontSizeSub,
            color = LocalSaltPalette.current.subText
        )
        Spacer(Modifier.width(8.dp))
        SmallButton("下一页", page < pageCount - 1) { onPage(page + 1) }
    }
}

private val pageSizeOptions = intArrayOf(10, 15, 20, 30, 50)


/** 下划线式 Tab（第一层：歌曲/专辑/艺术家） */
@Composable
private fun UnderlineTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 42.dp,
    onClick: () -> Unit
) {
    val p = LocalSaltPalette.current
    Surface(onClick = onClick, color = Color.Transparent, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    fontSize = SaltDesign.fontSizeMain,
                    color = if (selected) p.highlight else p.subText
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = if (selected) p.highlight else p.subText.copy(alpha = 0.15f)
            ) {}
        }
    }
}

/** 实心小 chip（第二层：排序方式） */
@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val p = LocalSaltPalette.current
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeSmall,
        color = if (selected) p.highlight else p.subBackground,
        modifier = modifier
    ) {
        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeSub,
                color = if (selected) p.onHighlight else p.subText
            )
        }
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeInner,
        color = if (selected) LocalSaltPalette.current.highlight else LocalSaltPalette.current.subBackground
    ) {
        Box(
            Modifier.height(SaltDesign.segmentHeight).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeSub,
                color = if (selected) LocalSaltPalette.current.onHighlight else LocalSaltPalette.current.text
            )
        }
    }
}

/** 小号文字按钮（分页、关闭等） */
@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = SaltDesign.shapeSmall,
        color = if (enabled) LocalSaltPalette.current.subBackground else Color.Transparent
    ) {
        Box(
            Modifier.height(26.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeSub,
                color = if (enabled) LocalSaltPalette.current.text else LocalSaltPalette.current.subText.copy(alpha = 0.35f)
            )
        }
    }
}


@Composable
private fun EmptyHint(text: String) {
    Text(text, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
    Spacer(Modifier.height(4.dp))
}


@Composable
private fun FooterBar() {
    Text(
        "事件日志 " + StatsStore.replayedLineCount() + " 行",
        fontSize = 10.sp,
        color = LocalSaltPalette.current.subText
    )
}

private fun weekdayText(date: String): String = try {
    val d = LocalDate.parse(date)
    when (d.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
} catch (t: Throwable) {
    ""
}

private fun formatLastPlayed(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
    return fmt.format(Date(timestamp))
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0 分"
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    if (hours > 0L && minutes > 0L) return hours.toString() + " 小时 " + minutes + " 分"
    if (hours > 0L) return hours.toString() + " 小时"
    return minutes.toString() + " 分钟"
}