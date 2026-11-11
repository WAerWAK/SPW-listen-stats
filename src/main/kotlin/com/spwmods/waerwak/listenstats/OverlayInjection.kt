@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.TrackAgg
import com.spwmods.waerwak.listenstats.ui.UiStateStore
import com.spwmods.waerwak.listenstats.ui.discovery.HostLayerSite
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏 Overlay + 边缘浮动入口
 *
 * Hook 目标由 [com.spwmods.waerwak.listenstats.ui.HostUiTargets] 按宿主版本给出（SPW 1.19.1 已 javap 实测）：
 * - `AppNavigationKt.AppNavigation(Composer, int)` 是顶层导航入口，
 *   唯一调用点在混淆类 `androidx.compose.ui.\u0443`（invokestatic 实测）。
 *   注意：混淆名每次宿主更新都会重排（1.19.0 是 \u06C4），升级后需重新定位。
 * - 之前的 `access$Content` 在导航框架**内部**（仅覆盖内容区）；
 *   迁到 `AppNavigation` 后覆盖整个窗口（含侧栏与顶栏），
 *   这是背景材质方案（隐藏宿主内容 + 透明背景）的前提。
 */
object OverlayInjection {

    private const val TARGET_CLASS = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"
    private const val TARGET_METHOD = "AppNavigation"

    /**
     * 是否用"包住宿主 Layer"的方式注入（B2 方案）
     *
     * 暂时关闭，原因（1.4.15 实测）：
     * - 面板位置会跑偏：Layer 的 content 铺满整个窗口，包住它之后我们的面板被放在窗口原点，
     *   于是顶栏被"刷新"按钮盖住；而旧方案里面板是插在 AppNavigation 的调用点上，天然落在内容区
     *   （侧栏右侧、顶栏下方）。
     * - 隐藏宿主内容也会连带隐藏侧栏与顶栏，而旧方案只隐藏导航内容，外观才是对的。
     * 代码与结构判据都保留（HostLayerStage / resolveLayerMethod / resolved.layer），
     * 等找到"只隐藏在内容区、且面板落在内容区"的组合方式后再打开。
     */
    private const val USE_LAYER_INJECTION = false


    @Volatile
    private var handle: HookHandle? = null

    /** replaceComposable 的材质桥接层句柄（可选，失败静默降级） */
    @Volatile
    private var materialHandle: HookHandle? = null

    /**
     * 本次安装的全部句柄（1.5.1.1）
     *
     * 面板 / 材质 / 导航 / 顶栏 全部挂在这里：只有 close 成功才算释放，
     * 失败时阻止下一次安装并在信息页提示重启。
     */
    @Volatile
    private var installation: com.spwmods.waerwak.listenstats.ui.HostHookInstallation? = null

    /** 上一轮释放不完整：宿主里还留着 hook，重启前不允许再装 */
    @Volatile
    private var blockedRollback: Throwable? = null

    @Volatile
    private var installing = false

    /** 面板开关状态（提到 object 级，快捷键才能切换） */
    val panelOpen = mutableStateOf(false)

    /** 内容最大宽度百分比（50–100，默认 80）：信息页可调，改后立即生效 */
    val contentPercent = mutableStateOf(80)

    /** 全局字体增加值（百分比，0–60，默认 0）：所有字号同比例放大 */
    val fontExtraPercent = mutableStateOf(0)

    /**
     * 时长单位开关（Compose 状态）
     *
     * 概览/榜单可能一直处于组合中，直接读 UiStateStore 的普通字段不会触发重组；
     * 这里用状态持有，信息页切换后同屏的两个页面立即跟着变。
     */
    val hmsRanking = mutableStateOf(false)

    /**
     * 悬浮圆钮入口是否显示
     *
     * 配置页的「悬浮按钮-卸载」只隐藏圆钮，**不**卸载注入本身：
     * 顶栏按钮、导航感知、面板都保留，否则面板就再也唤不出来了。
     */
    val entryEnabled = mutableStateOf(true)

    /**
     * 关闭最上层弹层（阈值编辑 / 曲目详情 / 当天明细 / 日历浮窗）。
     * 由 [StatsOverlay] 注册；返回 true 表示确实关了一个弹层。
     */
    @Volatile
    var dismissTopLayer: (() -> Boolean)? = null

    /** 关闭面板（统一入口，导航/返回/浮钮共用） */
    fun closePanel() {
        panelOpen.value = false
    }

    /**
     * 面板内容区在窗口内的几何范围（像素）。
     * 用于判断鼠标点击是否落在面板外侧（左侧导航栏 / 标题栏），点击外侧时自动关闭面板。
     */
    @Volatile
    var contentBoundsInWindow: java.awt.Rectangle? = null

    /** 配置页请求打开面板时指定的初始页（-1 表示不指定） */
    val pendingTab = mutableStateOf(-1)

