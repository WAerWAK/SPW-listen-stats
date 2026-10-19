@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle
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
import com.spwmods.waerwak.listenstats.stats.DayTrackAgg
import com.spwmods.waerwak.listenstats.stats.PlayEvent
import com.spwmods.waerwak.listenstats.ui.HostAppearance
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDarkPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import com.spwmods.waerwak.listenstats.ui.UiStateStore
import com.spwmods.waerwak.listenstats.ui.WallpaperLoader
import com.spwmods.waerwak.listenstats.ui.rememberCover
import com.spwmods.waerwak.listenstats.ui.SaltLightPalette
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
 * Hook 目标用未混淆的可读名：不能直接 hook `Content`（AppNavigationKt 内有 2 处调用），
 * 因此 hook 编译器生成的访问器 `access$Content`（ComposableSingletons$AppNavigationKt 内唯一）。
 */
object OverlayInjection {

    private const val TARGET_CLASS = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"
    private const val TARGET_METHOD = "access\$Content"
    private const val TARGET_IN_CLASS =
        "com.xuncorp.voxzen.ui.navigation.ComposableSingletons\$AppNavigationKt"

    @Volatile
    private var handle: HookHandle? = null

    /** 面板开关状态（提到 object 级，快捷键才能切换） */
    val panelOpen = mutableStateOf(false)

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

    fun targetSummary(): String = "$TARGET_CLASS.$TARGET_METHOD  @  $TARGET_IN_CLASS"

    @JvmStatic
    @JvmName("installOverlayFromConfig")
    fun installOverlayFromConfig() {
        val message = install()
        toast(message, message.startsWith("已注册"))
    }

    @JvmStatic
    @JvmName("uninstallOverlayFromConfig")
    fun uninstallOverlayFromConfig() {
        val was = handle != null
        uninstall()
        toast(if (was) "已卸载 Overlay 注入" else "当前没有注册中的 Overlay", true)
    }

