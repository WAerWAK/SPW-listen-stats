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
import com.xuncorp.voxzen.ui.navigation.AppNavigation

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