    /** 上次停留的页面：鼠标前进恢复面板时回到同一页 */
    val lastTab = mutableStateOf(0)

    /** 面板是"被返回键关掉"的：下一次前进把它恢复回来（鼠标前进侧键） */
    @Volatile
    private var forwardRestorePending = false

    /** 快捷键入口：没有面板就装一下，有则开关 */
    fun togglePanel() {
        if (handle == null) {
            val message = install()
            if (handle != null) panelOpen.value = true
            toast(message, handle != null)
            return
        }
        panelOpen.value = !panelOpen.value
    }

    @Volatile
    var lastError: String? = null
        private set

    /** 把注入结果追加到数据目录的 hook-diag.txt，宿主更新后定位失败原因用 */
    private fun writeDiag(tag: String, message: String) {
        try {
            val dir = StatsStore.dataDirOrNull() ?: return
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val line = stamp + "  " + tag + "  " + message + "\n"
            com.spwmods.waerwak.listenstats.stats.LogFiles.append(
                java.io.File(dir, "hook-diag.txt"),
                line
            )
        } catch (t: Throwable) {
            // 诊断写不进去不影响功能
        }
    }

    fun installed(): Boolean = handle != null

    @JvmStatic
    @JvmName("installOverlayFromConfig")
    fun installOverlayFromConfig() {
        val wasInstalled = handle != null
        val message = install()
        entryEnabled.value = true
        try {
            com.spwmods.waerwak.listenstats.ui.UiStateStore.saveEntryEnabled(true)
        } catch (t: Throwable) {
            // 持久化失败不影响本次生效
        }
        if (wasInstalled) {
            toast("悬浮按钮已恢复显示", true)
        } else {
            toast(message, message.startsWith("已注册"))
        }
    }

    @JvmStatic
    @JvmName("uninstallOverlayFromConfig")
    fun uninstallOverlayFromConfig() {
        // 只隐藏圆钮：注入、顶栏按钮、导航感知全部保留，面板仍可由顶栏按钮打开
        entryEnabled.value = false
        try {
            com.spwmods.waerwak.listenstats.ui.UiStateStore.saveEntryEnabled(false)
        } catch (t: Throwable) {
            // 持久化失败不影响本次生效
        }
        if (handle == null) {
            toast("悬浮按钮已隐藏（当前未注入，可在需要时重新安装）", true)
        } else {
            toast("悬浮按钮已隐藏，顶栏按钮仍可打开统计页", true)
        }
    }

    /** 宿主版本（注入目标按版本选择；取不到时按最新已知处理） */
    private fun hostVersion(): String = try {
        HostProbe.pluginContext()?.spwVersion ?: ""
    } catch (t: Throwable) {
        ""
    }

    /** 配置页：安装顶栏按钮 */
    @JvmStatic
    @JvmName("installTitlebarFromConfig")
    fun installTitlebarFromConfig() {
        val resolved = com.spwmods.waerwak.listenstats.ui.discovery.HostTargetResolver.resolve(
            pluginVersion = HostProbe.pluginContext()?.pluginVersion ?: "?",
            hostVersion = hostVersion(),
            cacheDir = StatsStore.dataDirOrNull()
        )
        if (resolved == null) {
            toast("安装失败：无法识别宿主界面", false)
            return
        }
        val ok = com.spwmods.waerwak.listenstats.ui.TitlebarButton.install(resolved.targets)
        if (ok) {
            toast("顶栏按钮已安装（标题栏 Quick 左侧）", true)
        } else {
            toast("安装失败：" + (com.spwmods.waerwak.listenstats.ui.TitlebarButton.lastError ?: "未知原因"), false)
        }
    }

    /** 配置页：卸载顶栏按钮 */
    @JvmStatic
    @JvmName("uninstallTitlebarFromConfig")
    fun uninstallTitlebarFromConfig() {
        try {
            com.spwmods.waerwak.listenstats.ui.TitlebarButton.uninstall()
            toast("顶栏按钮已卸载", true)
        } catch (t: Throwable) {
            toast("卸载失败：" + (t.message ?: t.javaClass.simpleName), false)
        }
    }

    /** 配置页入口：打开统计面板并直接定位到「信息」页 */
    @JvmStatic
    @JvmName("openStatsPanelFromConfig")
    fun openStatsPanelFromConfig() {
        if (handle == null) install()
        if (handle == null) {
            toast("打开失败：" + (lastError ?: "未知原因"), false)
            return
        }
        pendingTab.value = 3
        panelOpen.value = true
        toast("已打开统计面板，可在「信息 → 采集」调整计数门槛", true)
    }