    /** 配置页入口：打开统计面板并直接定位到「信息」页（计数门槛在那调整） */
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
            lastError = null
            "已注册：窗口右下角会出现一个「只露一点」的圆"
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            "失败：${t.javaClass.simpleName} — ${t.message ?: ""}"
        }
    }

    fun uninstall() {
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

    /** 弹出对话框：从备份恢复 */
    @JvmStatic
    @JvmName("restoreDataDialog")
    fun restoreDataDialog() {
        javax.swing.SwingUtilities.invokeLater {
            try {
                val fd = java.awt.FileDialog(null as java.awt.Frame?, "选择备份文件", java.awt.FileDialog.LOAD)
                fd.isVisible = true
                val dir = fd.directory
                val name = fd.file
                if (dir != null && name != null) {
                    val result = StatsStore.restoreFrom(java.io.File(dir, name))
                    toast(result, result.startsWith("已恢复"))
                }
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

    // 同步宿主的深浅色
    val dark = HostAppearance.isDarkTheme()
    val palette = if (dark) SaltDarkPalette else SaltLightPalette

    MaterialTheme(colorScheme = darkColorScheme(primary = palette.highlight)) {
        CompositionLocalProvider(
            LocalSaltPalette provides palette
        ) {
            if (opened) {
                StatsOverlay(onClose = {
                    OverlayInjection.panelOpen.value = false
                    // 关闭时同时复位 hover，否则圆钮会卡在“已弹出”状态
                    hovered = false
                })
            } else {
                // ❗ 关键：未打开时不能有任何不透明背景，否则会盖住宿主页面
                Box(Modifier.fillMaxSize()) {
                    EdgeEntry(
                        hovered = hovered,
                        onHoverChange = { hovered = it },
                        onClick = { OverlayInjection.panelOpen.value = true }
                    )
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
    var dayDetailDate by remember { mutableStateOf("") }

    // Esc：AWT 层拦截（逐层关闭：弹层 → 面板）
    EscGate.handler = {
        if (showThresholdEditor) {
            showThresholdEditor = false
        } else if (showRetentionEditor) {
            showRetentionEditor = false
        } else if (dayDetailDate.isNotEmpty()) {
            dayDetailDate = ""
        } else if (TrackDetailState.open.value != null) {
            TrackDetailState.open.value = null
        } else if (CalState.open.value) {
            CalState.open.value = false
        } else {
            onClose()
        }
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

    Surface(modifier = Modifier.fillMaxSize(), color = LocalSaltPalette.current.background) {
    Box(
        Modifier.fillMaxSize()
            .focusRequester(escRequester)
            .focusTarget()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) {
                    false
                } else {
                    if (showThresholdEditor) {
                        showThresholdEditor = false
                    } else if (showRetentionEditor) {
                        showRetentionEditor = false
                    } else if (dayDetailDate.isNotEmpty()) {
                        dayDetailDate = ""
                    } else if (TrackDetailState.open.value != null) {
                        TrackDetailState.open.value = null
                    } else if (CalState.open.value) {
                        CalState.open.value = false
                    } else {
                        onClose()
                    }
                    true
                }
            }
    ) {
    val wallpaper = remember { WallpaperLoader.load() }
    if (wallpaper != null) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawImage(
                    image = wallpaper,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
            }
        )
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (LocalSaltPalette.current.isDark) Color(0xD90B0E14) else Color(0xD9FAFAFA)
        ) {}
    }
    // 底部留 76dp：避开浮动胶囊迷你播放条（面板内容不被遮挡）
    Column(
        Modifier.fillMaxSize().padding(
            start = SaltDesign.padding,
            end = SaltDesign.padding,
            top = SaltDesign.padding,
            bottom = 87.dp
        )
    ) {
        HeaderBar(onClose = onClose, onRefresh = { tick = tick + 1 })
        Spacer(Modifier.height(12.dp))
        TabBar(selected = tab, onSelect = { tab = it })
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            ScrollArea(resetKey = tab) {
                when (tab) {
                    0 -> OverviewTab(liveKey)
                    1 -> RankingTab(liveKey)
                    2 -> CalendarTab(tick, onSelectDay = { dayDetailDate = it })
                    else -> InfoTab(
                        liveKey,
                        onEditThreshold = { showThresholdEditor = true },
                        onEditRetention = { showRetentionEditor = true }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FooterBar()
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
    if (dayDetailDate.isNotEmpty()) {
        DayDetailDialog(date = dayDetailDate, onDismiss = { dayDetailDate = "" })
    }
    val detailKey = TrackDetailState.open.value
    if (detailKey != null) {
        TrackDetailDialog(trackKey = detailKey, onDismiss = { TrackDetailState.open.value = null })
    }
    if (CalState.open.value) {
        CalendarPicker(
            gran = CalState.gran.value,
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
    val snapshot = remember(tick) { StatsStore.snapshot(5, 5) }
    val streak = remember(tick) { StatsStore.currentStreak() }

    Column(Modifier.fillMaxWidth()) {
        SaltGroup {
            GroupHeader("总览")
            Row(Modifier.fillMaxWidth()) {
                KpiCell("总播放次数", snapshot.totalCount.toString(), Modifier.weight(1f))
                KpiCell("累计收听", formatMs(snapshot.totalMs), Modifier.weight(1f))
                KpiCell("曲目数", snapshot.trackCount.toString(), Modifier.weight(1f))
                KpiCell("连续收听", streak.toString() + " 天", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SaltGroup {
                    GroupHeader("最常听 Top 5")
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
            shape = SaltDesign.shapeSmall,
            color = LocalSaltPalette.current.highlight,
            modifier = Modifier.size(width = 4.dp, height = 22.dp)
        ) {}
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = 26.sp,
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
        Text(label, fontSize = 20.sp, color = LocalSaltPalette.current.labelAccent)
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            fontSize = 32.sp,
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

@Composable
private fun RowDivider() {
    Surface(
        modifier = Modifier.fillMaxWidth().height(1.dp),
        color = LocalSaltPalette.current.subText.copy(alpha = 0.08f)
    ) {}
}

// ---------------------------------------------------------------- Tab: 排行（歌曲 / 专辑 / 艺术家 合并）

@Composable
private fun RankingTab(tick: Int) {
    var kind by remember { mutableStateOf(0) }
    var sortBy by remember { mutableStateOf(0) }
    var range by remember { mutableStateOf(0) }
    var songsPage by remember { mutableStateOf(0) }
    var albumsPage by remember { mutableStateOf(0) }
    var artistsPage by remember { mutableStateOf(0) }
    val today = LocalDate.now()
    val sinceDate: String? = when (range) {
        1 -> today.minusDays(30).toString()
        2 -> today.minusDays(7).toString()
        else -> null
    }

    Column(Modifier.fillMaxWidth()) {
        // 上排：榜单切换（居中，占 3/5 宽度）
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth(0.6f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RangeTab("总榜", range == 0, Modifier.weight(1f)) { range = 0; songsPage = 0; albumsPage = 0; artistsPage = 0 }
                RangeTab("月榜", range == 1, Modifier.weight(1f)) { range = 1; songsPage = 0; albumsPage = 0; artistsPage = 0 }
                RangeTab("周榜", range == 2, Modifier.weight(1f)) { range = 2; songsPage = 0; albumsPage = 0; artistsPage = 0 }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 下排：视图（1/2）+ 排序（1/2）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                UnderlineTab("歌曲", kind == 0, Modifier.weight(1f), height = 32.dp) { kind = 0 }
                UnderlineTab("专辑", kind == 1, Modifier.weight(1f), height = 32.dp) { kind = 1 }
                UnderlineTab("艺术家", kind == 2, Modifier.weight(1f), height = 32.dp) { kind = 2 }
            }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip("次数", sortBy == 0, Modifier.weight(1f)) { sortBy = 0 }
                SortChip("最近", sortBy == 1, Modifier.weight(1f)) { sortBy = 1 }
                SortChip("时长", sortBy == 2, Modifier.weight(1f)) { sortBy = 2 }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (kind) {
            0 -> TrackListView(songsPage, tick, sortBy, sinceDate, "") { songsPage = it }
            1 -> AggregateListView(true, albumsPage, tick, sinceDate, "") { albumsPage = it }
            else -> AggregateListView(false, artistsPage, tick, sinceDate, "") { artistsPage = it }
        }
    }
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
    onPage: (Int) -> Unit
) {
    val data = remember(page, tick, sortBy, sinceDate, query) {
        StatsStore.trackPageRange(page, PAGE_SIZE, sortBy, query, sinceDate)
    }
    Column(Modifier.fillMaxWidth()) {
        SaltGroup {
            ListHeader("曲目", "次数", "时长")
            if (data.items.isEmpty()) {
                EmptyHint("还没有数据：听几首歌再来")
            }
            var i = 0
            while (i < data.items.size) {
                if (i > 0) RowDivider()
                val t = data.items[i]
                ListRow(
                    rank = page * PAGE_SIZE + i + 1,
                    coverId = t.id,
                    title = t.title.ifBlank { "未知曲目" },
                    summary = t.artist + (if (t.album.isBlank()) "" else " · " + t.album),
                    count = t.count.toString() + " 次",
                    duration = formatMs(t.listenedMs),
                    trackKey = t.key,
                    onClick = { key -> TrackDetailState.open.value = key }
                )
                i++
            }
        }
        Spacer(Modifier.height(10.dp))
        Pager(page = page, pageCount = data.pageCount, onPage = onPage, total = data.total)
    }
}

@Composable
private fun AggregateListView(
    byAlbum: Boolean,
    page: Int,
    tick: Int,
    sinceDate: String?,
    query: String,
    onPage: (Int) -> Unit
) {
    val data = remember(byAlbum, page, tick, sinceDate, query) {
        StatsStore.aggregatePageRange(page, PAGE_SIZE, byAlbum, sinceDate, query)
    }
    Column(Modifier.fillMaxSize()) {
        SaltGroup {
            ListHeader(if (byAlbum) "专辑" else "艺术家", "次数", "时长")
            if (data.items.isEmpty()) {
                EmptyHint(if (byAlbum) "还没有专辑数据" else "还没有艺术家数据")
            }
            var i = 0
            while (i < data.items.size) {
                if (i > 0) RowDivider()
                val row = data.items[i]
                ListRow(
                    rank = page * PAGE_SIZE + i + 1,
                    coverId = row.coverId,
                    title = row.name,
                    summary = row.trackCount.toString() + " 首曲目",
                    count = row.count.toString() + " 次",
                    duration = formatMs(row.listenedMs),
                    trackKey = "",
                    onClick = { }
                )
                i++
            }
        }
        Spacer(Modifier.height(10.dp))
        Pager(page = page, pageCount = data.pageCount, onPage = onPage, total = data.total)
    }
}

// ---------------------------------------------------------------- Tab: 日历

@Composable
private fun CalendarTab(tick: Int, onSelectDay: (String) -> Unit) {
    // 近一年：53 周 × 7 天，列首周一、列尾周日
    val days = remember(tick) { StatsStore.recentDays(400) }
    // 柱状图状态（提升到这里，日历结果才能回写）
    var gran by remember { mutableStateOf(1) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var byTime by remember { mutableStateOf(false) }
    val pickResult = CalState.result.value
    LaunchedEffect(pickResult) {
        if (pickResult != null) {
            anchor = pickResult
            CalState.result.value = null
        }
    }
    val map = HashMap<String, DayAgg>()
    var i = 0
    while (i < days.size) {
        map[days[i].date] = days[i]
        i++
    }

    var hoverText by remember { mutableStateOf("把鼠标移到格子上查看当天数据") }
    var widthPx by remember { mutableStateOf(0) }

    val density = LocalDensity.current
    val labelColDp = 24f
    val availableDp = if (widthPx > 0) {
        widthPx.toFloat() / density.density - labelColDp
    } else {
        0f
    }

    // 固定最近一年：一年前对齐到周一 ~ 今天所在周的周日
    val today = LocalDate.now()
    val oneYearAgo = today.minusYears(1)
    val fullStartMonday = oneYearAgo.minusDays((oneYearAgo.dayOfWeek.value - 1).toLong())
    val endSunday = today.plusDays((7 - today.dayOfWeek.value).toLong())
    val fullWeeks = ((endSunday.toEpochDay() - fullStartMonday.toEpochDay()) / 7L).toInt() + 1

    // 宽度不够时减少周数（从最近往前截断），格子尺寸不变
    var weeks = fullWeeks
    if (availableDp > 0f) {
        val fit = (availableDp / 19f).toInt()
        if (fit < weeks) weeks = if (fit < 8) 8 else fit
    }
    val startMonday = endSunday.minusDays((weeks - 1L) * 7L + 6L)

    val cell = 16.dp
    val gap = 3.dp
    val gridWidth = (weeks * 19 - 3).dp
    val p = LocalSaltPalette.current

    Column(Modifier.fillMaxSize()) {
        SectionTitle("每日听歌活跃度（最近一年）")
        Box(Modifier.fillMaxWidth().onGloballyPositioned { widthPx = it.size.width }) {
            Row {
                // 左侧星期标签（一/三/五）
                Column(
                    Modifier.width(20.dp)
                        .padding(top = 22.dp)
                        .height(cell * 7 + gap * 6),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    var d = 0
                    while (d < 7) {
                        val label = when (d) {
                            0 -> "一"
                            2 -> "三"
                            4 -> "五"
                            else -> ""
                        }
                        Text(label, fontSize = 10.sp, color = p.subText)
                        d++
                    }
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    // 顶部月份标签
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        var w = 0
                        var lastMonth = -1
                        while (w < weeks) {
                            val m = startMonday.plusDays(w * 7L).monthValue
                            Box(Modifier.width(cell).height(18.dp)) {
                                if (m != lastMonth) {
                                    Text(m.toString(), fontSize = 10.sp, lineHeight = 12.sp, color = p.subText)
                                }
                            }
                            lastMonth = m
                            w++
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 格子矩阵
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        var w = 0
                        while (w < weeks) {
                            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                var d = 0
                                while (d < 7) {
                                    val date = startMonday.plusDays(w * 7L + d)
                                    if (date.isAfter(today) || date.isBefore(oneYearAgo)) {
                                        Box(Modifier.size(cell))
                                    } else {
                                        val key = date.toString()
                                        val agg = map[key]
                                        val count = if (agg == null) 0 else agg.count
                                        val ms = if (agg == null) 0L else agg.listenedMs
                                        Surface(
                                            onClick = { onSelectDay(key) },
                                            shape = SaltDesign.shapeSmall,
                                            color = heatColor(count, p.isDark),
                                            modifier = Modifier
                                                .size(cell)
                                                .onPointerEvent(PointerEventType.Enter) {
                                                    hoverText = key + " · " + count + " 次 · " + formatMs(ms) + "（点击看明细）"
                                                }
                                        ) {}
                                    }
                                    d++
                                }
                            }
                            w++
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 悬浮提示 + 「少→多」图例同一行，宽度与矩阵一致
                    Row(
                        Modifier.width(gridWidth),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(hoverText, fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.weight(1f))
                        Text("少", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                        Spacer(Modifier.width(6.dp))
                        var level = 0
                        while (level < 5) {
                            Surface(
                                shape = SaltDesign.shapeSmall,
                                color = heatColor(levelSamples[level], p.isDark),
                                modifier = Modifier.padding(end = 3.dp).size(12.dp)
                            ) {}
                            level++
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("多", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.width(gridWidth)) {
            SaltGroup {
                GroupHeader("收听时段")
                HourChart(
                    width = gridWidth,
                    tick = tick,
                    gran = gran,
                    anchor = anchor,
                    byTime = byTime,
                    onGran = { gran = it; anchor = LocalDate.now() },
                    onAnchor = { anchor = it },
                    onByTime = { byTime = it }
                )
            }
        }
    }
}

/** 24 小时分布柱状图（日/周/月/年 + 次数/时长 + 日期导航） */
@Composable
private fun HourChart(
    width: androidx.compose.ui.unit.Dp,
    tick: Int,
    gran: Int,
    anchor: LocalDate,
    byTime: Boolean,
    onGran: (Int) -> Unit,
    onAnchor: (LocalDate) -> Unit,
    onByTime: (Boolean) -> Unit
) {
    val p = LocalSaltPalette.current
    val today = LocalDate.now()

    var from = today
    var to = today
    var label = ""
    var weekDates: Array<String>? = null
    when (gran) {
        0 -> {
            from = anchor
            to = anchor
            label = anchor.toString() + "（" + weekdayText(anchor.toString()) + "）"
        }
        1 -> {
            val mon = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
            from = mon
            to = mon.plusDays(6)
            label = mon.toString() + " ~ " + to.toString()
            val arr = Array(7) { "" }
            var d = 0
            while (d < 7) {
                arr[d] = mon.plusDays(d.toLong()).toString()
                d++
            }
            weekDates = arr
        }
        2 -> {
            from = anchor.withDayOfMonth(1)
            to = anchor.withDayOfMonth(anchor.lengthOfMonth())
            label = anchor.year.toString() + " 年 " + anchor.monthValue + " 月"
        }
        else -> {
            from = anchor.withDayOfYear(1)
            to = anchor.withDayOfYear(anchor.lengthOfYear())
            label = anchor.year.toString() + " 年"
        }
    }

    val profile = remember(gran, anchor, tick) {
        StatsStore.hourProfile(from.toString(), to.toString())
    }
    val matrix = if (weekDates == null) null else remember(gran, anchor, tick) {
        StatsStore.hourMatrix(weekDates!!)
    }

    val values = FloatArray(24)
    var max = 0f
    var i = 0
    while (i < 24) {
        val v = if (byTime) profile.ms[i].toFloat() else profile.counts[i].toFloat()
        values[i] = v
        if (v > max) max = v
        i++
    }

    var started by remember(gran, anchor, byTime) { mutableStateOf(false) }
    LaunchedEffect(gran, anchor, byTime) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 380),
        label = "hour-appear"
    )

    val hovered = HourHover.index.value

    Column(Modifier.width(width)) {
        // 粒度切换（Tab 卡片样式，无图标）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var g = 0
            while (g < 4) {
                val idx = g
                val active = gran == idx
                Surface(
                    onClick = { onGran(idx) },
                    shape = SaltDesign.shapeMedium,
                    color = if (active) p.subBackground else Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        SolidColor(if (active) p.highlight else p.subText.copy(alpha = 0.18f))
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) {
                        Text(
                            granLabels[idx],
                            fontSize = SaltDesign.fontSizeMain,
                            color = if (active) p.highlight else p.subText
                        )
                    }
                }
                g++
            }
        }
        Spacer(Modifier.height(10.dp))
        // 左侧日期导航 + 右侧次数/时长（同一行）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NavBar(
                label = label,
                canNext = to.isBefore(today),
                onPrev = { onAnchor(shiftAnchor(gran, anchor, -1L)) },
                onNext = { if (to.isBefore(today)) onAnchor(shiftAnchor(gran, anchor, 1L)) },
                onPick = if (gran == 3) null else {
                    {
                        CalState.gran.value = gran
                        CalState.seed.value = anchor
                        CalState.open.value = true
                    }
                },
                modifier = Modifier.weight(2f)
            )
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f)) {
                UnderlineTab("次数", !byTime, Modifier.weight(1f), height = 32.dp) { onByTime(false) }
                UnderlineTab("时长", byTime, Modifier.weight(1f), height = 32.dp) { onByTime(true) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().height(126.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            var h = 0
            while (h < 24) {
                val stack = if (matrix == null) null else buildDayStack(matrix, h)
                var total = 0
                if (stack != null) {
                    var d = 0
                    while (d < stack.size) {
                        total += stack[d]
                        d++
                    }
                }
                HourBar(
                    index = h,
                    ratio = if (max > 0f) values[h] / max else 0f,
                    progress = progress,
                    text = if (byTime) shortMs(profile.ms[h]) else profile.counts[h].toString(),
                    stack = stack,
                    stackTotal = total,
                    highlight = hovered == h,
                    modifier = Modifier.weight(1f)
                )
                h++
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            var h2 = 0
            while (h2 < 24) {
                val text = when (h2) {
                    0 -> "0"
                    6 -> "6"
                    12 -> "12"
                    18 -> "18"
                    23 -> "23"
                    else -> ""
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (text.isNotEmpty()) {
                        Text(text, fontSize = 10.sp, color = p.subText)
                    }
                }
                h2++
            }
        }
        Spacer(Modifier.height(6.dp))
        if (hovered >= 0 && hovered < 24) {
            Text(
                hovered.toString() + " 点 · " + profile.counts[hovered] + " 次 · " + formatMs(profile.ms[hovered]),
                fontSize = SaltDesign.fontSizeMain,
                color = p.highlight
            )
        } else {
            Text(
                "把鼠标移到柱子上查看该时段详情",
                fontSize = SaltDesign.fontSizeSub,
                color = p.subText
            )
        }
        if (matrix != null) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var d = 0
                while (d < 7) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = p.highlight.copy(alpha = 0.22f + 0.11f * d),
                            shape = SaltDesign.shapeSmall,
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Spacer(Modifier.width(4.dp))
                        Text(weekdayShort(d), fontSize = 10.sp, color = p.subText)
                    }
                    d++
                }
            }
        }
    }
}


/** 日期导航条：‹ [标签 📅] ›，中间点击打开日历 */
@Composable
private fun NavBar(
    label: String,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val p = LocalSaltPalette.current
    Surface(shape = SaltDesign.shapeMedium, color = p.subBackground, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().height(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onPrev,
                shape = SaltDesign.shapeSmall,
                color = Color.Transparent,
                modifier = Modifier.width(30.dp).height(32.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ChevronGlyph(left = true, color = p.subText)
                }
            }
            if (onPick != null) {
                Surface(
                    onClick = onPick,
                    shape = SaltDesign.shapeSmall,
                    color = Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(label, fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.width(6.dp))
                        CalendarGlyph(p.highlight.copy(alpha = 0.9f))
                    }
                }
            } else {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = SaltDesign.fontSizeSub, color = p.text)
                }
            }
            Surface(
                onClick = onNext,
                shape = SaltDesign.shapeSmall,
                color = Color.Transparent,
                modifier = Modifier.width(30.dp).height(32.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ChevronGlyph(
                        left = false,
                        color = if (canNext) p.subText else p.subText.copy(alpha = 0.25f)
                    )
                }
            }
        }
    }
}

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

/** 左右箭头（自绘 V 形） */
@Composable
private fun ChevronGlyph(left: Boolean, color: Color) {
    Box(
        Modifier.size(14.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.12f
            if (left) {
                drawLine(color, Offset(s * 0.62f, s * 0.15f), Offset(s * 0.30f, s * 0.5f), strokeWidth = w)
                drawLine(color, Offset(s * 0.30f, s * 0.5f), Offset(s * 0.62f, s * 0.85f), strokeWidth = w)
            } else {
                drawLine(color, Offset(s * 0.38f, s * 0.15f), Offset(s * 0.70f, s * 0.5f), strokeWidth = w)
                drawLine(color, Offset(s * 0.70f, s * 0.5f), Offset(s * 0.38f, s * 0.85f), strokeWidth = w)
            }
        }
    )
}

/** 柱状图当前悬停的小时索引（-1 表示无） */
private object HourHover {
    val index = mutableStateOf(-1)
}

private val granLabels = arrayOf("日", "周", "月", "年")

/** 日历弹窗状态（由面板层渲染） */
private object CalState {
    val open = mutableStateOf(false)
    val seed = mutableStateOf(LocalDate.now())
    val result = mutableStateOf<LocalDate?>(null)
    val gran = mutableStateOf(0)
}

/** 月历悬浮的日期（用于计算整周半激活） */
private object WeekHover {
    val date = mutableStateOf<LocalDate?>(null)
}

private fun isSameWeek(a: LocalDate, b: LocalDate): Boolean {
    val ma = a.minusDays((a.dayOfWeek.value - 1).toLong())
    val mb = b.minusDays((b.dayOfWeek.value - 1).toLong())
    return ma == mb
}

/** 月历 / 月选择器（按粒度切换形态） */
@Composable
private fun CalendarPicker(
    gran: Int,
    initial: LocalDate,
    maxDate: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalSaltPalette.current
    if (gran == 2) {
        MonthPicker(initial = initial, maxDate = maxDate, onPick = onPick, onDismiss = onDismiss)
        return
    }
    if (gran == 1) {
        WeekPicker(initial = initial, maxDate = maxDate, onPick = onPick, onDismiss = onDismiss)
        return
    }

    var month by remember { mutableStateOf(initial.withDayOfMonth(1)) }
    val firstMonday = month.minusDays((month.dayOfWeek.value - 1).toLong())
    val maxMonthStart = maxDate.withDayOfMonth(1)
    val canForward = month.isBefore(maxMonthStart)

    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { }
            .drawBehind { drawRect(Color(0xB3000000)) },
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = SaltDesign.shapeLarge, color = p.card, shadowElevation = 18.dp) {
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

/** 周选择器：每周一个长按钮，显示日期范围与次数 */
@Composable
private fun WeekPicker(
    initial: LocalDate,
    maxDate: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var month by remember { mutableStateOf(initial.withDayOfMonth(1)) }
    val p = LocalSaltPalette.current
    val firstOfMonth = month.withDayOfMonth(1)
    val lastOfMonth = month.withDayOfMonth(month.lengthOfMonth())
    val startMonday = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value - 1).toLong())
    val endSunday = lastOfMonth.plusDays((7 - lastOfMonth.dayOfWeek.value).toLong())
    val weekTotal = ((endSunday.toEpochDay() - startMonday.toEpochDay()) / 7L).toInt() + 1
    val selectedMonday = initial.minusDays((initial.dayOfWeek.value - 1).toLong())
    val maxMonthStart = maxDate.withDayOfMonth(1)
    val canForward = month.isBefore(maxMonthStart)

    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { }
            .drawBehind { drawRect(Color(0xB3000000)) },
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = SaltDesign.shapeLarge, color = p.card, shadowElevation = 18.dp) {
            Column(Modifier.width(420.dp).padding(16.dp)) {
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
                Spacer(Modifier.height(12.dp))
                var w = 0
                while (w < weekTotal) {
                    val mon = startMonday.plusDays(w * 7L)
                    val sun = mon.plusDays(6)
                    val active = mon == selectedMonday
                    val future = mon.isAfter(maxDate)
                    val count = StatsStore.countInRange(mon.toString(), sun.toString())
                    Surface(
                        onClick = { if (!future) onPick(mon) },
                        shape = SaltDesign.shapeMedium,
                        color = if (active) p.highlight else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            SolidColor(if (active) p.highlight else p.subText.copy(alpha = 0.15f))
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                monthDayText(mon) + " - " + monthDayText(sun),
                                fontSize = SaltDesign.fontSizeMain,
                                color = when {
                                    active -> p.onHighlight
                                    future -> p.subText.copy(alpha = 0.30f)
                                    else -> p.text
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (count > 0) count.toString() + " 次" else "-",
                                fontSize = SaltDesign.fontSizeSub,
                                color = if (active) p.onHighlight else p.subText
                            )
                        }
                    }
                    if (w < weekTotal - 1) Spacer(Modifier.height(6.dp))
                    w++
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}

private fun monthDayText(date: LocalDate): String {
    return date.monthValue.toString() + "/" + date.dayOfMonth
}

/** 年 + 12 个月的四行按钮 */
@Composable
private fun MonthPicker(
    initial: LocalDate,
    maxDate: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalSaltPalette.current
    var year by remember { mutableStateOf(initial.year) }
    val maxMonthStart = maxDate.withDayOfMonth(1)
    val canForward = year < maxDate.year

    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press) { }
            .drawBehind { drawRect(Color(0xB3000000)) },
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = SaltDesign.shapeLarge, color = p.card, shadowElevation = 18.dp) {
            Column(Modifier.width(340.dp).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = { year = year - 1 },
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
                            year.toString() + " 年",
                            fontSize = SaltDesign.fontSizeParagraph,
                            color = p.text
                        )
                    }
                    Surface(
                        onClick = { if (canForward) year = year + 1 },
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
                Spacer(Modifier.height(12.dp))
                var r = 0
                while (r < 4) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        var c = 0
                        while (c < 3) {
                            val m = r * 3 + c + 1
                            val first = LocalDate.of(year, m, 1)
                            val future = first.isAfter(maxMonthStart)
                            val active = year == initial.year && m == initial.monthValue
                            Surface(
                                onClick = { if (!future) onPick(first) },
                                shape = SaltDesign.shapeMedium,
                                color = if (active) p.highlight else Color.Transparent,
                                border = BorderStroke(
                                    1.dp,
                                    SolidColor(
                                        if (active) p.highlight else p.subText.copy(alpha = 0.18f)
                                    )
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        m.toString() + " 月",
                                        fontSize = SaltDesign.fontSizeMain,
                                        color = when {
                                            active -> p.onHighlight
                                            future -> p.subText.copy(alpha = 0.30f)
                                            else -> p.text
                                        }
                                    )
                                }
                            }
                            c++
                        }
                    }
                    if (r < 3) Spacer(Modifier.height(8.dp))
                    r++
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}
private fun buildDayStack(matrix: Array<IntArray>, hour: Int): IntArray {
    val out = IntArray(matrix.size)
    var d = 0
    while (d < matrix.size) {
        out[d] = matrix[d][hour]
        d++
    }
    return out
}

private fun shiftAnchor(gran: Int, anchor: LocalDate, delta: Long): LocalDate = when (gran) {
    0 -> anchor.plusDays(delta)
    1 -> anchor.plusWeeks(delta)
    2 -> anchor.plusMonths(delta)
    else -> anchor.plusYears(delta)
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

private fun shortMs(ms: Long): String {
    if (ms <= 0L) return "0"
    if (ms < 60_000L) return (ms / 1000L).toString() + "s"
    val minutes = ms / 60_000L
    if (minutes < 60L) return minutes.toString() + "m"
    return (minutes / 60L).toString() + "h" + (minutes % 60L) + "m"
}

/** 单根柱子：柱顶显示值；周视图时按天分层堆叠（底部最早 → 顶部最近） */
@Composable
private fun HourBar(
    index: Int,
    ratio: Float,
    progress: Float,
    text: String,
    stack: IntArray?,
    stackTotal: Int,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    val p = LocalSaltPalette.current
    val scale by animateFloatAsState(
        targetValue = if (highlight) 1.12f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "bar-scale"
    )
    val barH = (6f + ratio * 76f) * progress
    Column(
        modifier
            .onPointerEvent(PointerEventType.Enter) { HourHover.index.value = index }
            .onPointerEvent(PointerEventType.Exit) {
                if (HourHover.index.value == index) HourHover.index.value = -1
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
            if (ratio > 0f && progress > 0.5f) {
                Text(
                    text,
                    fontSize = if (highlight) 11.sp else 9.sp,
                    color = if (highlight) p.highlight else p.subText
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().height(barH.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
        ) {
            if (stack != null && stackTotal > 0) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    var d = stack.size - 1
                    while (d >= 0) {
                        val frac = stack[d].toFloat() / stackTotal.toFloat()
                        if (frac > 0f) {
                            Surface(
                                color = p.highlight.copy(alpha = 0.22f + 0.11f * d),
                                modifier = Modifier.fillMaxWidth().weight(frac)
                            ) {}
                        }
                        d--
                    }
                }
            } else {
                Surface(
                    shape = SaltDesign.shapeSmall,
                    color = if (ratio > 0f) p.highlight.copy(alpha = 0.35f + 0.65f * ratio)
                    else p.subText.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxSize()
                ) {}
            }
        }
    }
}

private val levelSamples = intArrayOf(0, 1, 3, 6, 10)

private fun heatColor(count: Int, dark: Boolean): Color {
    if (dark) {
        return when {
            count <= 0 -> Color(0x14FFFFFF)
            count == 1 -> Color(0x4D4CC2FF)
            count <= 3 -> Color(0x804CC2FF)
            count <= 6 -> Color(0xC04CC2FF)
            else -> Color(0xFF4CC2FF)
        }
    }
    return when {
        count <= 0 -> Color(0x14000000)
        count == 1 -> Color(0x4D0470E6)
        count <= 3 -> Color(0x990470E6)
        count <= 6 -> Color(0xCC0470E6)
        else -> Color(0xFF0470E6)
    }
}

/** 可滚动区域（宿主裁掉了 verticalScroll / LazyColumn，这里用滚轮 + 拖拽 + 偏移自实现）
 *  [resetKey] 变化时重置滚动位置（每个 Tab 各自独立） */
@Composable
private fun ScrollArea(resetKey: Any, content: @Composable () -> Unit) {
    var viewportH by remember { mutableStateOf(0) }
    var contentH by remember { mutableStateOf(0) }
    var offsetY by remember(resetKey) { mutableStateOf(0f) }
    var vTop by remember { mutableStateOf(0f) }
    var cTop by remember(resetKey) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val rawMax = (contentH - viewportH).coerceAtLeast(0).toFloat()
    // 差值很小时（不到 60px）不开启滚动：宁可底部少显示一点，也不让顶部被裁
    val maxOffset = if (rawMax < 60f) 0f else rawMax
    val effOffset = if (offsetY > maxOffset) maxOffset else if (offsetY < 0f) 0f else offsetY
    val step = if (viewportH > 0) (viewportH / 4f).coerceAtLeast(60f) else 60f
    var scrollEvents by remember(resetKey) { mutableStateOf(0) }
    val p = LocalSaltPalette.current
    val barColor = p.highlight.copy(alpha = 0.35f)

    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned {
                    viewportH = it.size.height
                    vTop = it.positionInRoot().y
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
                        .onGloballyPositioned { cTop = it.positionInRoot().y }
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
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(size.width - barW - 4.dp.toPx(), barY),
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
            .onPointerEvent(PointerEventType.Press) { }
            .drawBehind { drawRect(Color(0xB3000000)) },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = SaltDesign.shapeLarge,
            color = p.card,
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
            .onPointerEvent(PointerEventType.Press) { }
            .drawBehind { drawRect(Color(0xB3000000)) },
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = SaltDesign.shapeLarge, color = p.card, shadowElevation = 18.dp) {
            Column(Modifier.width(580.dp).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TrackCover(agg?.id ?: "", 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            agg?.title?.ifBlank { "未知曲目" } ?: "未知曲目",
                            fontSize = SaltDesign.fontSizeParagraph,
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
            .onPointerEvent(PointerEventType.Press) { }
            .drawBehind { drawRect(Color(0xB3000000)) },
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = SaltDesign.shapeLarge, color = p.card, shadowElevation = 18.dp) {
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
                var shown = 0
                while (shown < items.size && shown < 10) {
                    val item = items[shown]
                    if (shown > 0) RowDivider()
                    Row(
                        Modifier.fillMaxWidth().height(44.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                    shown++
                }
                if (items.size > 10) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "仅显示前 10 首，共 " + items.size + " 首",
                        fontSize = SaltDesign.fontSizeSub,
                        color = p.subText
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
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
            .onPointerEvent(PointerEventType.Press) { }
            .drawBehind { drawRect(Color(0xB3000000)) },
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = SaltDesign.shapeLarge, color = p.card, shadowElevation = 18.dp) {
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
            Text(value, fontSize = SaltDesign.fontSizeParagraph, color = p.highlight)
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
                fontSize = SaltDesign.fontSizeParagraph,
                color = if (enabled) p.text else p.subText.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun InfoTab(tick: Int, onEditThreshold: () -> Unit, onEditRetention: () -> Unit) {
    val p = LocalSaltPalette.current
    val snap = remember(tick) { HostProbe.snapshot() }
    val dir = StatsStore.dataDirOrNull()?.absolutePath ?: "-"
    val trackCount = StatsStore.snapshot(1, 0).trackCount
    val eventLines = StatsStore.replayedLineCount()

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
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
            }
            Column(Modifier.weight(1f)) {
                SaltGroup {
                    GroupHeader("宿主")
                    ItemRow("SPW 版本", snap.spwVersion)
                    RowDivider()
                    ItemRow("Kotlin", snap.kotlinVersion)
                    RowDivider()
                    ItemRow("Compose", snap.composeVersion)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SaltGroup {
                    GroupHeader("采集")
                    Row(
                        Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "计数门槛",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text,
                            modifier = Modifier.weight(1f)
                        )
                        Text(CountRule.describe(), fontSize = SaltDesign.fontSizeSub, color = p.subText)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            onClick = onEditThreshold,
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
                    RowDivider()
                    ItemRow("播放状态", if (snap.isPlaying) "播放中" else "未播放")
                    RowDivider()
                    ItemRow("位置回调", snap.positionCallbacks.toString() + " 次")
                    RowDivider()
                    ItemRow("当前曲目", snap.lastTrackText)
                }
            }
            Column(Modifier.weight(1f)) {
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
                    Row(
                        Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "明细保留",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text,
                            modifier = Modifier.weight(1f)
                        )
                        Text(StorageRule.describe(), fontSize = SaltDesign.fontSizeSub, color = p.subText)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            onClick = onEditRetention,
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
                    RowDivider()
                    ItemRow("壁纸", WallpaperLoader.lastDiagnostics)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
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
            RowDivider()
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("数据目录", fontSize = SaltDesign.fontSizeMain, color = p.text)
                Spacer(Modifier.height(4.dp))
                Text(dir, fontSize = SaltDesign.fontSizeSub, color = p.subText)
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

private fun yesNo(value: Boolean): String = if (value) "已授权" else "未授权"

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

private fun barOf(count: Int, max: Int): String {
    if (count <= 0 || max <= 0) return ""
    var n = count * 24 / max
    if (n < 1) n = 1
    val sb = StringBuilder(n)
    var i = 0
    while (i < n) {
        sb.append('█')
        i++
    }
    return sb.toString()
}

// ---------------------------------------------------------------- 通用小组件

/** SaltUI Item 风格的表头：强调色竖条 + 大标题 + 两列右对齐 */
@Composable
private fun ListHeader(mainTitle: String, value1: String, value2: String) {
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
    }
    Spacer(Modifier.height(6.dp))
}

/** 曲目封面（无封面时显示占位音符） */
@Composable
private fun TrackCover(coverId: String, diameter: androidx.compose.ui.unit.Dp) {
    val p = LocalSaltPalette.current
    val bitmap = rememberCover(coverId)
    Surface(
        shape = SaltDesign.shapeSmall,
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
    trackKey: String,
    onClick: (String) -> Unit
) {
    Surface(
        onClick = { if (trackKey.isNotBlank()) onClick(trackKey) },
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
        }
    }
}



@Composable
private fun Pager(page: Int, pageCount: Int, onPage: (Int) -> Unit, total: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "共 " + total + " 条",
            fontSize = SaltDesign.fontSizeSub,
            color = LocalSaltPalette.current.subText
        )
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

/** 等宽胶囊按钮：选中 = 强调色边框 + 淡强调底 + 强调文字 */
@Composable
private fun PillButton(
    label: String,
    selected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val p = LocalSaltPalette.current
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeMedium,
        color = if (selected) p.highlight.copy(alpha = 0.14f) else p.subBackground,
        border = BorderStroke(
            1.dp,
            SolidColor(if (selected) p.highlight else p.subText.copy(alpha = 0.20f))
        ),
        modifier = modifier
    ) {
        Box(Modifier.height(height), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeMain,
                color = if (selected) p.highlight else p.subText
            )
        }
    }
}

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
                    fontSize = SaltDesign.fontSizeParagraph,
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
        shape = SaltDesign.shapeSmall,
        color = if (selected) LocalSaltPalette.current.highlight else LocalSaltPalette.current.subBackground
    ) {
        Box(
            Modifier.height(26.dp).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeSub,
                color = if (selected) LocalSaltPalette.current.onHighlight else LocalSaltPalette.current.subText
            )
        }
    }
}

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
private fun SectionTitle(title: String) {
    Text(title, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TrackLine(rank: Int?, track: TrackAgg, secondary: String) {
    Row(
        Modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (rank != null) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
                Text(rank.toString(), fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
            }
        }
        val title = StringBuilder(48)
        title.append(track.title.ifBlank { "未知曲目" })
        if (track.artist.isNotBlank()) title.append(" · ").append(track.artist)
        Text(
            clip(title.toString(), 42),
            fontSize = SaltDesign.fontSizeSub,
            color = LocalSaltPalette.current.text,
            modifier = Modifier.weight(1f)
        )
        Text(secondary, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
    }
}

@Composable
private fun FooterBar() {
    Text(
        "事件日志 " + StatsStore.replayedLineCount() + " 行",
        fontSize = 10.sp,
        color = LocalSaltPalette.current.subText
    )
}

private fun clip(raw: String, max: Int): String {
    if (raw.length <= max) return raw
    return raw.substring(0, max) + "…"
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
