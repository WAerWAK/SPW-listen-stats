@file:OptIn(ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.xuncorp.voxzen.util.AppConfig
import java.io.File

/**
 * 读取 SPW 的壁纸背景
 *
 * 宿主把 Haze 的便捷 API 裁剪掉了（只剩泛型版 hazeEffect），但壁纸链路是完整的：
 *  - `AppConfig.wallpaperMaterial` 是否启用壁纸材质
 *  - `AppConfig.wallpaperFilePath`  壁纸文件路径
 *  - `decodeToImageBitmap(byte[])`  保留，可直接解码
 *
 * 单次加载并缓存（壁纸切换需重开面板，先接受）。
 */
object WallpaperLoader {

    @Volatile
    private var cached: ImageBitmap? = null

    @Volatile
    private var attempted = false

    @Volatile
    var lastDiagnostics: String = "未尝试"
        private set

    fun load(): ImageBitmap? {
        if (attempted) return cached
        attempted = true
        return try {
            // 只看文件，不强依赖 wallpaperMaterial：SPW 的「亚克力/云母」样式下
            // 该开关与壁纸文件无关，强行判断会导致永远读不到壁纸
            val path = AppConfig.wallpaperFilePath
            if (path.isNullOrBlank()) {
                lastDiagnostics = "壁纸路径为空"
                return null
            }
            val file = File(path)
            if (!file.isFile) {
                lastDiagnostics = "壁纸文件不存在：" + path
                return null
            }
            val bytes = file.readBytes()
            if (bytes.isEmpty()) {
                lastDiagnostics = "壁纸文件为空"
                return null
            }
            val bitmap = bytes.decodeToImageBitmap()
            cached = bitmap
            lastDiagnostics = "壁纸已加载：" + file.name + "（" + bytes.size / 1024 + " KB）"
            bitmap
        } catch (t: Throwable) {
            lastDiagnostics = "壁纸加载失败：" + t.javaClass.simpleName + " " + t.message
            null
        }
    }

    /**
     * 壁纸原图（AWT 位图，供按窗口比例裁切用）
     *
     * 与 [load] 分开缓存：这里要的是像素级可裁切的 BufferedImage，
     * 面板兜底显示则用 Compose 的 ImageBitmap。
     */
    @Volatile
    private var rawBuffered: java.awt.image.BufferedImage? = null

    @Volatile
    private var rawAttempted = false

    fun loadBuffered(): java.awt.image.BufferedImage? {
        if (rawAttempted) return rawBuffered
        rawAttempted = true
        return try {
            val path = AppConfig.wallpaperFilePath
            if (path.isNullOrBlank()) return null
            val file = File(path)
            if (!file.isFile) return null
            val img = javax.imageio.ImageIO.read(file)
            // 超大壁纸（4K/8K）先缩小再缓存：面板背景用不到那么多像素，
            // 而原图常驻内存会吃 30–60 MB
            val capped = if (img.width > 2560) shrinkTo(img, 2560) else img
            rawBuffered = capped
            capped
        } catch (t: Throwable) {
            null
        }
    }

    private fun shrinkTo(img: java.awt.image.BufferedImage, targetW: Int): java.awt.image.BufferedImage? = try {
        val h = (img.height.toFloat() / img.width.toFloat() * targetW.toFloat()).toInt().coerceAtLeast(1)
        val out = java.awt.image.BufferedImage(targetW, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(img, 0, 0, targetW, h, null)
        g.dispose()
        out
    } catch (t: Throwable) {
        null
    }

    /** 壁纸文件路径（诊断用） */
    fun filePath(): String? = try {
        AppConfig.wallpaperFilePath
    } catch (t: Throwable) {
        null
    }

    /** SPW 是否开启了「壁纸材质」（壁纸被模糊 + 颗粒） */
    fun materialEnabled(): Boolean = try {
        AppConfig.wallpaperMaterial
    } catch (t: Throwable) {
        false
    }
}
