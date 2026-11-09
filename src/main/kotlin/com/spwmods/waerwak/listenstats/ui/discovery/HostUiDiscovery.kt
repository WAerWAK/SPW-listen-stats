package com.spwmods.waerwak.listenstats.ui.discovery

import com.spwmods.waerwak.listenstats.ui.HostUiTargets

/**
 * 从宿主字节码里按**结构**找出 UI 注入点（不依赖混淆名）
 *
 * 判据全部是"调用关系 + 描述符 + 唯一性"，不是类名：
 * 1. 面板：调用了 `AppNavigationKt.AppNavigation(Composer, int)` 的唯一外部类，且该类里只有 1 处调用点。
 * 2. 标题栏：宿主 caption button 组件 = 值类型 10 参静态 composable（内部再调 9 参重载）；
 *    它被标题栏里某个零参 composable 调用，那个 composable 的调用类就是标题栏行；
 *    行里排在它前一个的零参 composable，就是我们要 hook 的 DebugCaptionButton 模板。
 * 3. 材质层（留给 B2）：面板调用类里那处 `(Modifier, Z, Function3, Composer, II)` 调用，
 *    且其方法体里再次调用同描述符并调用 DrawModifierKt。
 *
 * 任何一步不唯一都直接失败（宁可报错也不猜），由上层回退到版本表。
 * 刻意不用 Kotlin inline 集合/作用域函数：本项目曾在 in-process 编译下被它们触发后端 codegen 崩溃。
 */
data class HostLayerSite(val target: HostMethod, val caller: String)

data class DiscoveredUiTargets(
    val targets: HostUiTargets,
    val layer: HostLayerSite?,
    /** 导航内容 composable（只覆盖内容区；不与替换 AppNavigation 的插件抢同一点位） */
    val content: HostLayerSite?
)

object HostUiDiscovery {

    private const val NAV_OWNER = "com/xuncorp/voxzen/ui/navigation/AppNavigationKt"
    private const val NAV_METHOD = "AppNavigation"
    private const val NAV_DESC = "(Landroidx/compose/runtime/Composer;I)V"
    private const val SHARED_TRANSITION_PREFIX = "androidx/compose/animation/SharedTransitionScopeKt"
    private const val NAV_DISPLAY_PREFIX = "androidx/navigation3/ui/NavDisplayKt"

    /** 宿主 caption button 的 10 参重载（含图标槽位宽度 float） */
    private const val CAPTION10 =
        "(Lkotlin/jvm/functions/Function0;Ljava/lang/String;Landroidx/compose/ui/Modifier;ZLjava/lang/String;F" +
            "Lkotlin/jvm/functions/Function3;Landroidx/compose/runtime/Composer;II)V"

    /** 上面那个重载内部转调的 9 参版本（不含 float） */
    private const val CAPTION9 =
        "(Lkotlin/jvm/functions/Function0;Ljava/lang/String;Landroidx/compose/ui/Modifier;ZLjava/lang/String;" +
            "Lkotlin/jvm/functions/Function3;Landroidx/compose/runtime/Composer;II)V"

    /** 宿主 "Layer" 包装函数：插件把内容包进去，避免和其他插件抢 AppNavigation 的替换点 */
    private const val LAYER_DESC =
        "(Landroidx/compose/ui/Modifier;ZLkotlin/jvm/functions/Function3;Landroidx/compose/runtime/Composer;II)V"

    private const val ROW_OWNER = "androidx/compose/foundation/layout/RowKt"
    private const val ROW_METHOD = "rowMeasurePolicy"
    private const val DRAW_OWNER = "androidx/compose/ui/draw/DrawModifierKt"
    private const val COMPOSER_OWNER = "androidx/compose/runtime/ComposerKt"

