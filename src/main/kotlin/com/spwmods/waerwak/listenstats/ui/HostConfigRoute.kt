package com.spwmods.waerwak.listenstats.ui

import androidx.compose.runtime.CompositionLocal

/**
 * 面板内直达宿主页面（插件配置页 / 键盘快捷键页）
 *
 * 做法（借鉴 Jethuit v0.5.0）：
 *  - 配置页：反射构造宿主自己的路由 `ScreenRoute$ModConfigContent(pluginId)`
 *  - 快捷键页：`ScreenRoute$KeyboardShortcut` 是 object，取 `INSTANCE`
 *  - 再通过 Navigator 实例调 **`navigate(ScreenRoute)`**（参数类型是父接口，不是具体子类）
 *
 * Navigator 实例来源有两条，任一条成立即可：
 *  1. 宿主 `LocalNavigator`（AppNavigationKt 的 ProvidableCompositionLocal），面板组合时读取；
 *  2. NavigationHook 在 navigate/replace/back/forward 回调里捕获。
 *
 * 任一前提不满足都只返回一句原因，面板本身不受影响。
 */
object HostConfigRoute {

    private const val ROUTE = "com.xuncorp.voxzen.ui.navigation.ScreenRoute"
    private const val MOD_CONFIG = "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent"
    private const val KEYBOARD_SHORTCUT = "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$KeyboardShortcut"
    private const val APP_NAV_KT = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"

    /**
     * 宿主 LocalNavigator（`CompositionLocal<Navigator>`）
     *
     * 只在 @Composable 里用 `local.current` 取值；取不到返回 null。
     */
    fun navigatorLocal(): CompositionLocal<Any?>? {
        return try {
            val type = Class.forName(APP_NAV_KT, false, HostConfigRoute::class.java.classLoader)
            val getter = type.getMethod("getLocalNavigator")
            @Suppress("UNCHECKED_CAST")
            getter.invoke(null) as? CompositionLocal<Any?>
        } catch (t: Throwable) {
            null
        }
    }

    /** 打开本插件的宿主配置页；成功返回 null，失败返回原因 */
    fun openConfig(pluginId: String): String? {
        if (pluginId.isBlank()) return "插件 ID 不可用"
        return try {
            val loader = HostConfigRoute::class.java.classLoader
            val routeType = Class.forName(MOD_CONFIG, false, loader)
            val route = routeType.getConstructor(String::class.java).newInstance(pluginId)
            val actual = routeType.getMethod("getPluginId").invoke(route)
            if (actual != pluginId) return "配置页路由校验失败（pluginId 不匹配）"
            navigate(route)
        } catch (t: Throwable) {
            describe(t)
        }
    }

    /** 打开宿主「设置 → 键盘快捷键」页；成功返回 null，失败返回原因 */
    fun openKeyboardShortcut(): String? {
        return try {
            val loader = HostConfigRoute::class.java.classLoader
            val routeType = Class.forName(KEYBOARD_SHORTCUT, false, loader)
            val route = routeType.getField("INSTANCE").get(null)
            navigate(route)
        } catch (t: Throwable) {
            describe(t)
        }
    }

    private fun navigate(route: Any): String? {
        val navigator = NavigationHook.navigatorOrNull() ?: return "导航尚未就绪，请稍后再试"
        return try {
            val loader = navigator.javaClass.classLoader
            // navigate 的参数类型是父接口 ScreenRoute，不是具体路由子类
            val routeType = Class.forName(ROUTE, false, loader)
            val navigate = navigator.javaClass.getMethod("navigate", routeType)
            navigate.invoke(navigator, route)
            null
        } catch (t: Throwable) {
            describe(t)
        }
    }

    private fun describe(t: Throwable): String {
        val cause = t.cause
        if (cause != null) {
            return t.javaClass.simpleName + "：" + (cause.message ?: cause.javaClass.simpleName)
        }
        return t.javaClass.simpleName + "：" + (t.message ?: "")
    }
}
