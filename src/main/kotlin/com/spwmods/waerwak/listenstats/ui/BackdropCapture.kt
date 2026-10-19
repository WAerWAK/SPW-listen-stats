package com.spwmods.waerwak.listenstats.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage

/**
 * 抓取 SPW 窗口背景，用于面板的「磨砂玻璃」底图
 *
 * 做法：面板打开**之前**用 Robot 截一张宿主窗口，缩到很小（宽约 96px）后随面板
 * 拉伸回全屏绘制——低分辨率放大的双线性插值天然就是模糊效果，不依赖
 * `BlurEffect` / Haze（宿主的 R8 裁剪让后两者不可靠）。
 *
 * 注意：必须在面板显示前截图，否则会把面板自己拍进去。
 */
object BackdropCapture {

    /** 底图宽度（越小越糊，同时越省内存） */
    private const val TARGET_WIDTH = 96

    @Volatile
    var bitmap: ImageBitmap? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var captured = false

    /** 面板打开前调用：截取宿主窗口（每次打开只截一次） */
    fun captureIfNeeded() {
        if (captured) return
        captured = true
        try {
            var target: Window? = null
            val windows = Window.getWindows()
            for (w in windows) {
                if (w.isVisible && w.width > 600 && w.height > 400) {
                    target = w
                    break
                }
            }
            if (target == null) {
                lastError = "未找到宿主窗口"
                return
            }
            val bounds = target.bounds
            if (bounds.width <= 0 || bounds.height <= 0) {
                lastError = "宿主窗口尺寸异常"
                return
            }
            val shot = Robot().createScreenCapture(bounds)
            val w = TARGET_WIDTH
            val h = (shot.height.toFloat() / shot.width.toFloat() * w).toInt().coerceAtLeast(1)
            val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.drawImage(shot, 0, 0, w, h, null)
            g.dispose()
            bitmap = scaled.toComposeImageBitmap()
            lastError = null
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
        }
    }

    /** 面板关闭时调用，下次打开重新截取（背景可能已变化） */
    fun reset() {
        captured = false
    }
}
