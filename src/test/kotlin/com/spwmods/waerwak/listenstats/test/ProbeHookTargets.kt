package com.spwmods.waerwak.listenstats.test

import com.spwmods.waerwak.listenstats.ui.HostUiTargets
import com.spwmods.waerwak.listenstats.ui.HostSidebarDiscovery
import com.spwmods.waerwak.listenstats.ui.discovery.HostBytecodeIndex
import com.spwmods.waerwak.listenstats.ui.discovery.HostUiDiscovery
import java.io.File
import kotlin.system.exitProcess

/**
 * 部署前门禁：离线扫描宿主 jar，打印结构化发现结果
 *
 * 不启动 SPW、不注册任何 hook，只读字节码；发现失败会以退出码 1 结束，避免装上去才发现 (0 calls)。
 *
 * 用法（默认扫 build/host/spw-host.jar）：
 *   gradle probeHookTargets
 *   gradle probeHookTargets -PspwProbeJar="<SPW>\app\ffmpeg-x64.dll" -PspwProbeVersion=1.19.3
 */
fun main(args: Array<String>) {
    val jarPath = if (args.isNotEmpty()) args[0] else ""
    if (jarPath.isEmpty() || !File(jarPath).isFile) {
        println("找不到宿主 jar：" + jarPath)
        exitProcess(1)
    }
    val goldenVersion = if (args.size > 1) args[1] else ""
    println("宿主 jar：" + File(jarPath).absolutePath)
    val started = System.currentTimeMillis()
    var failures = 0
    try {
        val index = HostBytecodeIndex.read(File(jarPath).toPath())
        val discovered = HostUiDiscovery.discover(index)
        val elapsed = System.currentTimeMillis() - started
        val t = discovered.targets
        println("扫描完成：" + index.methods.size + " 个方法，用时 " + elapsed + " ms")
        val sidebar = t.sidebar
        if (sidebar == null) {
            val traced = HostSidebarDiscovery.discoverTraced(index)
            println("  sidebar         = （未发现：" + traced.second + "）")
        } else {
            println("  sidebar.entry   = " + sidebar.entry.target.dottedOwner() + "." + sidebar.entry.target.name +
                "  inClass=" + sidebar.entry.caller.replace('/', '.'))
            println("  sidebar.row     = " + sidebar.row.dottedOwner() + "." + sidebar.row.name)
            println("  sidebar.getter  = " + sidebar.routeGetter.dottedOwner() + "." + sidebar.routeGetter.name)
            val style = sidebar.style
            println("  sidebar.style   = " + if (style == null) "（无，不做选中态抑制）" else style.target.dottedOwner() + "." + style.target.name)
        }
        println("  pageCaller      = " + t.pageCaller)
        println("  titlebarCaller  = " + t.titlebarCaller)
        println("  debugCaption    = " + t.debugCaption + "  (method " + t.debugCaptionMethod + ")")
        println("  captionClass    = " + t.captionClass + "  (method " + t.captionMethodName + ")")
        // 1.5.1.1：标题栏/材质是可选组，降级不能当失败，但必须打印出来
        if (t.titlebarAvailable) {
            println("  titlebar        = 可用")
        } else {
            failures++
            println("  FAIL titlebar    = 结构发现失败（面板仍可注入，但顶栏入口不可用）")
        }
        var w = 0
        while (w < t.warnings.size) {
            println("  warning         = " + t.warnings[w])
            w++
        }
        val layer = discovered.layer
        if (layer == null) {
            println("  layer           = （未发现，材质将回退旧点位）")
            failures++
        } else {
            println("  layer           = " + layer.target.dottedOwner() + "." + layer.target.name + "  inClass=" + layer.caller.replace('/', '.'))
        }
        if (goldenVersion.isNotEmpty()) {
            val golden = HostUiTargets.forVersion(goldenVersion)
            if (golden == null) {
                println("版本表没有 " + goldenVersion + " 的登记值，跳过对比")
            } else {
                if (t.pageCaller != golden.pageCaller) { failures++; println("  FAIL pageCaller 与版本表不一致：" + golden.pageCaller) }
                // 标题栏降级时已单独计过失败，这里不再重复比对
                if (t.titlebarAvailable) {
                    if (t.titlebarCaller != golden.titlebarCaller) { failures++; println("  FAIL titlebarCaller 与版本表不一致：" + golden.titlebarCaller) }
                    if (t.debugCaption != golden.debugCaption) { failures++; println("  FAIL debugCaption 与版本表不一致：" + golden.debugCaption) }
                    if (t.captionClass != golden.captionClass) { failures++; println("  FAIL captionClass 与版本表不一致：" + golden.captionClass) }
                    if (t.debugCaptionMethod != golden.debugCaptionMethod) { failures++; println("  FAIL debugCaptionMethod 与版本表不一致：" + golden.debugCaptionMethod) }
                }
                if (failures == 0) println("与版本表 " + goldenVersion + " 的登记值一致")
            }
        }
    } catch (t: Throwable) {
        failures++
        println("发现失败：" + t.javaClass.simpleName + "：" + (t.message ?: ""))
    }
    if (failures > 0) {
        println("结果：" + failures + " 项不通过 —— 不要部署，先按 docs/hook-targets.md 重新定位")
        exitProcess(1)
    }
    println("结果：全部通过（可以部署）")
}
