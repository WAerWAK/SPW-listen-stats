package com.spwmods.waerwak.listenstats.ui

/**
 * 面板内直达宿主「模组管理 → 听歌统计」配置页
 *
 * 做法（借鉴 Jethuit v0.5.0 的 HostPluginConfiguration）：
 * 反射构造宿主自己的配置页路由 `ScreenRoute$ModConfigContent(pluginId)`，再通过
 * 已捕获的 Navigator 实例走宿主导航 —— 不自己造窗口、不复制配置页 UI，
 * 返回键与宿主其它页面行为一致。
 *
 * 三个前提都在失败时降级成一句提示，不影响面板本身：
 *  - Navigator 实例：由 NavigationHook 在导航回调里捕获（用户至少切过一次页面）
 *  - 路由类与构造器：宿主内部结构，签名变了就报错
 *  - 路由描述的 pluginId：构造后立刻校验一次
 */
object HostConfigRoute {

    private const val ROUTE_CLASS = "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent"

    /** 打开配置页；成功返回 null，失败返回原因 */
    fun open(pluginId: String): String? {
        if (pluginId.isBlank()) return "插件 ID 不可用"
        val navigator = NavigationHook.navigatorOrNull()
            ?: return "导航尚未就绪，请先切换一次宿主页面"
        return try {
            val loader = navigator.javaClass.classLoader
            val routeType = Class.forName(ROUTE_CLASS, false, loader)
            val constructor = routeType.getConstructor(String::class.java)
            val route = constructor.newInstance(pluginId)
            val actual = routeType.getMethod("getPluginId").invoke(route)
            if (actual != pluginId) return "配置页路由校验失败（pluginId 不匹配）"
            val navigate = navigator.javaClass.getMethod("navigate", routeType)
            navigate.invoke(navigator, route)
            null
        } catch (t: Throwable) {
            t.javaClass.simpleName + "：" + (t.message ?: "")
        }
    }
}
