package com.spwmods.waerwak.listenstats.ui

import java.awt.Window

/**
 * 宿主主窗口定位（截图移除后仅保留这一处用途：判断鼠标是否点在本窗口内）
 */
internal object HostWindow {

    fun find(): Window? {
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
}
