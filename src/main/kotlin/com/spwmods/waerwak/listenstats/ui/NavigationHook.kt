package com.spwmods.waerwak.listenstats.ui

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam

/**
 * 宿主导航感知
 *
 * 面板是覆盖在宿主内容区上的图层。用户点左侧导航栏切页时，面板应该自动收起。
 *
 * 之前的做法是 AWT 全局鼠标监听 + 面板矩形坐标判断，那只是**启发式**：点空白处、
 * 点标题栏都会触发关闭，而某些点击又可能漏判。这里直接 hook 宿主的导航方法，
 * 拿到精确的「页面切换」事件。
 *
 * 同时接管返回：`Navigator.back()` 时若面板/弹层可见，只关闭它们，
 * 不让宿主把页面也退掉。
 *
 * @param onPageChange 导航到其它页面
 * @param onBack 宿主触发返回；返回 true 表示已被消费（原方法不执行）
 */
@OptIn(UnstableSpwWorkshopApi::class)
object NavigationHook {

    private const val NAVIGATOR_CLASS = "com.xuncorp.voxzen.ui.navigation.Navigator"
    private const val ROUTE_CLASS = "com.xuncorp.voxzen.ui.navigation.ScreenRoute"

    private val handles = ArrayList<HookHandle>(4)

    @Volatile
    var lastError: String? = null
        private set

    val installed: Boolean
        get() = handles.isNotEmpty()

    @Synchronized
    fun install(onPageChange: () -> Unit, onBack: () -> Boolean): Boolean {
        if (handles.isNotEmpty()) return true
        val registrar = try {
            WorkshopApi.hookRegistrar
        } catch (t: Throwable) {
            lastError = "拿不到 hookRegistrar：" + (t.message ?: t.javaClass.simpleName)
            return false
        }
        val routeParams = ArrayList<String>(1)
        routeParams.add(ROUTE_CLASS)
        val pageChange = object : MethodHook() {
            override fun before(call: MethodHookParam) {
                try {
                    onPageChange()
                } catch (t: Throwable) {
                    // 关闭面板失败不能影响宿主导航
                }
            }
        }
        try {
            handles.add(registrar.hookMethod(NAVIGATOR_CLASS, "navigate", routeParams, pageChange))
            handles.add(registrar.hookMethod(NAVIGATOR_CLASS, "replace", routeParams, pageChange))
            handles.add(registrar.hookMethod(NAVIGATOR_CLASS, "forward", object : MethodHook() {
                override fun before(call: MethodHookParam) {
                    try {
                        onPageChange()
                    } catch (t: Throwable) {
                        // 同上
                    }
                }
            }))
            handles.add(registrar.hookMethod(NAVIGATOR_CLASS, "back", object : MethodHook() {
                override fun before(call: MethodHookParam) {
                    val consumed = try {
                        onBack()
                    } catch (t: Throwable) {
                        false
                    }
                    if (consumed) call.setResult(null)
                }
            }))
            lastError = null
            return true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + "：" + (t.message ?: "")
            uninstall()
            return false
        }
    }

    @Synchronized
    fun uninstall() {
        var i = 0
        while (i < handles.size) {
            try {
                handles[i].unhook()
            } catch (t: Throwable) {
                // 卸载失败忽略，进程退出时随之释放
            }
            i++
        }
        handles.clear()
    }
}
