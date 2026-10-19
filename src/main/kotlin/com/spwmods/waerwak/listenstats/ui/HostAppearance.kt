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

    /** 宿主是否深色主题 */
    @Composable
    fun isDarkTheme(): Boolean = AppConfig.isDarkTheme
}
