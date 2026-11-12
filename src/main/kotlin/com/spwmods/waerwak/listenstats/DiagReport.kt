package com.spwmods.waerwak.listenstats

import com.spwmods.waerwak.listenstats.stats.CountRule
import com.spwmods.waerwak.listenstats.stats.MissingStore
import com.spwmods.waerwak.listenstats.stats.SessionTracker
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.StorageRule
import com.spwmods.waerwak.listenstats.stats.TimingDiag
import com.spwmods.waerwak.listenstats.ui.EntrySwitches
import com.spwmods.waerwak.listenstats.ui.HostAppearance
import com.spwmods.waerwak.listenstats.ui.HostInstallSession
import com.spwmods.waerwak.listenstats.ui.HostMaterialState
import com.spwmods.waerwak.listenstats.ui.NavigationHook
import com.spwmods.waerwak.listenstats.ui.TitlebarButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键诊断报告
 *
 * 用户反馈问题时，让他手工拼 hook-diag / listen-stats.log / 权限 / 版本非常容易漏项。
 * 这里把排查需要的状态一次写成文本文件。
 *
 * **脱敏约定**：不含歌名、歌手、专辑、封面、音频文件路径、备份内容；
 * 数据目录本身保留（排查必需），报告首行提示分享前自行打码。
 */
object DiagReport {

    private const val DIAG_TAIL_LINES = 25
    private const val LOG_TAIL_LINES = 40