    fun discover(index: HostBytecodeIndex): DiscoveredUiTargets {
        val nav = HostMethod(NAV_OWNER, NAV_METHOD, NAV_DESC)
        val pageCaller = uniquePageCaller(index, nav)
        checkPageSite(index, pageCaller, nav)

        val control = findCaptionControl(index)
        val entry = findCaptionEntry(index, control)
        val rowOwner = uniqueCallerOf(index, entry)
        val row = findRow(index, rowOwner, entry)
        val entries = collectComposableEntries(index, row)
        var position = -1
        var i = 0
        while (i < entries.size) {
            if (entries[i] == entry) position = i
            i++
        }
        check(position > 0) { "标题栏结构变化：找不到 DebugCaptionButton 的前一个 composable" }
        val predecessor = entries[position - 1]
        val predecessorCaller = uniqueCallerOf(index, predecessor)
        check(predecessorCaller == rowOwner) {
            "标题栏结构变化：DebugCaptionButton 模板的调用类不是标题栏行（" + predecessorCaller + " != " + rowOwner + "）"
        }

        val layer = findLayer(index, pageCaller)
        val content = findInnerMaterialSite(index)

        val targets = HostUiTargets(
            pageCaller = pageCaller.replace('/', '.'),
            titlebarCaller = rowOwner.replace('/', '.'),
            debugCaption = predecessor.owner.replace('/', '.'),
            reuseHostCaption = true,
            captionClass = control.owner.replace('/', '.'),
            debugCaptionMethod = predecessor.name,
            captionMethodName = control.name,
            contentOwner = if (content == null) "" else content.target.owner.replace('/', '.'),
            contentMethod = if (content == null) "" else content.target.name,
            contentDescriptor = if (content == null) "" else content.target.descriptor,
            contentCaller = if (content == null) "" else content.caller.replace('/', '.')
        )
        return DiscoveredUiTargets(targets, layer, content)
    }

    /**
     * 内层材质点位（隐藏宿主内容用）
     *
     * 多插件共存的关键：AppNavigation 这个"整页替换点"会被其它插件用更高的
     * replaceComposable priority 抢走（SPW-Lyrics 用 80，本插件默认 50），
     * 但对方替换的实现里仍会反射组合宿主 AppNavigation，所以挂到 AppNavigation
     * **内部**的调用点即可绕过争用：
     *  - 首选：AppNavigation 方法体内唯一的 SharedTransitionLayout 调用（1.19.3 类内唯一）；
     *  - 备用：AppNavigationKt.Content 方法体内唯一的 NavDisplay 调用。
     * 只有类内唯一才返回，否则回退旧点位（宁可纯色也不乱包）。
     */
    private fun findInnerMaterialSite(index: HostBytecodeIndex): HostLayerSite? {
        val shared = uniqueClassWideCall(index, SHARED_TRANSITION_PREFIX)
        if (shared != null) return HostLayerSite(shared, NAV_OWNER)
        val navDisplay = uniqueClassWideCall(index, NAV_DISPLAY_PREFIX)
        if (navDisplay != null) return HostLayerSite(navDisplay, NAV_OWNER)
        return null
    }

    /** 找 NAV_OWNER 类里对某个前缀 owner 的唯一调用（整类计数 = 1） */
    private fun uniqueClassWideCall(index: HostBytecodeIndex, ownerPrefix: String): HostMethod? {
        var found: HostMethod? = null
        var count = 0
        var i = 0
        while (i < index.methods.size) {
            var body = index.methods[i]
            i++
            if (body.method.owner != NAV_OWNER) continue
            var j = 0
            while (j < body.calls.size) {
                var call = body.calls[j]
                j++
                if (!call.owner.startsWith(ownerPrefix)) continue
                count++
                if (found == null) found = call
            }
        }
        if (count != 1) return null
        return found
    }

    // ------------------------------------------------------------ 面板

    private fun uniquePageCaller(index: HostBytecodeIndex, nav: HostMethod): String {
        var owners = ArrayList<String>()
        var i = 0
        while (i < index.methods.size) {
            var body = index.methods[i]
            i++
            if (body.method.owner == NAV_OWNER) continue
            var j = 0
            while (j < body.calls.size) {
                if (body.calls[j] == nav) {
                    if (!owners.contains(body.method.owner)) owners.add(body.method.owner)
                    break
                }
                j++
            }
        }
        check(owners.size == 1) { "面板调用点：期望唯一外部调用类，实际 " + owners.size + " 个" + describe(owners) }
        return owners[0]
    }

    private fun checkPageSite(index: HostBytecodeIndex, caller: String, nav: HostMethod) {
        var count = 0
        var i = 0
        while (i < index.methods.size) {
            var body = index.methods[i]
            i++
            if (body.method.owner != caller) continue
            var j = 0
            while (j < body.calls.size) {
                if (body.calls[j] == nav) count++
                j++
            }
        }
        check(count == 1) { "面板调用点：类内调用次数应为 1，实际 " + count }
    }

    // ------------------------------------------------------------ 标题栏

    private fun findCaptionControl(index: HostBytecodeIndex): HostMethod {
        var matches = ArrayList<HostMethod>()
        var i = 0
        while (i < index.methods.size) {
            var body = index.methods[i]
            i++
            if (body.method.descriptor != CAPTION10) continue
            if (!body.isStatic()) continue
            var j = 0
            var hit = false
            while (j < body.calls.size) {
                var call = body.calls[j]
                if (call.owner == body.method.owner && call.descriptor == CAPTION9) hit = true
                j++
            }
            if (hit) matches.add(body.method)
        }
        return unique(matches, "宿主 caption button 组件")
    }

