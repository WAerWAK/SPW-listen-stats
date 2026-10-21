@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.spwmods.waerwak.listenstats.OverlayInjection
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle

/**
 * 标题栏入口
 *
 * 在宿主标题栏的 `DebugCaptionButton` 之后追加一个「听歌统计」按钮
 * （位置：Quick 快捷按钮左侧）。浮钮仍然保留，两条入口互不影响。
 *
 * 实现上刻意**不反射调用宿主的 caption button 组件**：本机 1.19.0 里那个组件的签名是
 * `(Function0, Painter, String, Modifier, Function2, Boolean, Boolean, String, String,
 * Float, Long, Composer, ...)`，比公开资料多出若干参数，一旦参数错位就会在标题栏里崩溃。
 * 这里插入一个自绘按钮，只依赖 hook 点本身，失败也不影响主功能。
 */
@OptIn(UnstableSpwWorkshopApi::class)
object TitlebarButton {

    private const val TARGET_CLASS = "androidx.compose.ui.\u04f4"
    private const val TARGET_METHOD = "\u037f"
    private const val CALLER_CLASS = "androidx.compose.ui.\u0205"

    /** 与宿主 caption 按钮尺寸一致 */
    private val WIDTH = 40.dp
    private val HEIGHT = 32.dp

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

    fun install(): Boolean {
        if (handle != null) return true
        return try {
            handle = WorkshopApi.hookRegistrar.afterComposable(
                className = TARGET_CLASS,
                methodName = TARGET_METHOD,
                inClass = CALLER_CLASS
            ) { _ ->
                TitlebarEntry()
            }
            lastError = null
            true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + "：" + (t.message ?: "")
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
        var hovered by remember { mutableStateOf(false) }
        val dark = HostAppearance.isDarkTheme()
        // 强调色图标（与 SPW 一致），而不是纯白
        val accent = if (dark) Color(0xFF4CC2FF) else Color(0xFF2A7FB8)
        val hoverColor = if (dark) Color(0x1AFFFFFF) else Color(0x14000000)

        Box(
            Modifier
                .size(WIDTH, HEIGHT)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .onPointerEvent(PointerEventType.Press) {
                    if (OverlayInjection.panelOpen.value) {
                        OverlayInjection.closePanel()
                    } else {
                        OverlayInjection.panelOpen.value = true
                    }
                }
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
                    // 音频频谱图标：4 根圆角柱，主柱用强调色实心，其余半透明
                    val barW = 2.6f * density
                    val gapW = 1.7f * density
                    val totalW = barW * 4f + gapW * 3f
                    val left = (size.width - totalW) / 2f
                    val base = size.height * 0.68f
                    val radius = barW * 0.5f
                    val heights = floatArrayOf(0.15f, 0.30f, 0.44f, 0.22f)
                    var b = 0
                    while (b < 4) {
                        val h = size.height * heights[b]
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
        )
    }
}
