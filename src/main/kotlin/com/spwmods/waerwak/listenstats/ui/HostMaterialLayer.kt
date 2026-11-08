@file:OptIn(
    androidx.compose.runtime.InternalComposeApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class
)

package com.spwmods.waerwak.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.currentComposer
import com.spwmods.waerwak.listenstats.OverlayHost
import com.spwmods.waerwak.listenstats.ui.discovery.HostLayerSite
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import com.xuncorp.voxzen.ui.navigation.AppNavigation
import java.lang.reflect.Method

/**
 * 宿主材质桥接层
 *
 * 背景材质方案的核心：用 `replaceComposable` 把宿主对 `AppNavigation()` 的调用
 * 替换成本层，面板打开时不 place 宿主内容（仍 measure，保留状态），
 * 让 SPW 窗口的 DWM 壁纸 / 亚克力 / 云母透出。
 *
 * 关键设计（对齐 Jethuit）：
 * - 仍 measure 但不 place：组合与状态全部保留，关闭面板后原位恢复。
 * - `bridgeAvailable` 标志：只有本层真正进入组合才允许面板透明，
 *   否则回退不透明经典背景（防白屏露出宿主列表）。
 * - 隐藏时清焦点，防止输入框焦点残留。
 */
object HostMaterialState {

    /** 本桥接层是否已进入组合（replaceComposable 是否生效） */
    var bridgeAvailable by mutableStateOf(false)
        internal set

    /** 面板打开时置 true，隐藏宿主内容 */
    var hidden by mutableStateOf(false)

    /**
     * 注入模式：true = 包住宿主 Layer（新，不与其他插件抢 AppNavigation），
     * false = 替换 AppNavigation 调用点（旧，回退路径）
     */
    var layerMode by mutableStateOf(false)
        internal set
}

/** 保留测量、隐藏时移到屏幕外（比跳过 place 更可靠：某些 Compose 版本跳过 place 后仍绘制在 0,0） */
private data class HostPlacement(val hidden: Boolean) : LayoutModifier {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) {
            if (hidden) {
                // 移到屏幕外：既不绘制也不参与命中测试
                child.placeRelative(-100000, -100000)
            } else {
                child.placeRelative(0, 0)
            }
        }
    }
}

@Composable
fun RetainedHostContent(hidden: Boolean, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    LaunchedEffect(hidden) {
        if (hidden) focus.clearFocus(force = true)
    }
    Box(Modifier.fillMaxSize().then(HostPlacement(hidden))) {
        content()
    }
}

/**
 * 替换宿主 `AppNavigation()` 的调用点。
 *
 * 注意：这里对 `AppNavigation()` 的调用位于**插件自己的类**，
 * 不受 replaceComposable 影响（replace 只作用于宿主的调用方 inClass）。
 */
@Composable
fun HostMaterialLayer() {
    DisposableEffect(Unit) {
        HostMaterialState.bridgeAvailable = true
        onDispose { HostMaterialState.bridgeAvailable = false }
    }
    RetainedHostContent(HostMaterialState.hidden) {
        AppNavigation()
    }
}

/**
 * 解析宿主 Layer 方法
 *
 * 签名固定为 `(Modifier, boolean, Function3, Composer, int, int)`；结构判据已在发现阶段校验过，
 * 这里只做反射定位与静态方法断言。
 */
internal fun resolveLayerMethod(site: HostLayerSite): Method {
    val loader = HostMaterialState::class.java.classLoader
    val clazz = Class.forName(site.target.dottedOwner(), false, loader)
    val m = clazz.getDeclaredMethod(
        site.target.name,
        Modifier::class.java,
        java.lang.Boolean::class.javaPrimitiveType,
        kotlin.jvm.functions.Function3::class.java,
        androidx.compose.runtime.Composer::class.java,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType
    )
    check(java.lang.reflect.Modifier.isStatic(m.modifiers)) { "宿主 Layer 必须是静态方法" }
    m.isAccessible = true
    return m
}

/**
 * 包住宿主 Layer 的入口（B2 方案）
 *
 * 与之相比，替换 AppNavigation 会和所有插件抢同一个替换点；包住 Layer 只影响自己这一层，
 * 宿主内容仍然由原 Layer 组合，我们只决定"放不放下"和"上面再画什么"。
 */
@Composable
fun HostLayerStage(call: UiHookCall, layerMethod: Method) {
    DisposableEffect(Unit) {
        HostMaterialState.bridgeAvailable = true
        HostMaterialState.layerMode = true
        onDispose {
            HostMaterialState.bridgeAvailable = false
            HostMaterialState.layerMode = false
        }
    }
    val modifier = call.getArgument(0) as Modifier
    var decoration = false
    val rawDecoration = call.getArgument(1)
    if (rawDecoration is Boolean) decoration = rawDecoration
    val content = call.getArgument(2)
    val composer = currentComposer
    Box(Modifier.fillMaxSize()) {
        RetainedHostContent(HostMaterialState.hidden) {
            layerMethod.invoke(null, modifier, decoration, content, composer, 0, 0)
        }
        OverlayHost()
    }
}