    /** 从注入目标里取出内层材质点位；版本表/缓存没给出时返回 null */
    private fun innerMaterialSite(targets: com.spwmods.waerwak.listenstats.ui.HostUiTargets): HostLayerSite? {
        if (targets.contentOwner.isEmpty() || targets.contentMethod.isEmpty() ||
            targets.contentDescriptor.isEmpty() || targets.contentCaller.isEmpty()
        ) {
            return null
        }
        return HostLayerSite(
            com.spwmods.waerwak.listenstats.ui.discovery.HostMethod(
                targets.contentOwner.replace('.', '/'),
                targets.contentMethod,
                targets.contentDescriptor
            ),
            targets.contentCaller.replace('.', '/')
        )
    }

    /**
     * 尝试包住宿主 Layer
     *
     * 成功返回 true（handle 已就位）；失败会回滚并把原因写进 hook-diag，由调用方回退旧点位。
     */
    private fun installLayerIfPossible(site: HostLayerSite?): Boolean {
        if (site == null) return false
        return try {
            val layerMethod = com.spwmods.waerwak.listenstats.ui.resolveLayerMethod(site)
            handle = WorkshopApi.hookRegistrar.replaceComposable(
                className = site.target.dottedOwner(),
                methodName = site.target.name,
                inClass = site.caller.replace('/', '.'),
                parameterTypes = listOf(
                    "androidx.compose.ui.Modifier",
                    "boolean",
                    "kotlin.jvm.functions.Function3"
                )
            ) { call: UiHookCall ->
                com.spwmods.waerwak.listenstats.ui.HostLayerStage(call, layerMethod)
            }
            true
        } catch (t: Throwable) {
            writeDiag(
                "install",
                "layer-failed " + t.javaClass.simpleName + ": " + t.message + "（回退 AppNavigation 点位）"
            )
            try {
                handle?.unhook()
            } catch (e: Throwable) {
                // 回滚失败不影响后续
            }
            handle = null
            false
        }
    }

