package com.spwmods.waerwak.listenstats.ui

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
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
 * 1.5.1.1 起 `install()` 返回句柄本身（由调用方的 [HookHandles] 持有）：
 * 释放失败时句柄不会被丢弃，调用方据此拒绝后续安装。
 *
 * @param onPageChange 导航到其它页面
 * @param onBack 宿主触发返回；返回 true 表示已被消费（原方法不执行）
 * @param onForward 宿主触发前进（含鼠标前进侧键）；返回 true 表示已被消费（用于恢复统计面板）
 */
@OptIn(UnstableSpwWorkshopApi::class)
object NavigationHook {

    private const val NAVIGATOR_CLASS = "com.xuncorp.voxzen.ui.navigation.Navigator"
    private const val ROUTE_CLASS = "com.xuncorp.voxzen.ui.navigation.ScreenRoute"

    @Volatile
    private var installation: AutoCloseable? = null

    /** 最近一次导航时的 Navigator 实例（弱引用，供“面板内直达配置页”用） */
    @Volatile
    private var navigatorRef: java.lang.ref.WeakReference<Any>? = null

    /** 拿到宿主 Navigator 实例；未导航过则为 null */
    fun navigatorOrNull(): Any? {
        val ref = navigatorRef
        return ref?.get()
    }

    private fun rememberNavigator(value: Any?) {
        if (value == null) return
        val ref = navigatorRef
        if (ref != null && ref.get() === value) return
        navigatorRef = java.lang.ref.WeakReference(value)
    }

    /** 供面板组合时从宿主 LocalNavigator 注入实例（比 hook 捕获更早、更可靠） */
    internal fun rememberNavigatorFromHost(value: Any?) = rememberNavigator(value)

    @Volatile
    var lastError: String? = null
        private set

    val installed: Boolean get() = installation != null

    /**
     * 安装导航 hook
     *
     * @return 句柄（已安装过时返回同一个）；不可用时返回 null 并写 [lastError]。
     *         回滚不完整时抛出 [HookRegistrationFailure]，由上层提示"需重启 SPW"。
     */
    @Synchronized
    fun install(
        onPageChange: () -> Unit,
        onBack: () -> Boolean,
        onForward: (() -> Boolean)? = null
    ): AutoCloseable? {
        val existing = installation
        if (existing != null) return existing
        val registrar = try {
            WorkshopApi.hookRegistrar
        } catch (t: Throwable) {
            lastError = "拿不到 hookRegistrar：" + (t.message ?: t.javaClass.simpleName)
            return null
        }
        val routeParams = ArrayList<String>(1)
        routeParams.add(ROUTE_CLASS)
        val pageChange = object : MethodHook() {
            override fun before(call: MethodHookParam) {
                rememberNavigator(call.thisObject)
                try {
                    onPageChange()
                } catch (t: Throwable) {
                    // 关闭面板失败不能影响宿主导航
                }
            }
        }
        val handles = HookHandles()
        try {
            handles += registrar.hookMethod(NAVIGATOR_CLASS, "navigate", routeParams, pageChange)
            handles += registrar.hookMethod(NAVIGATOR_CLASS, "replace", routeParams, pageChange)
            handles += registrar.hookMethod(NAVIGATOR_CLASS, "forward", object : MethodHook() {
                override fun before(call: MethodHookParam) {
                    rememberNavigator(call.thisObject)
                    // 前进优先用于恢复面板（鼠标前进侧键也会走宿主这个方法）；
                    // 其余情况仍按"换页关面板"处理。
                    val consumed = try {
                        onForward?.invoke() ?: false
                    } catch (t: Throwable) {
                        false
                    }
                    if (consumed) {
                        call.setResult(null)
                        return
                    }
                    try {
                        onPageChange()
                    } catch (t: Throwable) {
                        // 同上
                    }
                }
            })
            handles += registrar.hookMethod(NAVIGATOR_CLASS, "back", object : MethodHook() {
                override fun before(call: MethodHookParam) {
                    rememberNavigator(call.thisObject)
                    val consumed = try {
                        onBack()
                    } catch (t: Throwable) {
                        false
                    }
                    if (consumed) call.setResult(null)
                }
            })
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + "：" + (t.message ?: "")
            val failure = handles.registrationFailed(t)
            // 残留句柄交给上层保留：下次启用不能在同一调用点重复注册
            if (!failure.rollbackComplete) throw failure
            return null
        }
        lastError = null
        var box: HostHookInstallation? = null
        val session = HostHookInstallation(handles, true, onClosed = {
            if (installation === box) installation = null
        })
        box = session
        installation = session
        return session
    }

    /**
     * 关闭导航 hook
     *
     * 释放失败会把异常抛出（由 [HookHandles] 保留句柄），不再静默忽略。
     */
    @Synchronized
    fun uninstall() {
        val current = installation
        installation = null
        if (current != null) current.close()
    }
}
