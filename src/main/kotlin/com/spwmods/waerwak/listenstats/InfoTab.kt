@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.spwmods.waerwak.listenstats.stats.MissingStore
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.CountRule
import com.spwmods.waerwak.listenstats.stats.StorageRule
import com.spwmods.waerwak.listenstats.ui.HostAppearance
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.MissingDetector
import com.spwmods.waerwak.listenstats.ui.MissingState
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import com.spwmods.waerwak.listenstats.ui.UiStateStore
import com.spwmods.waerwak.listenstats.ui.HostFont

@Composable
internal fun InfoTab(
    tick: Long,
    onEditThreshold: () -> Unit,
    onEditRetention: () -> Unit,
    onEditFontSize: () -> Unit
) {
    val p = LocalSaltPalette.current
    val snap = remember(tick) { HostProbe.snapshot() }
    val dir = StatsStore.dataDirOrNull()?.absolutePath ?: "-"
    val trackCount = StatsStore.snapshot(1, 0).trackCount
    val eventLines = StatsStore.replayedLineCount()
    val bridgeOn = com.spwmods.waerwak.listenstats.ui.HostMaterialState.bridgeAvailable

    Column(Modifier.fillMaxWidth()) {
        // 两列瀑布流，各列内部紧挨
        // 左列：提示 → 插件 → 采集 → 宿主
        // 右列：显示 → 数据 → 诊断
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                // ------------------------------------------------ 提示（置顶）
                SaltGroup {
                    GroupHeader("提示")
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            "以下操作需在「模组管理 → 听歌统计」中完成：",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("· 导出 CSV / 备份数据 / 恢复数据 / 清空统计", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.height(2.dp))
                        Text("· 悬浮按钮的安装与卸载", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.height(2.dp))
                        Text("· 顶栏按钮的安装与卸载", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "打开统计页的全局快捷键需在「设置 → 键盘快捷键」中绑定（默认未绑定）",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.text
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "不计数的播放、缺失曲目检测、明细保留天数、字体大小可在本页调整",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "缺失曲目不会自动检测：文件改动后在右侧「数据」组点「检测」才会重新判定",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 插件
                SaltGroup {
                    GroupHeader("插件")
                    ItemRow("名称", "听歌统计")
                    RowDivider()
                    ItemRow("版本", snap.pluginVersion)
                    RowDivider()
                    ItemRow("插件 ID", snap.pluginId)
                    RowDivider()
                    ItemRow("渠道", snap.channel)
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 采集（采集规则 + 实时状态）
                SaltGroup {
                    GroupHeader("采集")
                    AdjustableRow(
                        label = "计数门槛",
                        value = CountRule.describe(),
                        onClick = onEditThreshold
                    )
                    RowDivider()
                    ItemRow("播放状态", if (snap.isPlaying) "播放中" else "未播放")
                    RowDivider()
                    ItemRow("位置回调", snap.positionCallbacks.toString() + " 次")
                    RowDivider()
                    ItemRow("当前曲目", snap.lastTrackText)
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 宿主
                SaltGroup {
                    GroupHeader("宿主")
                    ItemRow("SPW 版本", snap.spwVersion)
                    RowDivider()
                    ItemRow("Kotlin", snap.kotlinVersion)
                    RowDivider()
                    ItemRow("Compose", snap.composeVersion)
                }
            }
            Column(Modifier.weight(1f)) {
                // ------------------------------------------------ 显示（字体 / 字号 / 宽度）
                SaltGroup {
                    GroupHeader("显示")
                    ItemRow("字体", HostFont.lastDiagnostics)
                    RowDivider()
                    AdjustableRow(
                        label = "字体大小",
                        value = if (OverlayInjection.fontExtraPercent.value <= 0) "默认"
                        else "+" + OverlayInjection.fontExtraPercent.value + "%",
                        onClick = onEditFontSize
                    )
                    RowDivider()
                    Row(
                        Modifier.fillMaxWidth().height(34.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "内容宽度上限",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            var w = 50
                            while (w <= 100) {
                                val target = w
                                SegmentButton(
                                    label = target.toString(),
                                    selected = OverlayInjection.contentPercent.value == target
                                ) {
                                    OverlayInjection.contentPercent.value = target
                                    UiStateStore.saveContentPercent(target)
                                }
                                w += 10
                            }
                        }
                    }
                    RowDivider()
                    // 缺失曲目默认显示；关掉只是从榜单里隐藏，不影响统计与顶部 KPI
                    val showMissingState = remember { mutableStateOf(!MissingStore.hidden) }
                    ToggleRow(
                        label = "显示缺失曲目",
                        checked = showMissingState.value,
                        onToggle = { show ->
                            showMissingState.value = show
                            MissingStore.hidden = !show
                            UiStateStore.saveShowMissing(show)
                            // 榜单/概览按这个版本号重建
                            MissingState.version.value = MissingState.version.value + 1
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 数据（统计量 + 保留策略 + 位置）
                SaltGroup {
                    GroupHeader("数据")
                    ItemRow("曲目数", trackCount.toString() + " 首")
                    RowDivider()
                    // 缺失曲目：手动检测 + 结果持久化（missing.txt），不开面板不会跑
                    val missingTick = MissingState.version.value
                    val missingCount = remember(missingTick) { MissingStore.count() }
                    val missingAt = remember(missingTick) { MissingStore.checkedAt() }
                    val scanning = MissingState.scanning.value
                    Row(
                        Modifier.fillMaxWidth().height(34.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "缺失曲目",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (missingCount > 0) missingCount.toString() + " 首"
                            else if (missingAt > 0L) "无" else "未检测",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            onClick = { if (!scanning) MissingDetector.detect() },
                            shape = SaltDesign.shapeSmall,
                            color = p.highlight.copy(alpha = if (scanning) 0.08f else 0.16f)
                        ) {
                            Box(
                                Modifier.height(24.dp).padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (scanning) "检测中…" else "检测",
                                    fontSize = SaltDesign.fontSizeSub,
                                    color = p.highlight
                                )
                            }
                        }
                    }
                    RowDivider()
                    ItemRow("上次检测", formatDateTime(missingAt))
                    RowDivider()
                    ItemRow(
                        "检测结果",
                        if (MissingState.lastMessage.value.isNotBlank()) MissingState.lastMessage.value else "-"
                    )
                    RowDivider()
                    ItemRow("事件行数", eventLines.toString())
                    RowDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("存储占用", fontSize = SaltDesign.fontSizeMain, color = p.text)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            StatsStore.storageSummary(),
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                    }
                    RowDivider()
                    AdjustableRow(
                        label = "明细保留",
                        value = StorageRule.describe(),
                        onClick = onEditRetention
                    )
                    RowDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("数据目录", fontSize = SaltDesign.fontSizeMain, color = p.text)
                        Spacer(Modifier.height(4.dp))
                        Text(dir, fontSize = SaltDesign.fontSizeSub, color = p.subText)
                    }
                }
                Spacer(Modifier.height(12.dp))
                // ------------------------------------------------ 诊断（运行状态）
                SaltGroup {
                    GroupHeader("诊断")
                    ItemRow("窗口样式", HostAppearance.lastStyleName)
                    RowDivider()
                    ItemRow(
                        "背景",
                        if (bridgeOn) {
                            if (HostAppearance.isMaterialStyle()) "跟随窗口材质（透明）" else "经典纯色"
                        } else {
                            "降级：桥接层未生效，使用纯色背景"
                        }
                    )
                    RowDivider()
                    ItemRow(
                        "材质桥接",
                        if (bridgeOn) {
                            "已生效 · 隐藏=" + com.spwmods.waerwak.listenstats.ui.HostMaterialState.hidden
                        } else {
                            "未生效（replaceComposable 未进入组合）"
                        }
                    )
                    RowDivider()
                    ItemRow("封面缓存", com.spwmods.waerwak.listenstats.ui.CoverCache.statsText())
                    RowDivider()
                    ItemRow("标题栏按钮", com.spwmods.waerwak.listenstats.ui.TitlebarButton.statusText())
                    RowDivider()
                    val hooks = hookHealth()
                    Row(
                        Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "注入状态",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            hooks.second,
                            fontSize = SaltDesign.fontSizeSub,
                            color = if (hooks.first) p.subText else p.error
                        )
                    }
                    RowDivider()
                    Row(
                        Modifier.fillMaxWidth().height(34.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "诊断信息",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            onClick = {
                                val ok = copyToClipboard(
                                    buildDiagnostics(snap, dir, trackCount, eventLines, bridgeOn)
                                )
                                toast(if (ok) "诊断信息已复制到剪贴板" else "复制失败", ok)
                            },
                            shape = SaltDesign.shapeSmall,
                            color = p.highlight.copy(alpha = 0.16f)
                        ) {
                            Box(
                                Modifier.height(24.dp).padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("复制", fontSize = SaltDesign.fontSizeSub, color = p.highlight)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // ------------------------------------------------ 权限（全宽）
        SaltGroup {
            GroupHeader("权限")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionChip("读取音乐库", "获取曲目信息", snap.libraryRead, 0, Modifier.weight(1f))
                PermissionChip("全局快捷键", "打开统计面板", snap.keyBindings, 1, Modifier.weight(1f))
                PermissionChip("界面注入", "显示统计浮层", snap.classTransform, 2, Modifier.weight(1f))
            }
            RowDivider()
            Text(
                "亮色 = 已授权，暗色 = 未授权",
                fontSize = SaltDesign.fontSizeSub,
                color = p.subText,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
    }
}


/** 「标签 + 当前值 + 调整按钮」一行（计数门槛 / 明细保留 / 字体大小共用） */
@Composable
internal fun AdjustableRow(label: String, value: String, onClick: () -> Unit) {
    val p = LocalSaltPalette.current
    Row(
        Modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = SaltDesign.fontSizeMain,
            color = p.text,
            modifier = Modifier.weight(1f)
        )
        Text(value, fontSize = SaltDesign.fontSizeSub, color = p.subText)
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onClick,
            shape = SaltDesign.shapeSmall,
            color = p.highlight.copy(alpha = 0.16f)
        ) {
            Box(
                Modifier.height(24.dp).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("调整", fontSize = SaltDesign.fontSizeSub, color = p.highlight)
            }
        }
    }
}


/** 自绘胶囊开关（宿主的 Switch 依赖被 R8 裁掉，这里用圆角轨道 + 圆点） */
@Composable
internal fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val p = LocalSaltPalette.current
    Surface(onClick = { onToggle(!checked) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeMain,
                color = p.text,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier.size(width = 34.dp, height = 20.dp).drawBehind {
                    val h = size.height
                    drawRoundRect(
                        color = if (checked) p.highlight else p.subText.copy(alpha = 0.30f),
                        cornerRadius = CornerRadius(h / 2f, h / 2f),
                        size = size
                    )
                    val r = h / 2f - 2.dp.toPx()
                    val cx = if (checked) size.width - h / 2f else h / 2f
                    drawCircle(color = Color.White, radius = r, center = Offset(cx, h / 2f))
                }
            ) { }
        }
    }
}


/** 权限卡片：与顶部 Tab 同一种卡片样式，已授权 = 强调色激活态 */
@Composable
internal fun PermissionChip(
    label: String,
    purpose: String,
    granted: Boolean,
    iconKind: Int,
    modifier: Modifier = Modifier
) {
    val p = LocalSaltPalette.current
    val tint = if (granted) p.highlight else p.subText.copy(alpha = 0.65f)
    Surface(
        shape = SaltDesign.shapeMedium,
        color = if (granted) p.highlight.copy(alpha = 0.10f) else Color.Transparent,
        border = BorderStroke(
            1.dp,
            SolidColor(if (granted) p.highlight else p.subText.copy(alpha = 0.22f))
        ),
        modifier = modifier
    ) {
        Row(
            Modifier.height(50.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PermIcon(iconKind, tint)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, fontSize = SaltDesign.fontSizeMain, color = tint)
                Spacer(Modifier.height(2.dp))
                Text(
                    purpose,
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.subText.copy(alpha = if (granted) 1f else 0.6f)
                )
            }
        }
    }
}


/** 权限图标：0 音符 / 1 键盘 / 2 图层 */
@Composable
internal fun PermIcon(kind: Int, color: Color) {
    Box(
        Modifier.size(20.dp).drawBehind {
            val s = size.minDimension
            val stroke = s * 0.1f
            when (kind) {
                0 -> {
                    // 音符
                    drawCircle(color, radius = s * 0.13f, center = Offset(s * 0.3f, s * 0.78f))
                    drawRect(color, Offset(s * 0.4f, s * 0.12f), Size(stroke * 1.4f, s * 0.68f))
                    drawRect(color, Offset(s * 0.4f, s * 0.12f), Size(s * 0.42f, stroke * 1.6f))
                }
                1 -> {
                    // 键盘：外框 + 三个小键
                    drawRoundRect(
                        color,
                        topLeft = Offset(0f, s * 0.26f),
                        size = Size(s, s * 0.48f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.1f),
                        style = Stroke(width = stroke)
                    )
                    drawRect(color, Offset(s * 0.16f, s * 0.46f), Size(s * 0.14f, stroke * 1.4f))
                    drawRect(color, Offset(s * 0.42f, s * 0.46f), Size(s * 0.14f, stroke * 1.4f))
                    drawRect(color, Offset(s * 0.68f, s * 0.46f), Size(s * 0.14f, stroke * 1.4f))
                }
                else -> {
                    // 图层：两个错位矩形
                    drawRoundRect(
                        color,
                        topLeft = Offset(s * 0.06f, s * 0.1f),
                        size = Size(s * 0.7f, s * 0.5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f),
                        style = Stroke(width = stroke)
                    )
                    drawRoundRect(
                        color,
                        topLeft = Offset(s * 0.26f, s * 0.4f),
                        size = Size(s * 0.7f, s * 0.5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f),
                        style = Stroke(width = stroke)
                    )
                }
            }
        }
    )
}


/** 写系统剪贴板（复制诊断信息用） */
internal fun copyToClipboard(text: String): Boolean = try {
    val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
    cb.setContents(java.awt.datatransfer.StringSelection(text), null)
    true
} catch (t: Throwable) {
    false
}


/** 面板内轻提示（不打断操作） */
internal fun toast(text: String, ok: Boolean) {
    try {
        com.xuncorp.spw.workshop.api.WorkshopApi.ui.toast(
            text,
            if (ok) com.xuncorp.spw.workshop.api.WorkshopApi.Ui.ToastType.Success
            else com.xuncorp.spw.workshop.api.WorkshopApi.Ui.ToastType.Warning
        )
    } catch (t: Throwable) {
        System.err.println("[listen-stats] " + text)
    }
}


/**
 * hook 健康自检
 *
 * @return first = 是否全部关键 hook 生效；second = 简短的 ✓/✗ 串
 *
 * 面板注入失败是致命的（打不开统计页）；材质桥接失败只影响背景（降级为纯色），
 * 但只有在宿主确实使用材质样式时才算异常。
 */
internal fun hookHealth(): Pair<Boolean, String> {
    val panel = OverlayInjection.installed()
    val bridge = com.spwmods.waerwak.listenstats.ui.HostMaterialState.bridgeAvailable
    val nav = com.spwmods.waerwak.listenstats.ui.NavigationHook.installed
    val titlebar = com.spwmods.waerwak.listenstats.ui.TitlebarButton.installed
    val materialStyle = com.spwmods.waerwak.listenstats.ui.HostAppearance.isMaterialStyle()
    val sb = StringBuilder(64)
    sb.append("面板").append(if (panel) "✓" else "✗")
    sb.append(" · 桥接").append(if (bridge) "✓" else "✗")
    sb.append(" · 导航").append(if (nav) "✓" else "✗")
    sb.append(" · 顶栏").append(if (titlebar) "✓" else "✗")
    val healthy = panel && (!materialStyle || bridge)
    return Pair(healthy, sb.toString())
}


/** 拼一段可直接贴给开发者排查的诊断文本 */
internal fun buildDiagnostics(
    snap: ProbeSnapshot,
    dir: String,
    trackCount: Int,
    eventLines: Int,
    bridgeOn: Boolean
): String {
    val sb = StringBuilder(768)
    sb.append("== 听歌统计诊断 ==").append('\n')
    sb.append("插件 ").append(snap.pluginVersion).append(" / SPW ").append(snap.spwVersion)
        .append(" (").append(snap.channel).append(')').append('\n')
    sb.append("Kotlin ").append(snap.kotlinVersion).append(" / Compose ").append(snap.composeVersion).append('\n')
    sb.append("数据目录: ").append(dir).append('\n')
    sb.append("曲目 ").append(trackCount).append(" / 聚合行 ").append(eventLines).append('\n')
    sb.append("存储: ").append(StatsStore.storageSummary()).append('\n')
    sb.append("计数门槛: ").append(CountRule.describe()).append('\n')
    sb.append("明细保留: ").append(StorageRule.describe()).append('\n')
    sb.append("窗口样式: ").append(HostAppearance.lastStyleName).append('\n')
    sb.append("背景: ")
    if (bridgeOn) {
        sb.append(if (HostAppearance.isMaterialStyle()) "跟随窗口材质（透明）" else "经典纯色")
    } else {
        sb.append("降级（桥接层未生效）")
    }
    sb.append('\n')
    sb.append("材质桥接: ").append(if (bridgeOn) "已生效" else "未生效")
        .append(" / 隐藏=").append(com.spwmods.waerwak.listenstats.ui.HostMaterialState.hidden).append('\n')
    sb.append("封面: ").append(com.spwmods.waerwak.listenstats.ui.CoverCache.statsText()).append('\n')
    sb.append("标题栏按钮: ").append(com.spwmods.waerwak.listenstats.ui.TitlebarButton.statusText()).append('\n')
    sb.append("播放: ").append(if (snap.isPlaying) "播放中" else "未播放")
        .append(" / 位置回调 ").append(snap.positionCallbacks).append(" 次").append('\n')
    sb.append("当前曲目: ").append(snap.lastTrackText).append('\n')
    return sb.toString()
}