    /** 生成报告；成功返回文件路径，失败返回原因（以"失败："开头） */
    fun write(): String {
        val dir = StatsStore.dataDirOrNull() ?: return "失败：数据目录不可用"
        return try {
            val text = build()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val file = File(dir, "诊断报告-" + stamp + ".txt")
            file.writeText(text, Charsets.UTF_8)
            file.absolutePath
        } catch (t: Throwable) {
            "失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun build(): String {
        val snap = HostProbe.snapshot()
        val dir = StatsStore.dataDirOrNull()
        val sb = StringBuilder(4096)
        sb.append("听歌统计 · 诊断报告\n")
        sb.append("（分享前请检查下面的路径与文件名，必要时自行打码）\n")
        sb.append('\n')

        sb.append("== 版本与环境 ==\n")
        sb.append("插件版本        : ").append(snap.pluginVersion).append('\n')
        sb.append("宿主版本        : ").append(snap.spwVersion).append("（").append(snap.channel).append("）\n")
        sb.append("插件 ID         : ").append(snap.pluginId).append('\n')
        sb.append("Kotlin / Compose: ").append(snap.kotlinVersion).append(" / ").append(snap.composeVersion).append('\n')
        sb.append("Java            : ").append(System.getProperty("java.version")).append('\n')
        sb.append("OS              : ").append(System.getProperty("os.name")).append(' ')
            .append(System.getProperty("os.version")).append(" / ").append(System.getProperty("os.arch")).append('\n')
        sb.append("时区            : ").append(java.time.ZoneId.systemDefault().id).append('\n')
        sb.append('\n')

        sb.append("== 权限 ==\n")
        sb.append("class-transform : ").append(snap.classTransform).append('\n')
        sb.append("library-read    : ").append(snap.libraryRead).append('\n')
        sb.append("key-bindings    : ").append(snap.keyBindings).append('\n')
        sb.append('\n')

        sb.append("== 界面注入 ==\n")
        sb.append("面板已注入      : ").append(OverlayInjection.installed()).append('\n')
        sb.append("注入错误        : ").append(OverlayInjection.lastError ?: "-").append('\n')
        sb.append("注入会话代号    : ").append(HostInstallSession.current()).append('\n')
        sb.append("标题栏按钮      : ").append(TitlebarButton.statusText()).append('\n')
        sb.append("导航 hook       : ").append(NavigationHook.installed)
            .append("（错误 ").append(NavigationHook.lastError ?: "-").append("）\n")
        sb.append("材质桥接        : ").append(HostMaterialState.bridgeAvailable).append('\n')
        sb.append("窗口样式        : ").append(HostAppearance.lastStyleName).append('\n')
        sb.append("宿主导航已就绪  : ").append(NavigationHook.navigatorOrNull() != null).append('\n')
        sb.append("浮钮 / 顶栏开关 : ").append(EntrySwitches.floating)
            .append(" / ").append(EntrySwitches.showTitlebar).append('\n')
        sb.append("侧栏入口        : ").append(EntrySwitches.showSidebar)
            .append("（请求 ").append(EntrySwitches.sidebarRequested)
            .append(" / 可用 ").append(EntrySwitches.sidebarReady).append("）\n")
        sb.append('\n')

        sb.append("== 采集与数据 ==\n")
        sb.append("播放状态        : ").append(if (snap.isPlaying) "播放中" else "未播放").append('\n')
        sb.append("位置回调次数    : ").append(snap.positionCallbacks).append('\n')
        sb.append("计数门槛        : ").append(CountRule.describe()).append('\n')
        sb.append("明细保留        : ").append(StorageRule.describe()).append('\n')
        sb.append("逐帧诊断        : ").append(if (TimingDiag.enabled) "已开启" else "关闭")
            .append("（已记录 ").append(TimingDiag.frameCount()).append(" 帧）\n")
        sb.append("计时异常        : 钳制 ").append(SessionTracker.clampedSteps)
            .append(" 次 / 丢步 ").append(SessionTracker.droppedSteps)
            .append(" 次 / 最大空档 ").append(SessionTracker.maxGapMs).append(" ms\n")
        sb.append("曲目数 / 事件行 : ").append(StatsStore.allTracks().size).append(" / ")
            .append(StatsStore.replayedLineCount()).append('\n')
        sb.append("存储占用        : ").append(StatsStore.storageSummary()).append('\n')
        sb.append("数据读取错误    : ").append(StatsStore.loadError() ?: "无").append('\n')
        sb.append("缺失曲目        : ").append(MissingStore.count()).append(" 首（上次检测 ")
            .append(formatTime(MissingStore.checkedAt())).append("）\n")
        sb.append("数据目录        : ").append(if (dir == null) "不可用" else dir.absolutePath).append('\n')
        sb.append('\n')

        sb.append("== hook-diag.txt（最后 ").append(DIAG_TAIL_LINES).append(" 行）==\n")
        sb.append(tail(dir, "hook-diag.txt", DIAG_TAIL_LINES))
        sb.append('\n')
        sb.append("== listen-stats.log（最后 ").append(LOG_TAIL_LINES).append(" 行）==\n")
        sb.append(tail(dir, "listen-stats.log", LOG_TAIL_LINES))
        return sb.toString()
    }

    private fun tail(dir: File?, name: String, lines: Int): String {
        if (dir == null) return "（不可用）\n"
        val file = File(dir, name)
        if (!file.isFile) return "（没有 " + name + "）\n"
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return "（读取失败：" + (t.message ?: t.javaClass.simpleName) + "）\n"
        }
        if (text.length > 200_000) return "（文件过大，跳过尾部读取）\n"
        val all = text.split('\n')
        val from = if (all.size <= lines) 0 else all.size - lines
        val sb = StringBuilder(2048)
        var i = from
        while (i < all.size) {
            sb.append(all[i]).append('\n')
            i++
        }
        return sb.toString()
    }

    /** 面板里复制的简短诊断（保留原有行为，便于贴到聊天里） */
    fun summaryText(): String {
        val snap = HostProbe.snapshot()
        val sb = StringBuilder(512)
        sb.append("插件 ").append(snap.pluginVersion)
            .append(" / SPW ").append(snap.spwVersion)
            .append(" / 面板=").append(if (OverlayInjection.installed()) "✓" else "✗")
            .append(" / 顶栏=").append(if (TitlebarButton.installed) "✓" else "✗")
            .append(" / 导航=").append(if (NavigationHook.installed) "✓" else "✗")
            .append(" / 材质=").append(HostMaterialState.bridgeAvailable)
            .append('\n')
        sb.append("注入错误: ").append(OverlayInjection.lastError ?: "-").append('\n')
        sb.append("计时: 钳制 ").append(SessionTracker.clampedSteps)
            .append(" / 丢步 ").append(SessionTracker.droppedSteps)
            .append(" / 最大空档 ").append(SessionTracker.maxGapMs).append(" ms")
        return sb.toString()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "未检测"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))
    }
}
