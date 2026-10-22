@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.MissingState
import com.spwmods.waerwak.listenstats.ui.SaltDesign

@Composable
internal fun OverviewTab(tick: Long) {
    // 检测结果变了要重算：徽章要更新，隐藏开关下列表内容也会变
    val missingTick = MissingState.version.value
    val snapshot = remember(tick, missingTick) { StatsStore.snapshot(10, 10) }
    val streak = remember(tick) { StatsStore.currentStreak() }
    val longest = remember(tick) { StatsStore.longestStreak() }
    // 累计收听的三态格式：0=时分 1=天时分 2=天数小数（点击循环）
    var durationMode by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        SaltGroup {
            GroupHeader("总览")
            Row(Modifier.fillMaxWidth()) {
                // 累计收听：点击循环切换三种格式
                KpiCellParts(
                    label = "累计收听",
                    parts = durationParts(snapshot.totalMs, durationMode),
                    modifier = Modifier.weight(1f),
                    onClick = { durationMode = (durationMode + 1) % 3 }
                )
                KpiNumberCell("总播放次数", snapshot.totalCount, "", Modifier.weight(1f))
                KpiNumberCell("曲目数", snapshot.trackCount, "", Modifier.weight(1f))
                KpiNumberCell("连续收听", streak, "天", Modifier.weight(1f))
                KpiNumberCell("最长连续", longest, "天", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SaltGroup {
                    GroupHeader("最近播放")
                    if (snapshot.recentTracks.isEmpty()) {
                        EmptyHint("暂无最近播放")
                    }
                    var i = 0
                    while (i < snapshot.recentTracks.size) {
                        if (i > 0) RowDivider()
                        val t = snapshot.recentTracks[i]
                        CompactListRow(
                            coverId = t.id,
                            title = t.title.ifBlank { "未知曲目" },
                            summary = t.artist,
                            value = formatLastPlayed(t.lastPlayed),
                            badge = missingBadgeOf(t.key)
                        )
                        i++
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                SaltGroup {
                    GroupHeader("最常听 Top 10")
                    if (snapshot.topTracks.isEmpty()) {
                        EmptyHint("还没有计数记录")
                    }
                    var i = 0
                    while (i < snapshot.topTracks.size) {
                        if (i > 0) RowDivider()
                        val t = snapshot.topTracks[i]
                        CompactListRow(
                            coverId = t.id,
                            title = t.title.ifBlank { "未知曲目" },
                            summary = t.artist,
                            value = t.count.toString() + " 次",
                            badge = missingBadgeOf(t.key)
                        )
                        i++
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // 听歌小结（三项，对齐 Jethuit）
        val peakDate = remember(tick) { StatsStore.peakDay() }
        val peakMs = remember(tick) { StatsStore.peakMillis() }
        val avgMs = remember(tick) { StatsStore.dailyAverageMillis() }
        val favHour = remember(tick) { StatsStore.favoriteHour() }
        SaltGroup {
            GroupHeader("听歌小结")
            Row(Modifier.fillMaxWidth()) {
                SummaryStat(
                    label = if (peakDate.isNotBlank()) "听得最多 · " + peakDate else "听得最多",
                    parts = if (peakMs > 0L) durationParts(peakMs, 0) else listOf("—" to ""),
                    modifier = Modifier.weight(1f)
                )
                SummaryStat(
                    label = "每日平均收听",
                    parts = if (avgMs > 0L) durationParts(avgMs, 0) else listOf("—" to ""),
                    modifier = Modifier.weight(1f)
                )
                SummaryStat(
                    label = "最常听歌时段",
                    // 数字 26sp，尾巴 :00 用 2/3 字号（与其它汉字单位一致）
                    parts = if (favHour >= 0) {
                        listOf(
                            pad2(favHour) to ":00 — ",
                            pad2((favHour + 1) % 24) to ":00"
                        )
                    } else {
                        listOf("—" to "")
                    },
                    modifier = Modifier.weight(1f)
                )
                // 跳过率：跳过次数 /（计次 + 跳过）。不计入「没听完但也没跳过」的那部分
                val skipTotal = snapshot.skipCount
                val playsTotal = snapshot.totalCount + skipTotal
                SummaryStat(
                    label = if (skipTotal > 0) "跳过率 · 共 " + skipTotal + " 次" else "跳过率",
                    parts = if (playsTotal > 0) {
                        listOf((skipTotal * 100 / playsTotal).toString() to "%")
                    } else {
                        listOf("—" to "")
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}


/** 补零到两位 */
internal fun pad2(value: Int): String {
    // 手动补零：不经 String.format，避免宿主裁剪相关重载
    return if (value < 10) "0" + value.toString() else value.toString()
}


/**
 * 把时长拆成「数值 + 单位」片段，供数字/单位分级字号渲染
 *
 * mode 0 = 时/分（不足一小时只显示分）
 * mode 1 = 天/时/分
 * mode 2 = 天数小数（保留两位）
 *
 * 分钟四舍五入（满 30 秒进位）；显示层处理，不影响已记录数据。
 */
internal fun durationParts(ms: Long, mode: Int): List<Pair<String, String>> {
    if (ms <= 0L) return listOf("0" to "分")
    val totalMinutes = ms / 60_000L + if (ms % 60_000L >= 30_000L) 1L else 0L
    if (mode == 2) {
        // 天数保留两位小数（用整数运算避免精度误差）
        val hundredths = (ms * 100L + 43_200_000L) / 86_400_000L
        val whole = hundredths / 100L
        val frac = hundredths % 100L
        val fracText = if (frac < 10L) "0" + frac.toString() else frac.toString()
        return listOf((whole.toString() + "." + fracText) to "天")
    }
    val days = totalMinutes / 1440L
    val hours = (totalMinutes % 1440L) / 60L
    val minutes = totalMinutes % 60L
    if (mode == 1) {
        // 「天」总是显示（不足一天也显示 0 天），与「时」保持一致的三段式
        val list = ArrayList<Pair<String, String>>(3)
        list.add(days.toString() to "天")
        list.add(hours.toString() to "时")
        if (minutes > 0L) list.add((if (minutes < 10) "0" + minutes else minutes.toString()) to "分")
        return list
    }
    if (hours > 0L) {
        val list = ArrayList<Pair<String, String>>(2)
        list.add(hours.toString() to "时")
        if (minutes > 0L) list.add((if (minutes < 10) "0" + minutes else minutes.toString()) to "分")
        return list
    }
    return listOf(minutes.toString() to "分")
}


/** 数值 + 单位分字号（单位取数字的一半），可选点击（仅内容区可点，不含标题） */
@Composable
internal fun KpiCellParts(
    label: String,
    parts: List<Pair<String, String>>,
    modifier: Modifier,
    onClick: (() -> Unit)? = null
) {
    val p = LocalSaltPalette.current
    Column(modifier) {
        Text(label, fontSize = SaltDesign.fontSizeSub, color = p.labelAccent)
        Spacer(Modifier.height(6.dp))
        val row: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.Bottom) {
                var i = 0
                while (i < parts.size) {
                    val part = parts[i]
                    Text(
                        part.first,
                        fontSize = SaltDesign.fontSizeKpi,
                        fontWeight = SaltDesign.weightKpi,
                        color = p.highlight
                    )
                    if (part.second.isNotEmpty()) {
                        Spacer(Modifier.width(2.dp))
                        Text(
                            part.second,
                            fontSize = SaltDesign.fontSizeKpiUnit,
                            color = p.highlight,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    if (i < parts.size - 1) Spacer(Modifier.width(2.dp))
                    i++
                }
            }
        }
        if (onClick != null) {
            // 只让数值行可点；局部关闭 48dp 最小交互区，避免把宽度挤开
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp
            ) {
                Surface(
                    onClick = onClick,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = SaltDesign.shapeSmall
                ) {
                    row()
                }
            }
        } else {
            row()
        }
    }
}


/** 听歌小结单项（标签在上、数值在下；汉字单位取数字的 2/3） */
@Composable
internal fun SummaryStat(
    label: String,
    parts: List<Pair<String, String>>,
    modifier: Modifier
) {
    val p = LocalSaltPalette.current
    Column(modifier) {
        Text(label, fontSize = SaltDesign.fontSizeSub, color = p.labelAccent)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            var i = 0
            while (i < parts.size) {
                val part = parts[i]
                Text(
                    part.first,
                    fontSize = SaltDesign.fontSizeKpi,
                    fontWeight = SaltDesign.weightKpi,
                    color = p.highlight
                )
                if (part.second.isNotEmpty()) {
                    Spacer(Modifier.width(1.dp))
                    Text(
                        part.second,
                        fontSize = SaltDesign.fontSizeKpiUnit,
                        color = p.highlight,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                i++
            }
        }
    }
}


@Composable
internal fun KpiCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.labelAccent)
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            fontSize = SaltDesign.fontSizeKpi,
            fontWeight = SaltDesign.weightKpi,
            color = LocalSaltPalette.current.highlight
        )
    }
}


/**
 * 数字变化时的「上滑切换」：旧值向上淡出，新值从下方淡入
 *
 * 纯整数插值（49→50）几乎没有中间帧，看上去只会"突然变"，
 * 所以改成位移 + 透明度过渡，变化过程肉眼可见。
 */
@Composable
internal fun AnimatedNumberText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: androidx.compose.ui.text.font.FontWeight?,
    color: Color
) {
    var current by remember { mutableStateOf(text) }
    var previous by remember { mutableStateOf(text) }
    val progress = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(text) {
        if (text != current) {
            previous = current
            current = text
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 340,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
        }
    }
    val offsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    val p = progress.value
    Box {
        if (p < 1f) {
            Text(
                previous,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = color,
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - p
                    translationY = -offsetPx * p
                }
            )
        }
        Text(
            current,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            modifier = Modifier.graphicsLayer {
                alpha = p
                translationY = offsetPx * (1f - p)
            }
        )
    }
}


/** 整数 KPI（数值变化时上滑切换） */
@Composable
internal fun KpiNumberCell(label: String, value: Int, suffix: String, modifier: Modifier) {
    val p = LocalSaltPalette.current
    Column(modifier) {
        Text(label, fontSize = SaltDesign.fontSizeSub, color = p.labelAccent)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumberText(
                text = value.toString(),
                fontSize = SaltDesign.fontSizeKpi,
                fontWeight = SaltDesign.weightKpi,
                color = p.highlight
            )
            if (suffix.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    suffix,
                    fontSize = SaltDesign.fontSizeKpiUnit,
                    color = p.highlight,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}

