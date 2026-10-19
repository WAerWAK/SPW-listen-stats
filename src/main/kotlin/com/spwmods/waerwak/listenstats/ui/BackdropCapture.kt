package com.spwmods.waerwak.listenstats.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * 磨砂玻璃背景
 *
 * 流程：面板打开**之前**用 Robot 截一张宿主窗口 → 用 Skia 离屏做高斯模糊并叠一层极轻噪点
 * → 缓存成一张 `ImageBitmap` → 面板只做 `drawImage`。
 *
 * 关键设计：
 * - **一次烘焙，零每帧开销**：模糊不在绘制阶段做，滚动/切换页签都不会重复计算。
 * - **缓存原始截图**：调整模糊强度时用同一张原图重新烘焙，不需要再截屏
 *   （面板已经打开时截屏会把自己拍进去）。
 * - **自适应遮罩**：记录截图平均亮度，交给面板决定遮罩不透明度（亮背景多盖一点）。
 */
object BackdropCapture {

    /** 烘焙后的底图宽度（越大细节越多，模糊后仍柔和） */
    private const val TARGET_WIDTH = 960

    /** 噪点叠加强度（0–255），约 3% */
    private const val NOISE_ALPHA = 8

    private const val NOISE_SEED = 20261019L

    const val BLUR_MIN = 24
    const val BLUR_DEFAULT = 48
    const val BLUR_MAX = 72

    /** 烘焙结果（面板读取，自动触发重组） */
    val bitmapState = mutableStateOf<ImageBitmap?>(null)

    /** 截图平均亮度 0–1（自适应遮罩用） */
    val lumaState = mutableStateOf(0f)

    /** 人类可读的状态（信息页展示） */
    val statusState = mutableStateOf("未尝试")

    /** 当前模糊强度（dp） */
    val blurDpState = mutableStateOf(BLUR_DEFAULT)

    private var rawImage: SkiaImage? = null
    private var rawW = 0
    private var rawH = 0
    private var noise: SkiaImage? = null
    private var captured = false

    /** 面板打开前调用：截取宿主窗口并烘焙（本次会话只截一次） */
    fun captureIfNeeded(blurDp: Int) {
        blurDpState.value = coerceBlur(blurDp)
        if (captured) return
        captured = true
        try {
            val target = hostWindow()
            if (target == null) {
                statusState.value = "截图失败：未找到宿主窗口"
                return
            }
            val bounds = target.bounds
            if (bounds.width <= 0 || bounds.height <= 0) {
                statusState.value = "截图失败：窗口尺寸异常"
                return
            }
            val shot = Robot().createScreenCapture(bounds)
            rawW = shot.width
            rawH = shot.height
            lumaState.value = averageLuma(shot)
            // 先把全分辨率图缩到目标宽度再编码：全分辨率 PNG 编码要 100–200ms
            val scaled = scaleDown(shot, TARGET_WIDTH) ?: shot
            val bytes = encodePng(scaled)
            if (bytes == null) {
                statusState.value = "截图失败：编码异常"
                return
            }
            rawImage = SkiaImage.makeFromEncoded(bytes)
            bake()
        } catch (t: Throwable) {
            statusState.value = "截图失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 调整模糊强度：用缓存的原始截图重新烘焙（面板已打开也安全） */
    fun setBlurDp(dp: Int) {
        val v = coerceBlur(dp)
        if (v == blurDpState.value) return
        blurDpState.value = v
        if (rawImage != null) {
            bake()
        } else {
            statusState.value = "未截图，下次打开面板生效"
        }
    }

    /** 面板关闭时调用，下次打开重新截取（背景内容可能已变化） */
    fun reset() {
        captured = false
    }

    private fun coerceBlur(dp: Int): Int = when {
        dp < BLUR_MIN -> BLUR_MIN
        dp > BLUR_MAX -> BLUR_MAX
        else -> dp
    }

    private fun bake() {
        val src = rawImage ?: return
        // 注意：rawImage 在 captureIfNeeded 里已经缩到目标宽度，这里不能再缩一次
        val w = src.width
        val h = src.height
        try {
            val surface = Surface.makeRasterN32Premul(w, h)
            val canvas = surface.canvas
            val srcRect = Rect.makeWH(src.width.toFloat(), src.height.toFloat())
            val dstRect = Rect.makeWH(w.toFloat(), h.toFloat())

            // sigma 与 dp 的换算：底图宽 960 会被拉伸到全屏（约 1.8–2 倍），
            // 0.18 倍后 24dp≈屏幕 8px、48dp≈16px、72dp≈25px
            val sigma = blurDpState.value * 0.18f
            val blurPaint = Paint()
            blurPaint.imageFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP, null)
            canvas.drawImageRect(src, srcRect, dstRect, blurPaint)

            // 极轻噪点：让磨砂感更接近真实亚克力
            val noiseImg = ensureNoise(w, h)
            if (noiseImg != null) {
                val noisePaint = Paint()
                noisePaint.alpha = NOISE_ALPHA
                canvas.drawImageRect(
                    noiseImg,
                    Rect.makeWH(noiseImg.width.toFloat(), noiseImg.height.toFloat()),
                    dstRect,
                    noisePaint
                )
            }

            val snapshot = surface.makeImageSnapshot()
            bitmapState.value = snapshot.toComposeImageBitmap()
            statusState.value = "磨砂玻璃 · " + w + "px · 模糊 " + blurDpState.value + "dp"
        } catch (t: Throwable) {
            statusState.value = "烘焙失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun ensureNoise(w: Int, h: Int): SkiaImage? {
        val existed = noise
        if (existed != null) return existed
        return try {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val pixels = IntArray(w * h)
            val random = java.util.Random(NOISE_SEED)
            var i = 0
            while (i < pixels.size) {
                val v = random.nextInt(256)
                pixels[i] = (255 shl 24) or (v shl 16) or (v shl 8) or v
                i++
            }
            img.setRGB(0, 0, w, h, pixels, 0, w)
            val bytes = encodePng(img) ?: return null
            val made = SkiaImage.makeFromEncoded(bytes)
            noise = made
            made
        } catch (t: Throwable) {
            null
        }
    }

    private fun scaleDown(img: BufferedImage, targetW: Int): BufferedImage? = try {
        if (img.width <= targetW) {
            img
        } else {
            val h = (img.height.toFloat() / img.width.toFloat() * targetW.toFloat()).toInt().coerceAtLeast(1)
            val out = BufferedImage(targetW, h, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.drawImage(img, 0, 0, targetW, h, null)
            g.dispose()
            out
        }
    } catch (t: Throwable) {
        null
    }

    private fun averageLuma(img: BufferedImage): Float {
        var sum = 0.0
        var count = 0
        val stepX = (img.width / 40).coerceAtLeast(1)
        val stepY = (img.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0f else (sum / count / 255.0).toFloat()
    }

    /** 宿主主窗口（截图与鼠标坐标判断共用） */
    internal fun hostWindow(): Window? {
        var found: Window? = null
        val windows = Window.getWindows()
        for (w in windows) {
            if (w.isVisible && w.width > 600 && w.height > 400) {
                found = w
                break
            }
        }
        return found
    }

    private fun encodePng(img: BufferedImage): ByteArray? = try {
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        out.toByteArray()
    } catch (t: Throwable) {
        null
    }
}
