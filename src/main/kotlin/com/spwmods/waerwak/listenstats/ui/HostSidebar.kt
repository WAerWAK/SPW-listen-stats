package com.spwmods.waerwak.listenstats.ui

import com.spwmods.waerwak.listenstats.ui.discovery.HostBytecodeIndex
import com.spwmods.waerwak.listenstats.ui.discovery.HostLayerSite
import com.spwmods.waerwak.listenstats.ui.discovery.HostMethod
import com.spwmods.waerwak.listenstats.ui.discovery.HostMethodBody

/**
 * 宿主左侧栏入口的注入点（可选组）
 *
 * 判据完全按结构（不带任何混淆名），参考 Jethuit v0.5.0 与 Salt-Extend-Online 的接入说明：
 *  1. `routeRow`：3 参 composable（route + Composer + int），方法体里同时调用
 *     "原生侧栏行"（11 参）与 `stringResource`；
 *  2. `row`：11 参静态 composable，前 5 参 = String/boolean/Modifier/boolean/Modifier，
 *     后 4 参 = Function3/Composer/int/int（中间两参是图标与配色，允许随宿主变化）；
 *  3. `routeGetter`：routeRow 里对 route 参数的零参 getter（用来判断是不是"音乐库"行）；
 *  4. `accessor`：routeRow 同类的公开静态包装（有的版本会多一层），没有就直接用 routeRow；
 *  5. `lazyCaller`：调用 accessor 的 `Function4.invoke`（4 个 Object 参数、返回 Object）。
 *
 * 任何一步不唯一就返回 null（可选组降级）：不装侧栏入口，不影响面板/标题栏入口。
 */
data class HostSidebarTargets(
    /** 追加入口的锚点：target 方法 + 其调用点所属类 */
    val entry: HostLayerSite,
    /** 原生侧栏行（11 参 composable） */
    val row: HostMethod,
    /** route 的零参 getter（判断 MusicLibrary 用） */
    val routeGetter: HostMethod,
    /** 侧栏样式 Provider 的调用点（选中态抑制用，可空） */
    val style: HostLayerSite?
)

object HostSidebarDiscovery {

    private const val STRING_RESOURCES = "org/jetbrains/compose/resources/StringResourcesKt"
    private const val COMPOSITION_LOCAL = "androidx/compose/runtime/CompositionLocalKt"
    private const val FUNCTION4_ARGS = 4

    fun discover(index: HostBytecodeIndex): HostSidebarTargets? {
        return try {
            discoverOrThrow(index)
        } catch (t: Exception) {
            null
        }
    }