    /**
     * 执行一次界面注入（已被调度到宿主界面线程）
     *
     * 会话代号在扫描前后各校验一次：宿主停用插件时走的是后台 FutureTask
     * （SPW 1.19.3 只等 3 秒），扫描要 1.5 秒左右，停用可能正好发生在中间。
     */
    private fun installSession(session: Long): String {
        if (!com.spwmods.waerwak.listenstats.ui.HostInstallSession.isCurrent(session)) {
            writeDiag("install", "cancelled session=" + session + "（插件已停用，放弃本次注册）")
            return "已取消：插件已停用"
        }
        if (installation != null) return "已注册（无需重复）"
        val blocked = blockedRollback
        if (blocked != null) {
            lastError = "上一轮界面注入未能完全释放，请重启 SPW 后再启用"
            return "失败：" + lastError
        }
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            lastError = "未授予 CLASS_TRANSFORM"
            writeDiag("install", "no-permission session=" + session)
            return "失败：未授予 CLASS_TRANSFORM 权限"
        }
        val startedAt = System.nanoTime()
        // 注入目标：缓存 → 结构化发现（宿主字节码）→ 版本表回退；都失败就不猜，明确报错
        val hostVer = hostVersion()
        val pluginVersion = HostProbe.pluginContext()?.pluginVersion ?: "?"
        var ignoreCache = false
        var resolved = com.spwmods.waerwak.listenstats.ui.discovery.HostTargetResolver.resolve(
            pluginVersion = pluginVersion,
            hostVersion = hostVer,
            cacheDir = StatsStore.dataDirOrNull(),
            ignoreCache = ignoreCache
        )
        // 缓存命中也要先确认反射能真的拿到目标（宿主重排后描述符可能还在但签名已变），
        // 预检失败就丢掉缓存全扫一次（只重试一次）。
        if (resolved != null && resolved.source == "cache") {
            val problem = cachePreflight(resolved.targets)
            if (problem != null) {
                writeDiag("install", "cache-preflight-failed " + problem + "，改为全扫描")
                ignoreCache = true
                com.spwmods.waerwak.listenstats.ui.discovery.HostTargetResolver.forget()
                resolved = com.spwmods.waerwak.listenstats.ui.discovery.HostTargetResolver.resolve(
                    pluginVersion = pluginVersion,
                    hostVersion = hostVer,
                    cacheDir = StatsStore.dataDirOrNull(),
                    ignoreCache = true
                )
            }
        }
        if (!com.spwmods.waerwak.listenstats.ui.HostInstallSession.isCurrent(session)) {
            writeDiag("install", "cancelled session=" + session + "（发现期间插件被停用）")
            return "已取消：插件已停用"
        }
        if (resolved == null) {
            val shown = if (hostVer.isEmpty()) "未知" else hostVer
            lastError = "无法识别宿主界面（" + shown + "）"
            writeDiag("install", "no-target host=" + shown + "  session=" + session + "  skip-ui-injection（采集不受影响）")
            return "无法识别宿主界面（" + shown + "）：面板与顶栏入口暂不可用，听歌数据仍会正常记录"
        }
        val uiTargets = resolved.targets
        lastError = null
        writeDiag(
            "resolve",
            "source=" + resolved.source +
                "  session=" + session +
                "  page=" + uiTargets.pageCaller +
                "  titlebar=" + uiTargets.titlebarCaller +
                "  caption=" + uiTargets.debugCaption +
                (if (resolved.detail.isEmpty()) "" else "  " + resolved.detail)
        )
        val handles = com.spwmods.waerwak.listenstats.ui.HookHandles()
        return try {
            // 首选：包住宿主 Layer（不与其它插件抢 AppNavigation 的替换点）
            val layerInstalled = if (USE_LAYER_INJECTION) installLayerIfPossible(resolved.layer) else false
            if (layerInstalled) {
                val layerHandle = handle
                if (layerHandle != null) handles += layerHandle
            }
            if (!layerInstalled) {
                // 回退路径：afterComposable 装面板 + replaceComposable 装材质层
                val overlayHandle = WorkshopApi.hookRegistrar.afterComposable(
                    className = TARGET_CLASS,
                    methodName = TARGET_METHOD,
                    inClass = uiTargets.pageCaller
                ) { _: UiHookCall ->
                    OverlayHost()
                }
                handle = overlayHandle
                handles += overlayHandle
                // 背景材质桥接层（可选）：优先替换 AppNavigation **内部**的宿主内容调用，
                // 这样不会被其它插件（如 SPW-Lyrics，priority 80）抢占同一替换点；
                // 找不到内层点时回退旧做法（替换 AppNavigation 调用）。
                // 都失败则静默降级为不透明背景。
                val innerSite = innerMaterialSite(uiTargets)
                if (innerSite != null) {
                    try {
                        val innerMethod = com.spwmods.waerwak.listenstats.ui.resolveComposableMethod(innerSite)
                        val innerHandle = WorkshopApi.hookRegistrar.replaceComposable(
                            className = innerSite.target.dottedOwner(),
                            methodName = innerSite.target.name,
                            inClass = innerSite.caller.replace('/', '.'),
                            parameterTypes = listOf(
                                "androidx.compose.ui.Modifier",
                                "kotlin.jvm.functions.Function3"
                            )
                        ) { call: UiHookCall ->
                            com.spwmods.waerwak.listenstats.ui.HostInnerMaterialStage(call, innerMethod)
                        }
                        materialHandle = innerHandle
                        handles += innerHandle
                        writeDiag(
                            "material",
                            "inner  " + innerSite.target.owner + "#" + innerSite.target.name +
                                " @ " + innerSite.caller
                        )
                    } catch (t: Throwable) {
                        writeDiag(
                            "material",
                            "inner-failed " + t.javaClass.simpleName + ": " + t.message
                        )
                        materialHandle = null
                    }
                }
                if (materialHandle == null) {
                    try {
                        val fallbackHandle = WorkshopApi.hookRegistrar.replaceComposable(
                            className = TARGET_CLASS,
                            methodName = TARGET_METHOD,
                            inClass = uiTargets.pageCaller
                        ) { _: UiHookCall ->
                            com.spwmods.waerwak.listenstats.ui.HostMaterialLayer()
                        }
                        materialHandle = fallbackHandle
                        handles += fallbackHandle
                        writeDiag("material", "appnav（内层点不可用，回退旧点位）")
                    } catch (t: Throwable) {
                        System.err.println("[listen-stats] 材质桥接层注册失败（降级为不透明背景）: " + t.message)
                        materialHandle = null
                        writeDiag("material", "none " + t.javaClass.simpleName + ": " + t.message)
                    }
                }
            }
            // 读取 UI 状态（内容宽度百分比等）
            contentPercent.value = UiStateStore.loadContentPercent()
            fontExtraPercent.value = UiStateStore.loadFontExtraPercent()
            entryEnabled.value = UiStateStore.loadEntryEnabled()
            hmsRanking.value = UiStateStore.loadDurationHmsRanking()
            // 导航感知：切页时关面板，返回键优先关弹层/面板（可选组，失败只降级）
            var navError: String? = null
            try {
                val navHandle = com.spwmods.waerwak.listenstats.ui.NavigationHook.install(
                onPageChange = {
                    forwardRestorePending = false
                    if (panelOpen.value) closePanel()
                },
                onBack = {
                    val dismiss = dismissTopLayer
                    if (dismiss != null && dismiss()) {
                        true
                    } else if (panelOpen.value) {
                        closePanel()
                        // 记录"这次关闭来自返回键"：鼠标前进侧键可以把它恢复回来
                        forwardRestorePending = true
                        true
                    } else {
                        forwardRestorePending = false
                        false
                    }
                },
                onForward = {
                    if (forwardRestorePending && !panelOpen.value) {
                        forwardRestorePending = false
                        panelOpen.value = true
                        true
                    } else {
                        forwardRestorePending = false
                        false
                    }
                }
                )
                if (navHandle != null) {
                    handles += navHandle
                } else {
                    navError = com.spwmods.waerwak.listenstats.ui.NavigationHook.lastError
                }
            } catch (t: Throwable) {
                if (com.spwmods.waerwak.listenstats.ui.HookFailurePolicy.noRetry(t)) throw t
                if (t is com.spwmods.waerwak.listenstats.ui.HookRegistrationFailure) {
                    if (!t.rollbackComplete) throw t
                    navError = t.message
                } else {
                    navError = t.javaClass.simpleName + "：" + (t.message ?: "")
                }
            }
            // 标题栏入口（可选组：结构发现失败/注册失败都只降级，浮钮仍在）
            var titlebarError: String? = null
            try {
                val titlebarHandle = com.spwmods.waerwak.listenstats.ui.TitlebarButton.register(uiTargets)
                if (titlebarHandle != null) {
                    handles += titlebarHandle
                } else {
                    titlebarError = com.spwmods.waerwak.listenstats.ui.TitlebarButton.lastError
                }
            } catch (t: Throwable) {
                if (com.spwmods.waerwak.listenstats.ui.HookFailurePolicy.noRetry(t)) throw t
                if (t is com.spwmods.waerwak.listenstats.ui.HookRegistrationFailure) {
                    if (!t.rollbackComplete) throw t
                    titlebarError = t.message
                } else {
                    titlebarError = t.javaClass.simpleName + "：" + (t.message ?: "")
                }
            }
            lastError = null
            val warnings = uiTargets.warnings
            if (titlebarError != null || navError != null || warnings.isNotEmpty()) {
                writeDiag(
                    "install-partial",
                    "titlebar=" + (titlebarError ?: "-") +
                        " nav=" + (navError ?: "-") +
                        " warnings=" + warnings.size +
                        (if (warnings.isEmpty()) "" else " " + warnings)
                )
            }
            writeDiag(
                "install",
                "ok session=" + session +
                    " handles=" + handles.size +
                    " layer=" + layerInstalled +
                    " material=" + (materialHandle != null) +
                    " nav=" + (navError == null) +
                    " titlebar=" + (titlebarError == null) +
                    " titlebarErr=" + (titlebarError ?: "-") +
                    " warnings=" + warnings.size +
                    " via=" + resolved.source +
                    " ms=" + ((System.nanoTime() - startedAt) / 1_000_000L)
            )
            val complete = navError == null && titlebarError == null &&
                materialHandle != null && warnings.isEmpty()
            // 只有“完整安装”才把描述串写盘：否则下次启动会命中一份不完整的目标
            if (complete && resolved.fromScan) {
                try {
                    com.spwmods.waerwak.listenstats.ui.discovery.HostTargetResolver.saveCache(
                        pluginVersion,
                        StatsStore.dataDirOrNull(),
                        resolved
                    )
                } catch (t: Throwable) {
                    writeDiag("install", "cache-write-skipped " + t.javaClass.simpleName + ": " + t.message)
                }
            }
            var box: com.spwmods.waerwak.listenstats.ui.HostHookInstallation? = null
            val installed = com.spwmods.waerwak.listenstats.ui.HostHookInstallation(
                handles,
                complete,
                onClosed = { failure ->
                    if (installation === box) installation = null
                    handle = null
                    materialHandle = null
                    com.spwmods.waerwak.listenstats.ui.HostMaterialState.bridgeAvailable = false
                    com.spwmods.waerwak.listenstats.ui.HostMaterialState.hidden = false
                    com.spwmods.waerwak.listenstats.ui.HostMaterialState.layerMode = false
                    if (failure != null) {
                        // 释放不完整：保留阻断状态，下一次 install 会直接拒绝
                        blockedRollback = failure
                        System.err.println("[listen-stats] 释放界面 hook 失败（需重启 SPW）: " + failure.message)
                    }
                }
            )
            box = installed
            installation = installed
            "已注册：窗口右下角会出现一个「只露一点」的圆"
        } catch (t: Throwable) {
            // 中止：尽力回滚；回滚不完整时保留原因，重启前不再尝试安装
            val failure = handles.registrationFailed(t)
            handle = null
            materialHandle = null
            installation = null
            lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
            writeDiag(
                "install",
                "failed " + t.javaClass.simpleName + ": " + t.message +
                    " rollbackComplete=" + failure.rollbackComplete
            )
            if (!failure.rollbackComplete) {
                blockedRollback = failure
                lastError = "界面注入失败且未能完全回滚，请重启 SPW 后再启用"
                "失败：界面注入未能完全回滚，请重启 SPW 后再启用"
            } else {
                "失败：${t.javaClass.simpleName} — ${t.message ?: ""}"
            }
        }
    }

    /**
     * 安装界面注入（对外唯一入口）
     *
     * - 扫描/发现仍在调用线程（通常是后台线程）完成，不阻塞宿主启动
     * - **注册与释放统一在宿主界面线程（AWT EDT）执行**：字节码插桩与 Compose 组合
     *   都归宿主界面线程管，跨线程注册是 1.5.1 遗留的隐患
     * - 等待 EDT 最多 3 秒；超时放弃本次注册（宿主界面线程忙时不让插件拖住启动）
     */
    fun install(): String {
        val blocked = blockedRollback
        if (blocked != null) {
            lastError = "上一轮界面注入未能完全释放，请重启 SPW 后再启用"
            return "失败：" + lastError
        }
        val early = claimInstalling()
        if (early != null) return early
        val session = com.spwmods.waerwak.listenstats.ui.HostInstallSession.begin()
        val outcome = InstallOutcome()
        val task = object : Runnable {
            override fun run() {
                try {
                    outcome.message = installSession(session)
                } catch (t: Throwable) {
                    outcome.failure = t
                }
            }
        }
        if (java.awt.EventQueue.isDispatchThread()) {
            task.run()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            java.awt.EventQueue.invokeLater(object : Runnable {
                override fun run() {
                    try {
                        task.run()
                    } finally {
                        latch.countDown()
                    }
                }
            })
            val done = try {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (t: InterruptedException) {
                false
            }
            if (!done) {
                releaseInstalling()
                lastError = "宿主界面线程忙，本次界面注入已跳过"
                writeDiag("install", "edt-timeout session=" + session)
                return "失败：宿主界面线程忙，界面注入已跳过（不影响听歌记录）"
            }
        }
        releaseInstalling()
        val thrown = outcome.failure
        if (thrown != null) {
            lastError = thrown.javaClass.simpleName + ": " + (thrown.message ?: "")
            writeDiag("install", "unexpected " + thrown.javaClass.simpleName + ": " + thrown.message)
            return "失败：" + (thrown.message ?: thrown.javaClass.simpleName)
        }
        return outcome.message ?: "已注册"
    }

    @Synchronized
    private fun claimInstalling(): String? {
        if (installation != null) return "已注册（无需重复）"
        if (installing) return "正在注册（请稍候）"
        installing = true
        return null
    }

    @Synchronized
    private fun releaseInstalling() {
        installing = false
    }

    @Synchronized
    private fun takeInstallation(): com.spwmods.waerwak.listenstats.ui.HostHookInstallation? {
        val owned = installation
        installation = null
        return owned
    }

    /**
     * 缓存命中后的反射预检
     *
     * 注入点缓存只存描述串（类名/方法名/描述符），不保证反射一定拿得到：
     * 宿主重排后可能某一行残留旧值。预检失败就丢掉缓存全扫一次。
     */
    private fun cachePreflight(targets: com.spwmods.waerwak.listenstats.ui.HostUiTargets): String? {
        if (targets.titlebarAvailable) {
            val error = com.spwmods.waerwak.listenstats.ui.TitlebarButton.preflight(targets)
            if (error != null) return "caption: " + error
        }
        val site = innerMaterialSite(targets)
        if (site != null) {
            try {
                com.spwmods.waerwak.listenstats.ui.resolveComposableMethod(site)
            } catch (t: Throwable) {
                return "material: " + (t.message ?: t.javaClass.simpleName)
            }
        }
        return null
    }

    /**
     * 启动时后台安装
     *
     * 首次启用需要扫描宿主字节码（约 1~2 秒，之后走缓存），不能阻塞插件启动流程；
     * 扫描期间面板/顶栏入口要等它完成（通常宿主还在加载界面，用户感知不到）。
     */
    /**
     * 让在途的扫描/发现立即作废（停用第一步调用，句柄稍后再释放）
     *
     * 与 [uninstall] 分开：停用流程里先斩断“还要不要装”，再从容关句柄，
     * 避免出现“停用超时 → 提示必须重启”的互等。
     */
    fun cancelPending() {
        val session = com.spwmods.waerwak.listenstats.ui.HostInstallSession.stop()
        writeDiag("install", "cancel-pending session=" + session)
    }

    fun installAsync() {
        writeDiag("install", "async-start")
        val thread = Thread({
            try {
                val message = install()
                System.err.println("[listen-stats] 界面注入：" + message)
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 界面注入异常：" + t.javaClass.simpleName + ": " + t.message)
            }
        }, "listen-stats-ui-install")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 卸载全部界面 hook
     *
     * 1. 先让会话代号失效：在途的扫描/发现结果全部作废（不会再注册）
     * 2. 再逆序关闭句柄：**不在界面线程上等后台**，也不等界面线程（停用任务只有 3 秒）
     * 3. 任一释放失败 → 保留阻断状态，提示重启
     */
    fun uninstall() {
        val session = com.spwmods.waerwak.listenstats.ui.HostInstallSession.stop()
        val current = takeInstallation()
        if (current != null) {
            try {
                current.close()
                writeDiag("install", "release session=" + session + " ok")
            } catch (t: Throwable) {
                blockedRollback = t
                writeDiag(
                    "install",
                    "release-failed session=" + session + " " + t.javaClass.simpleName + ": " + t.message
                )
                System.err.println("[listen-stats] 卸载界面 hook 失败（需重启 SPW）: " + t.message)
            }
        } else {
            writeDiag("install", "release session=" + session + " noop（本次未完成安装）")
        }
        // 兜底：只在没有完整安装对象时才会走到这里（半截安装 / 旧路径）
        val overlay = handle
        if (current == null && overlay != null) {
            try {
                overlay.unhook()
            } catch (t: Throwable) {
                blockedRollback = t
                System.err.println("[listen-stats] 卸载 Overlay 失败（需重启 SPW）: " + t.message)
            }
        }
        handle = null
        materialHandle = null
        com.spwmods.waerwak.listenstats.ui.HostMaterialState.bridgeAvailable = false
        com.spwmods.waerwak.listenstats.ui.HostMaterialState.hidden = false
        com.spwmods.waerwak.listenstats.ui.HostMaterialState.layerMode = false
    }

    private fun toast(text: String, ok: Boolean) {
        try {
            WorkshopApi.ui.toast(
                text,
                if (ok) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Error
            )
        } catch (t: Throwable) {
            System.err.println("[listen-stats] $text")
        }
    }
}



/** 配置页按钮入口 */
object StatsActions {

    @JvmStatic
    @JvmName("resetStats")
    fun resetStats() {
        toast(StatsStore.reset(), true)
    }


    /**
     * 弹出对话框：备份全部数据
     *
     * 对话框与 zip 打包都在后台线程（[com.spwmods.waerwak.listenstats.ui.BackgroundDialogs]）：
     * 上万行事件打包要几百毫秒，放在宿主界面线程上会明显掉帧。
     */
    @JvmStatic
    @JvmName("backupDataDialog")
    fun backupDataDialog() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.run({
            val file = com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.saveFile(
                "备份听歌统计数据",
                "listen-stats-backup-" + stamp + ".zip"
            )
            if (file == null) null else StatsStore.backupTo(file)
        }, { message ->
            if (message != null) toast(message, message.startsWith("已备份"))
        })
    }

    /**
     * 弹出对话框：合并导入另一份备份（按会话 id 去重，可重复导入）
     *
     * 与"恢复数据"的区别：保留现有统计，把备份里没有的播放明细并进来。
     */
    @JvmStatic
    @JvmName("mergeImportDialog")
    fun mergeImportDialog() {
        com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.run({
            val file = com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.openFile("选择要合并的备份")
            if (file == null) {
                null
            } else if (!com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.confirm(
                    "合并导入",
                    "把「" + file.name + "」里的播放明细并入当前统计：\n" +
                        "· 不会删除或覆盖现有记录\n" +
                        "· 同一份备份导入多次也不会重复计数\n" +
                        "· 备份里若只有按天聚合数据（没有明细），会被拒绝\n\n继续吗？"
                )
            ) {
                null
            } else {
                StatsStore.importMerge(file)
            }
        }, { message ->
            if (message != null) toast(message, message.startsWith("已合并"))
        })
    }

    /** 弹出对话框：从备份恢复（先展示备份内容摘要，确认后才覆盖） */
    @JvmStatic
    @JvmName("restoreDataDialog")
    fun restoreDataDialog() {
        com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.run({
            val file = com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.openFile("选择备份文件")
            if (file == null) {
                null
            } else {

                // 读 manifest 摘要：让用户知道这个备份里有什么（旧版备份没有 manifest）
                val manifest = try {
                    StatsStore.inspectBackup(file)
                } catch (t: Throwable) {
                    null
                }
                val summary = if (manifest == null) {
                    "备份文件：" + file.name + "\n（旧版备份，无内容摘要）"
                } else {
                    val tracks = com.spwmods.waerwak.listenstats.stats.JsonLite.long(manifest, "tracks")
                    val days = com.spwmods.waerwak.listenstats.stats.JsonLite.long(manifest, "dailyRows")
                    val events = com.spwmods.waerwak.listenstats.stats.JsonLite.long(manifest, "events")
                    val at = com.spwmods.waerwak.listenstats.stats.JsonLite.string(manifest, "exportedAt", "—")
                    val first = com.spwmods.waerwak.listenstats.stats.JsonLite.string(manifest, "firstDay", "")
                    val last = com.spwmods.waerwak.listenstats.stats.JsonLite.string(manifest, "lastDay", "")
                    val span = if (first.isBlank() && last.isBlank()) "—" else first + " ~ " + last
                    "备份文件：" + file.name + "\n" +
                        "导出时间：" + at + "\n" +
                        "曲目字典：" + tracks + " 条\n" +
                        "日聚合行：" + days + " 行\n" +
                        "播放明细：" + events + " 条\n" +
                        "日期范围：" + span
                }
                val message = summary + "\n\n" +
                    "恢复会用备份内容替换当前统计：\n" +
                    "当前数据会改名为 .bak 保留（不删除）。\n" +
                    "缺失检测记录会被清空（恢复后需重新检测）。\n\n确认恢复吗？"
                if (com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.confirm("恢复听歌统计", message)) {
                    StatsStore.restoreFrom(file)
                } else {
                    null
                }
            }
        }, { message ->
            if (message != null) toast(message, message.startsWith("已恢复"))
        })
    }

    /** 弹出系统保存对话框，让用户自选导出位置（对话框与写盘都在后台线程） */
    @JvmStatic
    @JvmName("exportCsvDialog")
    fun exportCsvDialog() {
        val tracks = StatsStore.allTracks()
        if (tracks.isEmpty()) {
            toast("没有数据可导出", false)
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.run({
            val file = com.spwmods.waerwak.listenstats.ui.BackgroundDialogs.saveFile(
                "导出 CSV",
                "listen-stats-" + stamp + ".csv"
            )
            if (file == null) null else writeCsv(file, tracks)
        }, { message ->
            if (message != null) toast(message, message.startsWith("已导出"))
        })
    }

    private fun writeCsv(file: java.io.File, tracks: List<TrackAgg>): String {
        val sb = StringBuilder(4096)
        sb.append('\uFEFF')
        sb.append("曲目,艺术家,专辑,次数,累计分钟,跳过次数,首次播放,最近播放,路径\n")
        var i = 0
        while (i < tracks.size) {
            val t = tracks[i]
            sb.append(csv(t.title)).append(',')
            sb.append(csv(t.artist)).append(',')
            sb.append(csv(t.album)).append(',')
            sb.append(t.count).append(',')
            sb.append(t.listenedMs / 60_000L).append(',')
            sb.append(t.skipCount).append(',')
            sb.append(timeOf(t.firstPlayed)).append(',')
            sb.append(timeOf(t.lastPlayed)).append(',')
            sb.append(csv(if (t.path.isBlank()) t.id else t.path)).append('\n')
            i++
        }
        return try {
            java.nio.file.Files.write(file.toPath(), sb.toString().toByteArray(Charsets.UTF_8))
            "已导出：" + file.absolutePath
        } catch (t: Throwable) {
            "导出失败：" + t.message
        }
    }

    private fun csv(raw: String): String {
        if (raw.indexOf(',') < 0 && raw.indexOf('"') < 0 && raw.indexOf('\n') < 0) return raw
        return "\"" + raw.replace("\"", "\"\"") + "\""
    }

    private fun timeOf(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        return fmt.format(Date(timestamp))
    }

    private fun toast(text: String, ok: Boolean) {
        try {
            WorkshopApi.ui.toast(
                text,
                if (ok) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Warning
            )
        } catch (t: Throwable) {
            System.err.println("[listen-stats] $text")
        }
    }
}

// ---------------------------------------------------------------- UI



/**
 * 弹窗外点击关闭（外层）
 *
 * 注意：`Modifier.onPointerEvent` **不会**因为事件已被子节点消费就跳过回调，
 * 必须在回调里自己检查 `isConsumed`。否则弹窗内点按钮（翻页 / 步进 / 保存）
 * 会被当成「点在弹窗外面」，把弹窗直接关掉。
 */
internal fun Modifier.dismissOnOutsidePress(onDismiss: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        var consumed = false
        var i = 0
        while (i < event.changes.size) {
            if (event.changes[i].isConsumed) consumed = true
            i++
        }
        if (!consumed) onDismiss()
    }



/** 弹窗本体：把按下标记为已消费，让外层知道这次点在弹窗里 */
internal fun Modifier.consumePress(): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        var i = 0
        while (i < event.changes.size) {
            event.changes[i].consume()
            i++
        }
    }



/** 安装结果持有者：跨线程传递注册消息与异常（避免在 lambda 里捕获可变局部变量） */
internal class InstallOutcome {
    @Volatile
    var message: String? = null

    @Volatile
    var failure: Throwable? = null
}
