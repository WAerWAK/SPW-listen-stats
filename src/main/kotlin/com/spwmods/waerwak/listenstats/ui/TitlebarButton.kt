@file:OptIn(
    androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class
)

package com.spwmods.waerwak.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spwmods.waerwak.listenstats.OverlayInjection
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle

/**
 * 标题栏入口
 *
 * 位置：标题栏右侧（Quick 附近）。具体挂载点由 [HostUiTargets] 按宿主版本给出。
 *
 * 1.19.1 起不复用自绘组件，而是**反射调用宿主自己的 caption button**，把 hook 拿到的
 * `currentComposer` 传进去 —— 这样按钮直接参与标题栏的行布局，尺寸、悬停波纹、
 * 点击态、无障碍描述全部与宿主按钮一致（出处：Jethuit/SPW-Listen-Stats 的做法）。
 * 1.19.0 的标题栏容器接受自绘组件，沿用老路径。
 */
object TitlebarButton {

    private const val TARGET_METHOD = "\u037f"

    /** 宿主 caption button 组件本体（1.19.1 签名；仅复用模式下使用） */
    private const val CAPTION_CLASS = "com.xuncorp.voxzen.ui.component.\u0529"
    private const val CONTENT_DESCRIPTION = "听歌统计"

    /** 宿主 caption 按钮默认宽度（px） */
    private const val CAPTION_WIDTH_PX = 32

    /** 当前宿主版本的注入目标（install 时写入） */
    @Volatile
    private var targets: HostUiTargets = HostUiTargets.forVersion("")

    private var handle: HookHandle? = null

    @Volatile
    var lastError: String? = null
        private set

    val installed: Boolean
        get() = handle != null

    fun statusText(): String {
        if (installed) return "已安装（标题栏 Quick 左侧）"
        val error = lastError
        return if (error == null) "未安装" else "未安装：" + error
    }

    /**
     * 宿主 caption button 的 10 参数重载
     *
     * `(Function0 onClick, String, Modifier, boolean, String, float, Function3<BoxScope>, Composer, int, int)`
     */
    private val captionMethod: java.lang.reflect.Method by lazy {
        val loader = TitlebarButton::class.java.classLoader
        val clazz = Class.forName(CAPTION_CLASS, false, loader)
        val m = clazz.getDeclaredMethod(
            TARGET_METHOD,
            kotlin.jvm.functions.Function0::class.java,
            String::class.java,
            Modifier::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Float::class.javaPrimitiveType,
            kotlin.jvm.functions.Function3::class.java,
            androidx.compose.runtime.Composer::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        check(java.lang.reflect.Modifier.isStatic(m.modifiers)) { "caption button 必须是静态方法" }
        m.isAccessible = true
        m
    }

    fun install(spwVersion: String): Boolean {
        if (handle != null) return true
        val t = HostUiTargets.forVersion(spwVersion)
        targets = t
        return try {
            // 复用宿主组件时提前解析签名：不支持就尽早失败，不影响主统计 hook
            if (t.reuseHostCaption) captionMethod
            handle = WorkshopApi.hookRegistrar.afterComposable(
                className = t.debugCaption,
                methodName = TARGET_METHOD,
                inClass = t.titlebarCaller,
                parameterTypes = emptyList()
            ) { _ ->
                TitlebarEntry()
            }
            lastError = null
            true
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + "：" + (e.message ?: "")
            handle = null
            false
        }
    }

    fun uninstall() {
        try {
            handle?.unhook()
        } catch (t: Throwable) {
            // 卸载失败忽略
        }
        handle = null
    }

    @Composable
    private fun TitlebarEntry() {
        if (!targets.reuseHostCaption) {
            FallbackEntry()
            return
        }
        val composer = currentComposer
        try {
            captionMethod.invoke(
                null,
                { OverlayInjection.togglePanel() } as kotlin.jvm.functions.Function0<kotlin.Unit>,
                CONTENT_DESCRIPTION,
                Modifier.semantics { contentDescription = CONTENT_DESCRIPTION },
                true,
                null,
                0f,
                StatsGlyph,
                composer,
                0,
                CAPTION_WIDTH_PX
            )
        } catch (t: Throwable) {
            // 宿主签名变动时退回自绘按钮，至少保留入口
            lastError = t.javaClass.simpleName + "：" + (t.message ?: "")
            FallbackEntry()
        }
    }

    /**
     * 图标槽位交给宿主的图标内容（20dp 频谱）
     *
     * 槽位可能不是正方形（宿主给的高度约束可能小于宽度），所以统一按**短边**取正方形
     * 再居中绘制，避免图标被压扁。
     */
    private val StatsGlyph: @Composable BoxScope.() -> Unit = {
        val dark = HostAppearance.isDarkTheme()
        val accent = if (dark) Color(0xFF4CC2FF) else Color(0xFF2A7FB8)
        Box(
            Modifier.align(Alignment.Center).size(20.dp).drawBehind {
                drawSpectrum(accent)
            }
        )
    }

    /** 在绘制区里按短边取正方形画 4 根柱子（短边为基准，任何槽位比例都不变形） */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpectrum(accent: Color) {
        val side = if (size.width < size.height) size.width else size.height
        val ox = (size.width - side) / 2f
        val oy = (size.height - side) / 2f
        // 比例按「填满 20dp 图标区」来定：总宽约 91%、最高柱约 74%，
        // 与宿主原生 caption 图标的视觉尺寸接近
        val barW = side * 0.16f
        val gapW = side * 0.09f
        val totalW = barW * 4f + gapW * 3f
        val left = ox + (side - totalW) / 2f
        val base = oy + side * 0.88f
        val radius = barW * 0.5f
        val heights = floatArrayOf(0.22f, 0.46f, 0.74f, 0.32f)
        var b = 0
        while (b < 4) {
            val h = side * heights[b]
            val x = left + b * (barW + gapW)
            val c = if (b == 2) accent else accent.copy(alpha = 0.55f)
            drawRoundRect(
                color = c,
                topLeft = Offset(x, base - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(radius, radius)
            )
            b++
        }
    }

    /** 兜底 / 1.19.0 路径：自绘一个带悬停态的按钮 */
    @Composable
    private fun FallbackEntry() {
        var hovered by remember { mutableStateOf(false) }
        val dark = HostAppearance.isDarkTheme()
        val accent = if (dark) Color(0xFF4CC2FF) else Color(0xFF2A7FB8)
        val hoverColor = if (dark) Color(0x1AFFFFFF) else Color(0x14000000)
        Box(
            Modifier
                .size(40.dp, 32.dp)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .onPointerEvent(PointerEventType.Press) { OverlayInjection.togglePanel() }
                .drawBehind {
                    if (hovered) {
                        val radius = 6.dp.toPx()
                        drawRoundRect(
                            color = hoverColor,
                            topLeft = Offset(size.width * 0.10f, size.height * 0.10f),
                            size = Size(size.width * 0.80f, size.height * 0.80f),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
                    drawSpectrum(accent)
                }
        )
    }
}
