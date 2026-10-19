package com.spwmods.waerwak.listenstats.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.xuncorp.voxzen.util.AppConfig
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 同步宿主字体
 *
 * 关键点：Compose Desktop 的字体必须用 **字节数组重载**
 * `FontFamily(Font(identity, data = ByteArray))`；
 * 路径版 `FontFamily(path)` 在宿主里会静默解析失败（回退系统字体且不报错），
 * 这是之前「字体无法同步」结论的由来。
 *
 * 字体来源优先级：
 * 1. 用户在 SPW 里设置的自定义字体（`AppConfig.customFontFilePath`）
 * 2. 宿主内置 MiSans（classloader 里的 composeResources 资源，约 25 MB）
 * 3. 系统默认字体
 */
object HostFont {

    private const val MISANS =
        "composeResources/voxzen.composeapp.generated.resources/font/misans.ttf"

    @Volatile
    private var cached: FontFamily? = null

    @Volatile
    private var attempted = false

    @Volatile
    var lastDiagnostics: String = "未尝试"
        private set

    /** 取宿主字体（首次调用会读取并解析字体，之后走缓存） */
    fun family(): FontFamily {
        if (attempted) return cached ?: FontFamily.Default
        attempted = true
        cached = load()
        return cached ?: FontFamily.Default
    }

    private fun load(): FontFamily? {
        // 1) 用户自定义字体
        val customPath = try {
            AppConfig.customFontFilePath
        } catch (t: Throwable) {
            null
        }
        if (!customPath.isNullOrBlank()) {
            val bytes = readFileBytes(customPath)
            if (bytes != null && isValidFont(bytes)) {
                val family = build(bytes, "custom")
                if (family != null) {
                    lastDiagnostics = "自定义字体：" + File(customPath).name
                    return family
                }
            } else {
                lastDiagnostics = "自定义字体读取/校验失败，尝试内置字体"
            }
        }
        // 2) 宿主内置 MiSans
        val misans = readHostResource(MISANS)
        if (misans != null && isValidFont(misans)) {
            val family = build(misans, "misans")
            if (family != null) {
                lastDiagnostics = "宿主 MiSans（" + (misans.size / 1024 / 1024) + " MB）"
                return family
            }
        }
        lastDiagnostics = "回退系统字体"
        return null
    }

    private fun build(bytes: ByteArray, identity: String): FontFamily? = try {
        FontFamily(Font(identity = "spw-listenstats:" + identity, data = bytes))
    } catch (t: Throwable) {
        lastDiagnostics = "构造 FontFamily 失败：" + (t.message ?: t.javaClass.simpleName)
        null
    }

    /** 用 AWT 提前校验字体能否解析，避免 Compose 渲染时才崩 */
    private fun isValidFont(bytes: ByteArray): Boolean = try {
        val stream = ByteArrayInputStream(bytes)
        try {
            java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, stream)
            true
        } finally {
            try {
                stream.close()
            } catch (t: Throwable) {
                // 忽略
            }
        }
    } catch (t: Throwable) {
        false
    }

    private fun readHostResource(path: String): ByteArray? = try {
        val stream = AppConfig::class.java.classLoader?.getResourceAsStream(path)
        if (stream == null) {
            null
        } else {
            val bytes = stream.readBytes()
            try {
                stream.close()
            } catch (t: Throwable) {
                // 忽略
            }
            if (bytes.isEmpty()) null else bytes
        }
    } catch (t: Throwable) {
        null
    }

    private fun readFileBytes(path: String): ByteArray? = try {
        val file = File(path)
        if (!file.isFile) null else file.readBytes()
    } catch (t: Throwable) {
        null
    }
}
