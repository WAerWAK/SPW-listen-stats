@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.ui.HostAppearance
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import com.spwmods.waerwak.listenstats.ui.UiStateStore
import com.spwmods.waerwak.listenstats.ui.HostFont
import com.spwmods.waerwak.listenstats.ui.saltPalette
import java.time.LocalDate

internal val TABS = arrayOf("概览", "排行", "活跃度", "信息")



@Composable
internal fun OverlayHost() {
    var hovered by remember { mutableStateOf(false) }
    val opened = OverlayInjection.panelOpen.value

    // 关闭面板（含浮钮状态与全局弹层复位）
    val closePanel: () -> Unit = {
        OverlayInjection.panelOpen.value = false
        hovered = false
        // 详情 / 日历浮层是全局状态：不复位的话下次打开面板会直接弹回上次那一层
        TrackDetailState.open.value = null
        GroupDetailState.open.value = ""
        CalState.open.value = false
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
    // 窗口样式：决定是否材质模式、卡片覆盖层透明度，以及面板根是否透出 DWM 材质
    val styleName = HostAppearance.windowStyleName()
    val materialStyle = styleName == "WindowsAcrylic" || styleName == "WindowsMica" ||
        styleName == "WindowsMicaAlt"
    // 配色只看样式，不看桥接是否可用：桥接失效时由 transparentPanel 退回实色底，
    // 而不是掉回经典色（否则云母 Alt 会显示成经典灰）
    val palette = saltPalette(dark, materialStyle, styleName)
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
                // 打开：从下方 150px 滑入 + 淡入；关闭：淡出（与宿主页面切换同一套参数）
                AnimatedVisibility(
                    visible = opened,
                    enter = slideInVertically(animationSpec = spring(stiffness = 500f)) { 150 } +
                        fadeIn(animationSpec = spring(stiffness = 500f)),
                    // 关闭不做动画：立即消失（淡出会短暂盖住宿主内容）
                    exit = androidx.compose.animation.ExitTransition.None
                ) {
                    StatsOverlay(onClose = closePanel)
                }
                if (!opened) {
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
internal fun EdgeEntry(hovered: Boolean, onHoverChange: (Boolean) -> Unit, onClick: () -> Unit) {
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
internal fun ChartGlyph(color: Color) {
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
internal fun GlyphBar(height: androidx.compose.ui.unit.Dp, color: Color) {
    Surface(
        modifier = Modifier.width(4.dp).height(height),
        shape = SaltDesign.shapeSmall,
        color = color
    ) {}
}



/** Esc 全局监听（AWT 层，不依赖 Compose 焦点） */
internal object EscGate {

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
internal fun StatsOverlay(onClose: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    // 配置页 / 顶栏按钮可以指定打开哪一页；用 State 订阅，面板已打开时也能切过去
    val pendingTab = OverlayInjection.pendingTab.value
    LaunchedEffect(pendingTab) {
        if (pendingTab >= 0) {
            tab = pendingTab
            OverlayInjection.pendingTab.value = -1
        }
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
    // 用 Long 拼键：Int 拼接在连续播放 41.7 小时后会与 tick 进位重叠，导致该次刷新丢失
    val liveKey: Long = tick.toLong() * 1_000_000L + liveTick.toLong()
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
    val rootPalette = LocalSaltPalette.current
    // 只有「调色板允许透明」且桥接层真的在工作时才透出 DWM，否则用实色兜底
    val transparentRoot = bridgeAvailable && rootPalette.transparentPanel
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (transparentRoot) Color.Transparent else rootPalette.background
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
    // 底部留 87dp：避开浮动胶囊迷你播放条（面板内容不被遮挡）
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
            Column(Modifier.width(contentW)) {
                HeaderBar(onClose = onClose, onRefresh = { tick = tick + 1 })
                Spacer(Modifier.height(12.dp))
                TabBar(selected = tab, onSelect = { tab = it })
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    // 四页切换：旧页快速淡出，新页从下方 150px 滑入（对齐宿主 AppNavigation 的动效）
                    AnimatedContent(
                        targetState = tab,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (slideInVertically(animationSpec = spring(stiffness = 500f)) { 150 } +
                                fadeIn(animationSpec = spring(stiffness = 500f))) togetherWith
                                fadeOut(animationSpec = spring(stiffness = 500f))
                        },
                        label = "tab-switch"
                    ) { currentTab ->
                        ScrollArea(resetKey = currentTab) {
                            when (currentTab) {
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
internal fun HeaderBar(onClose: () -> Unit, onRefresh: () -> Unit) {
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
internal fun AccentButton(label: String, onClick: () -> Unit) {
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
internal fun RefreshGlyph(color: Color) {
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
internal fun BackGlyph(color: Color) {
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
internal fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
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



/** 自绘的 Tab 图标（0 概览 / 1 排行 / 2 活跃度 / 3 信息） */
@Composable
internal fun TabIcon(kind: Int, color: Color) {
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
internal fun FooterBar() {
    Text(
        "事件日志 " + StatsStore.replayedLineCount() + " 行",
        fontSize = 10.sp,
        color = LocalSaltPalette.current.subText
    )
}


