@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spwmods.waerwak.listenstats.ui

import androidx.compose.runtime.Composable
import com.xuncorp.voxzen.util.AppConfig

/**
 * 读取宿主外观设置
 *
 * 目前只同步深浅色。字体同步不可行：宿主用 Compose Resources 加载 MiSans，
 * 且 R8 裁掉了 Compose 的字体解析链路——实测「自定义字体 / 内置 Inter / 默认字体」
 * 三者的渲染结果完全一致，插件拿不到任何非系统字体。
 */
object HostAppearance {

    /**
     * 宿主是否深色主题
     *
     * 三档：Light / Dark 直接采用；FollowSystem 回退读 Windows 注册表；
     * 枚举读不到时退回宿主自己的 isDarkTheme（与旧行为一致）。
     */
    @Composable
    fun isDarkTheme(): Boolean {
        // lightDarkTheme 是普通 getter，可以放在 try 里（isDarkTheme 是 @Composable，不行）
        val name = try {
            AppConfig.lightDarkTheme.name
        } catch (t: Throwable) {
            ""
        }
        return when (name) {
            "Light" -> false
            "Dark" -> true
            "FollowSystem" -> rememberSystemDark()
            else -> AppConfig.isDarkTheme
        }
    }

    /**
     * 系统深浅色（定时刷新，避免每次重组都起一个进程）
     *
     * 每次刷新要 spawn 一个 reg.exe（实测约 25 ms），所以间隔取 20 秒：
     * 系统主题切换不是高频操作，没必要每 5 秒查一次。
     */
    @Composable
    private fun rememberSystemDark(): Boolean {
        val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(systemDark()) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(20_000L)
                val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { systemDark() }
                if (v != state.value) state.value = v
            }
        }
        return state.value
    }

    /**
     * 窗口背景样式名
     *
     * 实测 SPW 1.19.0：None / WindowsAcrylic / WindowsMica / WindowsMicaAlt / MacOSVibrancy
     */
    fun windowStyleName(): String = try {
        AppConfig.spwWindowStyle.name
    } catch (t: Throwable) {
        "None"
    }

    /** 是否为 DWM 材质样式（亚克力 / 云母 / 云母 Alt），用于背景透明透出 */
    fun isMaterialStyle(): Boolean {
        val name = windowStyleName()
        return name == "WindowsAcrylic" || name == "WindowsMica" || name == "WindowsMicaAlt"
    }

    /**
     * 系统是否深色主题（注册表 AppsUseLightTheme = 0）
     *
     * 只在宿主设为「跟随系统」或读取失败时用得上；直接调 reg 比 JNA 稳。
     */
    fun systemDark(): Boolean {
        val text = runRegQuery() ?: return false
        // 输出里 0x0 = 深色，0x1 = 浅色
        return text.contains("0x0")
    }

    private fun runRegQuery(): String? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme"
                )
            )
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }
}