    private fun findCaptionEntry(index: HostBytecodeIndex, control: HostMethod): HostMethod {
        var matches = ArrayList<HostMethod>()
        var i = 0
        while (i < index.methods.size) {
            var body = index.methods[i]
            i++
            if (body.method.descriptor != NAV_DESC) continue
            var j = 0
            var hit = false
            while (j < body.calls.size) {
                if (body.calls[j] == control) hit = true
                j++
            }
            if (hit) matches.add(body.method)
        }
        return unique(matches, "调用 caption button 的标题栏 composable")
    }

    /** 目标方法（或其调用）在宿主里的唯一外部调用类 */
    private fun uniqueCallerOf(index: HostBytecodeIndex, target: HostMethod): String {
        var owners = ArrayList<String>()
        var i = 0
        while (i < index.methods.size) {
            var body = index.methods[i]
            i++
            if (body.method.owner == target.owner) continue
            var j = 0
            while (j < body.calls.size) {
                if (body.calls[j] == target) {
                    if (!owners.contains(body.method.owner)) owners.add(body.method.owner)
                    break
                }
                j++
            }
        }
        check(owners.size == 1) { "调用点：期望唯一外部调用类，实际 " + owners.size + " 个" + describe(owners) + " for " + target }
        return owners[0]
    }

    private fun findRow(index: HostBytecodeIndex, rowOwner: String, entry: HostMethod): HostMethodBody {
        var matches = ArrayList<HostMethodBody>()
        var i = 0
        while (i < index.methods.size) {
            var body = index.methods[i]
            i++
            if (body.method.owner != rowOwner) continue
            var j = 0
            var hit = false
            while (j < body.calls.size) {
                if (body.calls[j] == entry) hit = true
                j++
            }
            if (hit) matches.add(body)
        }
        check(matches.size == 1) { "标题栏行：期望唯一方法，实际 " + matches.size }
        var row = matches[0]
        var rowLayout = 0
        var j = 0
        while (j < row.calls.size) {
            var call = row.calls[j]
            if (call.owner == ROW_OWNER && call.name == ROW_METHOD) rowLayout++
            j++
        }
        check(rowLayout == 1) { "标题栏行结构变化：rowMeasurePolicy 调用次数 " + rowLayout }
        return row
    }

    private fun collectComposableEntries(index: HostBytecodeIndex, row: HostMethodBody): ArrayList<HostMethod> {
        var entries = ArrayList<HostMethod>()
        var i = 0
        while (i < row.calls.size) {
            var call = row.calls[i]
            i++
            if (call.descriptor != NAV_DESC) continue
            if (call.owner == COMPOSER_OWNER) continue
            var callee = index.body(call)
            if (callee == null) continue
            if (!callee.isStatic()) continue
            entries.add(call)
        }
        return entries
    }

    // ------------------------------------------------------------ 材质层（B2 用）

    private fun findLayer(index: HostBytecodeIndex, pageCaller: String): HostLayerSite? {
        var layerCalls = ArrayList<HostMethod>()
        var i = 0
        while (i < index.methods.size) {
            var body = index.methods[i]
            i++
            if (body.method.owner != pageCaller) continue
            var j = 0
            while (j < body.calls.size) {
                if (body.calls[j].descriptor == LAYER_DESC) {
                    if (!layerCalls.contains(body.calls[j])) layerCalls.add(body.calls[j])
                }
                j++
            }
        }
        if (layerCalls.size != 1) return null
        var layerCall = layerCalls[0]
        var layerBody = index.body(layerCall) ?: return null
        if (!layerBody.isStatic()) return null
        var hasSelf = false
        var hasDraw = false
        var j = 0
        while (j < layerBody.calls.size) {
            var call = layerBody.calls[j]
            if (call.descriptor == LAYER_DESC) hasSelf = true
            if (call.owner == DRAW_OWNER) hasDraw = true
            j++
        }
        if (!hasSelf || !hasDraw) return null
        return HostLayerSite(layerCall, pageCaller)
    }

    // ------------------------------------------------------------ 工具

    private fun unique(matches: ArrayList<HostMethod>, role: String): HostMethod {
        check(matches.size == 1) { role + "：期望唯一匹配，实际 " + matches.size }
        return matches[0]
    }

    private fun describe(owners: ArrayList<String>): String {
        if (owners.isEmpty()) return ""
        var sb = StringBuilder("（")
        var i = 0
        while (i < owners.size && i < 8) {
            if (i > 0) sb.append(", ")
            sb.append(owners[i])
            i++
        }
        sb.append("）")
        return sb.toString()
    }
}