    /** 带原因发现（诊断用）：第二项为空表示成功 */
    fun discoverTraced(index: HostBytecodeIndex): Pair<HostSidebarTargets?, String> {
        return try {
            val routeRow = findRouteRow(index) ?: return null to "routeRow: 未找到或不唯一"
            val row = findRowMethod(index, routeRow) ?: return null to "row: 未找到或行结构不符"
            val getter = findRouteGetter(routeRow) ?: return null to "routeGetter: 未找到或不唯一"
            val accessor = findAccessor(index, routeRow)
            val target = accessor ?: routeRow.method
            val caller = findLazyCaller(index, target) ?: return null to "lazyCaller: 未找到或不唯一"
            val style = findStyleProvider(index, routeRow)
            HostSidebarTargets(HostLayerSite(target, caller), row, getter, style) to ""
        } catch (t: Exception) {
            null to (t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    private fun discoverOrThrow(index: HostBytecodeIndex): HostSidebarTargets? {
        val routeRow = findRouteRow(index) ?: return null
        val row = findRowMethod(index, routeRow) ?: return null
        val getter = findRouteGetter(routeRow) ?: return null
        val accessor = findAccessor(index, routeRow)
        val target = accessor ?: routeRow.method
        val caller = findLazyCaller(index, target) ?: return null
        val style = findStyleProvider(index, routeRow)
        return HostSidebarTargets(HostLayerSite(target, caller), row, getter, style)
    }

    /** 原生侧栏行的结构判据（不比较中间两参：宿主可能换图标/配色类型） */
    private fun isSidebarRow(index: HostBytecodeIndex, method: HostMethod): Boolean {
        if (method.returnTypeDescriptor() != "V") return false
        val body = index.body(method) ?: return false
        if (!body.isStatic()) return false
        val p = method.parameterTypes()
        if (p.size != 11) return false
        if (p[0] != "java.lang.String") return false
        if (p[1] != "boolean") return false
        if (p[2] != "androidx.compose.ui.Modifier") return false
        if (p[3] != "boolean") return false
        if (p[4] != "androidx.compose.ui.Modifier") return false
        if (p[7] != "kotlin.jvm.functions.Function3") return false
        if (p[8] != "androidx.compose.runtime.Composer") return false
        if (p[9] != "int" || p[10] != "int") return false
        return true
    }

    private fun findRouteRow(index: HostBytecodeIndex): HostMethodBody? {
        var found: HostMethodBody? = null
        var i = 0
        while (i < index.methods.size) {
            val body = index.methods[i]
            i++
            if (!body.isStatic()) continue
            val p = body.method.parameterTypes()
            if (p.size != 3) continue
            if (p[1] != "androidx.compose.runtime.Composer" || p[2] != "int") continue
            var hasRow = false
            var hasStringResource = false
            var j = 0
            while (j < body.calls.size) {
                val call = body.calls[j]
                j++
                if (isSidebarRow(index, call)) hasRow = true
                if (call.owner == STRING_RESOURCES && call.name == "stringResource") hasStringResource = true
            }
            if (!hasRow || !hasStringResource) continue
            if (found != null) return null
            found = body
        }
        return found
    }

    private fun findRowMethod(index: HostBytecodeIndex, routeRow: HostMethodBody): HostMethod? {
        var found: HostMethod? = null
        var i = 0
        while (i < routeRow.calls.size) {
            val call = routeRow.calls[i]
            i++
            if (!isSidebarRow(index, call)) continue
            if (found != null && found != call) return null
            found = call
        }
        return found
    }

    private fun findRouteGetter(routeRow: HostMethodBody): HostMethod? {
        val routeType = routeRow.method.parameterTypes()[0]
        var found: HostMethod? = null
        var i = 0
        while (i < routeRow.calls.size) {
            val call = routeRow.calls[i]
            i++
            if (call.dottedOwner() != routeType) continue
            if (call.parameterTypes().isNotEmpty()) continue
            if (!call.returnTypeDescriptor().startsWith("L")) continue
            // 同一个 getter 可能被调用多次：只要都是同一个方法就算唯一
            if (found != null && found != call) return null
            found = call
        }
        return found
    }

    private fun findAccessor(index: HostBytecodeIndex, routeRow: HostMethodBody): HostMethod? {
        var found: HostMethod? = null
        var i = 0
        while (i < index.methods.size) {
            val body = index.methods[i]
            i++
            if (!body.isStatic()) continue
            if (!body.isPublic()) continue
            if (body.method.owner != routeRow.method.owner) continue
            if (body.method.descriptor != routeRow.method.descriptor) continue
            var callsRouteRow = false
            var j = 0
            while (j < body.calls.size) {
                if (body.calls[j] == routeRow.method) callsRouteRow = true
                j++
            }
            if (!callsRouteRow) continue
            if (found != null) return null
            found = body.method
        }
        return found
    }

    /** 调用 accessor 的 lazy-item lambda：4 个 Object 参数、返回 Object */
    private fun findLazyCaller(index: HostBytecodeIndex, target: HostMethod): String? {
        var found: String? = null
        var i = 0
        while (i < index.methods.size) {
            val body = index.methods[i]
            i++
            if (body.method.owner == target.owner) continue
            var callsTarget = false
            var j = 0
            while (j < body.calls.size) {
                if (body.calls[j] == target) callsTarget = true
                j++
            }
            if (!callsTarget) continue
            val p = body.method.parameterTypes()
            if (p.size != FUNCTION4_ARGS) continue
            if (body.method.returnTypeDescriptor() != "Ljava/lang/Object;") continue
            if (found != null && found != body.method.owner) return null
            found = body.method.owner
        }
        return found
    }

    /** 侧栏样式 Provider 的调用点（可选；拿不到就只做入口，不做选中态抑制） */
    private fun findStyleProvider(index: HostBytecodeIndex, routeRow: HostMethodBody): HostLayerSite? {
        var found: HostLayerSite? = null
        var i = 0
        while (i < index.methods.size) {
            val body = index.methods[i]
            i++
            if (body.method.owner != routeRow.method.owner) continue
            var j = 0
            while (j < body.calls.size) {
                val call = body.calls[j]
                j++
                if (call.owner != COMPOSITION_LOCAL) continue
                if (call.name != "CompositionLocalProvider") continue
                val p = call.parameterTypes()
                if (p.size != 4) continue
                if (p[0] != "androidx.compose.runtime.ProvidedValue") continue
                if (p[1] != "kotlin.jvm.functions.Function2") continue
                if (p[2] != "androidx.compose.runtime.Composer" || p[3] != "int") continue
                if (found != null) return null
                found = HostLayerSite(call, routeRow.method.owner)
            }
        }
        return found
    }
}
