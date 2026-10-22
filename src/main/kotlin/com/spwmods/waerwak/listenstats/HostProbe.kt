@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.spwmods.waerwak.listenstats.stats.StatsStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 面板展示用的一次性快照 */
data class ProbeSnapshot(
    val spwVersion: String = "?",
    val channel: String = "?",
    val pluginId: String = "?",
    val pluginVersion: String = "?",
    val kotlinVersion: String = "?",
    val composeVersion: String = "?",
    val libraryRead: Boolean = false,
    val keyBindings: Boolean = false,
    val classTransform: Boolean = false,
    val positionCallbacks: Long = 0L,
    val lastTrackText: String = "-",
    val isPlaying: Boolean = false
)

/**
 * 宿主环境探测（M0 的核心验证手段）
 *
 * 记录：宿主/插件版本、Kotlin/Compose 版本、权限状态、AWT 窗口列表、扩展点回调情况。
 * 这些信息决定 M1 之后的采集与 UI 方案能否成立。
 */
object HostProbe {

    @Volatile
    private var context: PluginContext? = null

    @Volatile
    var positionCallbacks: Long = 0L

    @Volatile
    var lastTrack: MediaItem? = null

    @Volatile
    var isPlaying: Boolean = false

    fun attach(ctx: PluginContext) {
        context = ctx
        // 采集层需要数据目录（%APPDATA%\...\workshop\data\<pluginId>）
        try {
            StatsStore.attach(dataDir())
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 初始化统计目录失败: ${t.message}")
        }
    }

    /** 插件上下文（null 表示尚未 attach） */
    fun pluginContext(): PluginContext? = context

    fun snapshot(): ProbeSnapshot {
        val ctx = context
        return ProbeSnapshot(
            spwVersion = ctx?.spwVersion ?: "?",
            channel = ctx?.spwChannel?.name ?: "?",
            pluginId = ctx?.pluginId ?: "?",
            pluginVersion = ctx?.pluginVersion ?: "?",
            kotlinVersion = KotlinVersion.CURRENT.toString(),
            composeVersion = composeVersion(),
            libraryRead = hasPermission(PluginPermission.LIBRARY_READ),
            keyBindings = hasPermission(PluginPermission.KEY_BINDINGS),
            classTransform = hasPermission(PluginPermission.CLASS_TRANSFORM),
            positionCallbacks = positionCallbacks,
            lastTrackText = lastTrack?.let { track ->
                listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" - ")
                    .ifBlank { track.path }
            } ?: "-",
            isPlaying = isPlaying
        )
    }

    /** 宿主没提供查询接口，权限探测必须容错：旧宿主上会 NoSuchMethodError */
    private fun hasPermission(permission: PluginPermission): Boolean = try {
        WorkshopApi.manager.isPermissionGranted(permission)
    } catch (t: Throwable) {
        false
    }

    /**
     * 宿主实际使用的 Compose 运行时版本
     *
     * ComposeVersion 是 `const val`，编译后以 public static final 字段存在；拿不到就返回 unknown，
     * 由日志侧做人工比对（这是判断插件 Compose 编译器版本是否匹配的唯一依据）。
     */
    fun composeVersion(): String = try {
        val clazz = Class.forName("androidx.compose.runtime.ComposeVersion")
        val field = clazz.getField("version")
        field.get(null)?.toString() ?: "?"
    } catch (t: Throwable) {
        "unknown"
    }

    /** 宿主的插件数据目录：%APPDATA%\Salt Player for Windows\workshop\data\<pluginId> */
    fun dataDir(): File? = try {
        val helper = WorkshopApi.manager.createConfigManager().getConfig("config.json")
        helper.getConfigPath().parent?.toFile()
    } catch (t: Throwable) {
        val appData = System.getenv("APPDATA")
        val id = context?.pluginId ?: "com.spwmods.waerwak.listenstats"
        if (appData.isNullOrBlank()) {
            null
        } else {
            File(File(File(appData, "Salt Player for Windows"), "workshop"), "data/$id")
        }
    }

    /** 写入一行启动日志，用于确认插件被真实加载（宿主自己不记录插件日志） */
    fun logStartup(): Boolean {
        val ctx = context ?: return false
        return try {
            val dir = dataDir() ?: return false
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val line = buildString {
                append(stamp)
                append("  plugin=").append(ctx.pluginId).append(" v").append(ctx.pluginVersion)
                append("  spw=").append(ctx.spwVersion).append('(').append(ctx.spwChannel).append(')')
                append("  kotlin=").append(KotlinVersion.CURRENT)
                append("  compose=").append(composeVersion())
                append("  data=").append(dir.absolutePath)
            }
            Files.write(
                File(dir, "listen-stats.log").toPath(),
                listOf(line),
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
            true
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 写启动日志失败: ${t.message}")
            false
        }
    }
}
