@file:OptIn(
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.spwmods.waerwak.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spwmods.waerwak.listenstats.OverlayInjection
import com.spwmods.waerwak.listenstats.ui.discovery.HostLayerSite
import com.spwmods.waerwak.listenstats.ui.discovery.HostMethod
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.lang.reflect.Method
import java.io.File
import java.lang.reflect.Modifier as JvmModifier

/** 侧栏行内部要恢复成宿主原始样式时用（不参与选中态抑制） */
internal val LocalUnmaskedSidebarStyle = staticCompositionLocalOf<Any?> { null }

/**
 * 左侧栏统计入口
 *
 * 结构与样式全部来自宿主（不自己画行、不自己配色）：追加在原生"音乐库"行的同一个
 * lazy-item 调用点之后，只对 `ScreenRoute$MusicLibrary` 绘制。参考 Jethuit v0.5.0。
 *
 * 统计页打开时，侧栏的真实路由仍处于"音乐库"状态，会与"统计"行同时高亮。
 * 这里的做法是在宿主唯一的 `CompositionLocalProvider` 调用点上提供一份"未选中副本"
 * 的 SidebarItemStyle，只在统计行内部恢复原样式 —— 不卸载侧栏、不改导航状态。
 */
object SidebarEntry {

    private const val MUSIC_LIBRARY = "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$MusicLibrary"

    @Volatile
    private var targets: HostSidebarTargets? = null

    @Volatile
    private var installation: AutoCloseable? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var rowMethod: Method? = null

    @Volatile
    private var routeGetter: Method? = null

    @Volatile
    private var selection: SidebarSelection? = null

    @Volatile
    private var renderErrorReported = false

    val installed: Boolean get() = installation != null

    fun statusText(): String {
        if (installed) return "已安装（音乐库下方「统计」）"
        val error = lastError
        return if (error == null) "未安装" else "未安装：" + error
    }

    /**
     * 安装侧栏入口（可选组）
     *
     * @return 句柄；宿主结构不支持时返回 null（只写 lastError，不影响面板）
     */
    @Synchronized
    fun register(t: HostSidebarTargets): AutoCloseable? {
        val existing = installation
        if (existing != null) return existing
        targets = t
        val row = try {
            resolveRow(t.row)
        } catch (e: Throwable) {
            lastError = "原生侧栏行不可用：" + (e.message ?: e.javaClass.simpleName)
            return null
        }
        rowMethod = row
        routeGetter = try {
            resolveMethod(t.routeGetter)
        } catch (e: Throwable) {
            null
        }
        selection = SidebarSelection.create(row)
        if (selection == null) {
            // 没有样式抑制也能用入口，只是统计页打开时可能双高亮
            System.err.println("[listen-stats] 侧栏样式抑制不可用（保留入口）")
        }
        val handles = HookHandles()
        try {
            val entry = t.entry
            val businessParams = ArrayList<String>(1)
            var i = 0
            val params = entry.target.parameterTypes()
            while (i < params.size - 2) {
                businessParams.add(params[i])
                i++
            }
            handles += WorkshopApi.hookRegistrar.afterComposable(
                className = entry.target.dottedOwner(),
                methodName = entry.target.name,
                inClass = entry.caller.replace('/', '.'),
                parameterTypes = businessParams,
                priority = 25
            ) { call -> SidebarSlot(call) }
            val style = t.style
            val styleSelection = selection
            if (style != null && styleSelection != null) {
                try {
                    val styleParams = ArrayList<String>(2)
                    styleParams.add("androidx.compose.runtime.ProvidedValue")
                    styleParams.add("kotlin.jvm.functions.Function2")
                    handles += WorkshopApi.hookRegistrar.replaceComposable(
                        className = style.target.dottedOwner(),
                        methodName = style.target.name,
                        inClass = style.caller.replace('/', '.'),
                        parameterTypes = styleParams,
                        priority = 50
                    ) { call -> styleSelection.onProviderCall(call) }
                } catch (e: Throwable) {
                    // 样式抑制失败不影响入口本身
                    System.err.println("[listen-stats] 侧栏样式抑制注册失败: " + e.message)
                }
            }
        } catch (e: Throwable) {
            val failure = handles.registrationFailed(e)
            lastError = e.javaClass.simpleName + "：" + (e.message ?: "")
            if (!failure.rollbackComplete) throw failure
            rowMethod = null
            return null
        }
        lastError = null
        var box: AutoCloseable? = null
        val session = AutoCloseable {
            if (installation === box) installation = null
            rowMethod = null
            routeGetter = null
            selection = null
            EntrySwitches.markSidebarReady(false)
        }
        box = session
        installation = session
        EntrySwitches.markSidebarReady(true)
        return session
    }

    fun uninstall() {
        val current = installation
        installation = null
        if (current != null) current.close()
    }

    /** 宿主侧栏行（11 参） */
    private fun resolveRow(key: HostMethod): Method = resolveMethod(key)

    private fun resolveMethod(key: HostMethod): Method {
        val loader = SidebarEntry::class.java.classLoader
        val owner = Class.forName(key.dottedOwner(), false, loader)
        val names = key.parameterTypes()
        val types = arrayOfNulls<Class<*>>(names.size)
        var i = 0
        while (i < names.size) {
            types[i] = primitiveOrClass(names[i], loader)
            i++
        }
        @Suppress("UNCHECKED_CAST")
        val method = owner.getDeclaredMethod(key.name, *types)
        if (!JvmModifier.isStatic(method.modifiers)) error("侧栏行不是静态方法")
        method.isAccessible = true
        return method
    }

    private fun primitiveOrClass(name: String, loader: ClassLoader): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> Class.forName(name, false, loader)
    }

    private fun isMusicLibrary(route: Any?): Boolean {
        if (route == null) return false
        val getter = routeGetter
        if (getter != null) {
            try {
                return getter.invoke(route)?.javaClass?.name == MUSIC_LIBRARY
            } catch (e: Throwable) {
                // 回退到直接判断 route 类型
            }
        }
        return route.javaClass.name == MUSIC_LIBRARY
    }

    /** 追加在原生行之后的插槽：只对音乐库行绘制 */
    @Composable
    private fun SidebarSlot(call: UiHookCall) {
        if (!EntrySwitches.showSidebar) return
        val route = if (call.arguments.isEmpty()) null else call.arguments[0]
        if (!isMusicLibrary(route)) return
        SidebarStatsRow()
    }

    /** 用宿主自己的侧栏行渲染"统计"，样式/尺寸/悬停全部与原生一致 */
    @Composable
    private fun SidebarStatsRow() {
        val row = rowMethod ?: return
        val composer = currentComposer
        val active = OverlayInjection.panelOpen.value
        val modifier = Modifier.semantics { contentDescription = "统计" }
            .onPointerEvent(PointerEventType.Press) { OverlayInjection.togglePanel() }
        val icon: @Composable BoxScope.() -> Unit = { SidebarGlyph() }
        val args = arrayOf<Any?>(
            "统计",
            active,
            null,
            false,
            modifier,
            null,
            null,
            icon,
            composer,
            0,
            108
        )
        try {
            row.invoke(null, *args)
        } catch (e: Throwable) {
            // 每帧都会重试：只记一次，避免刷爆 hook-diag
            if (!renderErrorReported) {
                renderErrorReported = true
                System.err.println("[listen-stats] 侧栏统计行渲染失败: " + e.message)
                try {
                    val dir = com.spwmods.waerwak.listenstats.stats.StatsStore.dataDirOrNull()
                    if (dir != null) {
                        com.spwmods.waerwak.listenstats.stats.LogFiles.append(
                            File(dir, "hook-diag.txt"),
                            "sidebar  render-failed " + e.javaClass.simpleName + ": " + e.message + "\n"
                        )
                    }
                } catch (t: Throwable) {
                    // 诊断失败不影响入口
                }
            }
        }
    }

    /** 20dp 频谱图标（与标题栏入口同一套视觉） */
    @Composable
    private fun BoxScope.SidebarGlyph() {
        val dark = HostAppearance.isDarkTheme()
        val accent = if (dark) Color(0xFF4CC2FF) else Color(0xFF2A7FB8)
        val style = LocalUnmaskedSidebarStyle.current
        // 在原生样式作用域内绘制，颜色由宿主行统一控制
        Box(
            Modifier.align(Alignment.Center).size(20.dp).drawBehind {
                val side = if (size.width < size.height) size.width else size.height
                val ox = (size.width - side) / 2f
                val oy = (size.height - side) / 2f
                val barW = side * 0.16f
                val gapW = side * 0.09f
                val totalW = barW * 4f + gapW * 3f
                val left = ox + (side - totalW) / 2f
                val base = oy + side * 0.88f
                val radius = barW * 0.5f
                val heights = floatArrayOf(0.22f, 0.46f, 0.74f, 0.32f)
                var b = 0
                while (b < 4) {
                    val h = side * heights[b]
                    val x = left + b * (barW + gapW)
                    val c = if (b == 2) accent else accent.copy(alpha = 0.55f)
                    drawRoundRect(
                        color = c,
                        topLeft = Offset(x, base - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(radius, radius)
                    )
                    b++
                }
            }
        )
    }
}

/**
 * 侧栏样式的"未选中副本"
 *
 * 反射宿主 `SidebarItemStyle`：10 参构造器 + 若干 getter。任何一步不成立就返回 null，
 * 上层退化成"不做抑制"（入口仍可用）。
 */
internal class SidebarSelection private constructor(
    private val styleType: Class<*>,
    private val constructor: java.lang.reflect.Constructor<*>,
    private val modeGetter: Method,
    private val dimensionsGetter: Method,
    private val shapeGetter: Method,
    private val indicatorGetter: Method,
    private val colorGetter: Method,
    private val textGetter: Method,
    private val backgroundGetter: Method,
    private val local: ProvidableCompositionLocal<Any?>,
    private val invokeContent: Method
) {

    fun unselected(original: Any): Any? {
        return try {
            if (!styleType.isInstance(original)) return null
            val normalColor = colorGetter.invoke(original, false)
            val normalText = textGetter.invoke(original, false)
            val normalBackground = backgroundGetter.invoke(original, false, false)
            val hoverBackground = backgroundGetter.invoke(original, false, true)
            constructor.newInstance(
                modeGetter.invoke(original),
                dimensionsGetter.invoke(original),
                normalColor,
                normalColor,
                normalBackground,
                hoverBackground,
                normalText,
                normalText,
                shapeGetter.invoke(original),
                false
            )
        } catch (e: Throwable) {
            System.err.println("[listen-stats] 侧栏未选中样式构造失败: " + e.message)
            null
        }
    }

    @Composable
    fun onProviderCall(call: UiHookCall) {
        if (call.arguments.size < 2) return
        @Suppress("UNCHECKED_CAST")
        val value = call.arguments[0] as? ProvidedValue<Any?> ?: return
        val content = call.arguments[1] ?: return
        Provider(value, content)
    }

    @Composable
    private fun Provider(value: ProvidedValue<Any?>, content: Any) {
        val original = value.value
        val matches = value.compositionLocal === local
        val active = EntrySwitches.showSidebar && OverlayInjection.panelOpen.value
        val neutral = remember(original, matches, active) {
            if (matches && active && original != null) unselected(original) else null
        }
        val effective = if (neutral != null) local provides neutral else value
        // 同一组 Provider 与内容调用：统计页开关只切换样式值，不重建侧栏
        CompositionLocalProvider(effective, LocalUnmaskedSidebarStyle provides original) {
            invokeContent.invoke(content, currentComposer, 0)
        }
    }

    companion object {

        fun create(row: Method): SidebarSelection? {
            return try {
                val owner = row.declaringClass
                val loader = owner.classLoader
                // 样式类型：同类里"单 Composer 参数、返回非基本类型"的静态方法
                var styleType: Class<*>? = null
                val methods = owner.declaredMethods
                var i = 0
                while (i < methods.size) {
                    val m = methods[i]
                    i++
                    if (!JvmModifier.isStatic(m.modifiers)) continue
                    val p = m.parameterTypes
                    if (p.size != 1) continue
                    if (p[0] != androidx.compose.runtime.Composer::class.java) continue
                    if (m.returnType.isPrimitive) continue
                    if (styleType != null) return null
                    styleType = m.returnType
                }
                val type = styleType ?: return null

                var localMethod: Method? = null
                i = 0
                while (i < methods.size) {
                    val m = methods[i]
                    i++
                    if (!JvmModifier.isStatic(m.modifiers)) continue
                    if (m.parameterCount != 0) continue
                    if (m.returnType != ProvidableCompositionLocal::class.java) continue
                    if (localMethod != null) return null
                    localMethod = m
                }
                val localValue = localMethod?.invoke(null) ?: return null
                @Suppress("UNCHECKED_CAST")
                val local = localValue as? ProvidableCompositionLocal<Any?> ?: return null

                // 10 参构造器：enum / dimensions / Long×4 / TextStyle×2 / Shape / Boolean
                var constructor: java.lang.reflect.Constructor<*>? = null
                val constructors = type.declaredConstructors
                i = 0
                while (i < constructors.size) {
                    val c = constructors[i]
                    i++
                    val p = c.parameterTypes
                    if (p.size != 10) continue
                    if (!p[0].isEnum) continue
                    if (p[2] != java.lang.Long::class.javaPrimitiveType) continue
                    if (p[6] != androidx.compose.ui.text.TextStyle::class.java) continue
                    if (p[7] != androidx.compose.ui.text.TextStyle::class.java) continue
                    if (p[8] != androidx.compose.ui.graphics.Shape::class.java) continue
                    if (p[9] != java.lang.Boolean::class.javaPrimitiveType) continue
                    if (constructor != null) return null
                    constructor = c
                }
                val ctor = constructor ?: return null
                ctor.isAccessible = true

                fun getter(typeWanted: Class<*>): Method {
                    var found: Method? = null
                    val all = type.declaredMethods
                    var k = 0
                    while (k < all.size) {
                        val m = all[k]
                        k++
                        if (JvmModifier.isStatic(m.modifiers)) continue
                        if (m.parameterCount != 0) continue
                        if (m.returnType != typeWanted) continue
                        if (found != null) error("样式 getter 不唯一：" + typeWanted.name)
                        found = m
                    }
                    return found ?: error("缺少样式 getter：" + typeWanted.name)
                }

                val modeGetter = getter(ctor.parameterTypes[0])
                val dimensionsGetter = getter(ctor.parameterTypes[1])
                val shapeGetter = getter(ctor.parameterTypes[8])
                val indicatorGetter = getter(java.lang.Boolean::class.javaPrimitiveType!!)

                var colorGetter: Method? = null
                var textGetter: Method? = null
                var backgroundGetter: Method? = null
                val all = type.declaredMethods
                i = 0
                while (i < all.size) {
                    val m = all[i]
                    i++
                    val p = m.parameterTypes
                    if (p.size == 1 && p[0] == java.lang.Boolean::class.javaPrimitiveType) {
                        if (m.returnType == java.lang.Long::class.javaPrimitiveType) colorGetter = m
                        if (m.returnType == androidx.compose.ui.text.TextStyle::class.java) textGetter = m
                    }
                    if (p.size == 2 && p[0] == java.lang.Boolean::class.javaPrimitiveType &&
                        p[1] == java.lang.Boolean::class.javaPrimitiveType &&
                        m.returnType == java.lang.Long::class.javaPrimitiveType
                    ) {
                        backgroundGetter = m
                    }
                }
                val cg = colorGetter ?: return null
                val tg = textGetter ?: return null
                val bg = backgroundGetter ?: return null
                cg.isAccessible = true
                tg.isAccessible = true
                bg.isAccessible = true
                modeGetter.isAccessible = true
                dimensionsGetter.isAccessible = true
                shapeGetter.isAccessible = true
                indicatorGetter.isAccessible = true

                val invokeContent = Class.forName("kotlin.jvm.functions.Function2", false, loader)
                    .getMethod("invoke", Any::class.java, Any::class.java)
                SidebarSelection(
                    type,
                    ctor,
                    modeGetter,
                    dimensionsGetter,
                    shapeGetter,
                    indicatorGetter,
                    cg,
                    tg,
                    bg,
                    local,
                    invokeContent
                )
            } catch (e: Throwable) {
                System.err.println("[listen-stats] 侧栏样式不可用: " + e.message)
                null
            }
        }
    }
}
