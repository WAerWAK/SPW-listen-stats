@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spwmods.waerwak.listenstats.stats.DayAgg
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.RangeBuckets
import com.spwmods.waerwak.listenstats.stats.RangePreset
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import java.time.LocalDate

/** 时长分布：范围预设 + 自动粒度 + 柱/折线/曲线 + Y 轴刻度 */
@Composable
internal fun DurationChart(
    width: androidx.compose.ui.unit.Dp,
    tick: Int,
    preset: RangePreset,
    customFrom: LocalDate,
    customTo: LocalDate,
    byTime: Boolean,
    chartType: Int,
    daily: Map<String, DayAgg>,
    onPreset: (RangePreset) -> Unit,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onByTime: (Boolean) -> Unit,
    onChartType: (Int) -> Unit
) {
    val p = LocalSaltPalette.current
    val dLocal = LocalDensity.current
    val today = LocalDate.now()
    val range = RangeBuckets.rangeOf(preset, today, customFrom, customTo)
    val from = range.first
    val to = range.second
    val days = RangeBuckets.dayCount(from, to)

    val hourly = if (days <= 1) {
        remember(from, tick) { StatsStore.hourProfile(from.toString(), to.toString()) }
    } else {
        null
    }
    val buckets = remember(from, to, tick, byTime) {
        RangeBuckets.build(from, to, hourly, daily)
    }

    val values = FloatArray(buckets.size)
    var rawMax = 0f
    var total = 0.0
    var i = 0
    while (i < buckets.size) {
        val v = if (byTime) buckets[i].ms.toFloat() else buckets[i].count.toFloat()
        values[i] = v
        total += v.toDouble()
        if (v > rawMax) rawMax = v
        i++
    }
    val axisMax = niceAxisMax(rawMax)
    val average = if (buckets.size > 0) (total / buckets.size).toFloat() else 0f

    // 任何改变图表内容的操作（范围 / 次数-时长 / 柱线曲线）都重放一次「从下往上升起」：
    // 先 snapTo(0) 让旧图形立即消失，再生长到 1
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(from, to, byTime, chartType) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 380)
        )
    }

    var hovered by remember(from, to) { mutableStateOf(-1) }
    var hoverLeftPx by remember { mutableStateOf(0f) }
    var hoverBarW by remember { mutableStateOf(0f) }
    var plotPx by remember { mutableStateOf(0f) }
    // 鼠标在图表区内的位置（用于跟随式浮窗）
    var mouseX by remember { mutableStateOf(-1f) }
    var mouseY by remember { mutableStateOf(0f) }

    val axisTitle = if (byTime) axisUnitMs(axisMax) else "次"

    Column(Modifier.width(width)) {
        // 预设 + 图表类型
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                var s = 0
                while (s < 6) {
                    val option = presetOptions[s]
                    val active = preset == option
                    Surface(
                        onClick = { onPreset(option) },
                        shape = SaltDesign.shapeMedium,
                        color = if (active) p.subBackground else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            SolidColor(if (active) p.highlight else p.subText.copy(alpha = 0.18f))
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                option.label,
                                fontSize = SaltDesign.fontSizeSub,
                                color = if (active) p.highlight else p.subText
                            )
                        }
                    }
                    s++
                }
            }
            Spacer(Modifier.width(10.dp))
            // 柱 / 折线 / 曲线
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                var ct = 0
                while (ct < 3) {
                    val active = chartType == ct
                    val kind = ct
                    Surface(
                        onClick = { onChartType(kind) },
                        shape = SaltDesign.shapeSmall,
                        color = if (active) p.subBackground else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            SolidColor(if (active) p.highlight else p.subText.copy(alpha = 0.18f))
                        )
                    ) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            ChartTypeGlyph(kind, if (active) p.highlight else p.subText)
                        }
                    }
                    ct++
                }
            }
        }

        if (preset == RangePreset.Custom) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onPickFrom,
                    shape = SaltDesign.shapeSmall,
                    color = p.subBackground,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("开始 " + customFrom.toString(), fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.width(6.dp))
                        CalendarGlyph(p.highlight.copy(alpha = 0.9f))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = onPickTo,
                    shape = SaltDesign.shapeSmall,
                    color = p.subBackground,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("结束 " + customTo.toString(), fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.width(6.dp))
                        CalendarGlyph(p.highlight.copy(alpha = 0.9f))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 说明行：普通描述 + 强调色的平均值（两者分开以便分别着色）
            Text(
                RangeBuckets.describe(from, to) + " · " + RangeBuckets.granularityLabel(from, to),
                fontSize = SaltDesign.fontSizeSub,
                color = p.subText
            )
            if (average > 0f && buckets.size > 1) {
                val unit = if (!byTime) " 次" else if (axisMax >= 3_600_000f) " 小时" else " 分钟"
                Text(
                    " · 平均 " + axisTickLabel(average, byTime, axisMax, axisMax / 4f) + unit,
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.highlight
                )
            }
            Spacer(Modifier.weight(1f))
            Row {
                UnderlineTab("次数", !byTime, Modifier.width(54.dp), height = 28.dp) { onByTime(false) }
                UnderlineTab("时长", byTime, Modifier.width(54.dp), height = 28.dp) { onByTime(true) }
            }
        }

        Spacer(Modifier.height(10.dp))
        // 轴单位标题单独一行，不占刻度区高度（之前放在刻度列内会把刻度整体推偏 22dp）
        Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                axisTitle,
                fontSize = 10.sp,
                color = p.subText,
                modifier = Modifier.width(40.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            // Y 轴刻度：与图表同高（170dp），文字垂直中心对齐网格线
            val labelLineH = 13.dp
            Box(Modifier.width(40.dp).height(170.dp)) {
                var g = 4
                while (g >= 0) {
                    val tickValue = axisMax * g / 4f
                    // g=4 → y=0（顶），g=0 → y=170dp（底）
                    val centerY = 170.dp * (4 - g) / 4f
                    Text(
                        axisTickLabel(tickValue, byTime, axisMax, axisMax / 4f),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = p.subText,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = centerY - labelLineH / 2)
                    )
                    g--
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.fillMaxWidth().height(170.dp)
                        .onGloballyPositioned { plotPx = it.size.width.toFloat() }
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val chg = event.changes.firstOrNull()
                            if (chg != null) {
                                mouseX = chg.position.x
                                mouseY = chg.position.y
                            }
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            mouseX = -1f
                            hovered = -1
                        }
                ) {
                    // 网格线
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            val stepY = size.height / 4f
                            var g = 0
                            while (g <= 4) {
                                val y = size.height - stepY * g
                                drawLine(
                                    color = p.subText.copy(alpha = if (g == 0) 0.35f else 0.14f),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1f
                                )
                                g++
                            }
                        }
                    )
                    if (chartType == 0) {
                        Row(
                            Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            var b = 0
                            while (b < buckets.size) {
                                val bucket = buckets[b]
                                val value = values[b]
                                val index = b
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(170.dp)
                                        .onGloballyPositioned { coords ->
                                            if (hovered == index) {
                                                val pos = coords.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                                                hoverLeftPx = pos.x
                                                hoverBarW = coords.size.width.toFloat()
                                            }
                                        }
                                        .onPointerEvent(PointerEventType.Enter) {
                                            hovered = index
                                            hoverLeftPx = -1f
                                        }
                                        .onPointerEvent(PointerEventType.Exit) {
                                            if (hovered == index) hovered = -1
                                        }
                                        .drawBehind {
                                            val ratio = if (axisMax > 0f) value / axisMax else 0f
                                            val h = size.height * ratio * progress.value
                                            if (h <= 0f) return@drawBehind
                                            val barW = size.width * 0.66f
                                            val left = (size.width - barW) / 2f
                                            val active = hovered == index
                                            drawRoundRect(
                                                color = if (active) p.highlight else p.highlight.copy(alpha = 0.6f),
                                                topLeft = Offset(left, size.height - h),
                                                size = Size(barW, h),
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * density, 2.5f * density)
                                            )
                                        }
                                )
                                b++
                            }
                        }
                    } else {
                        // 折线 / 曲线
                        Box(
                            Modifier.fillMaxSize().drawBehind {
                                if (buckets.isEmpty()) return@drawBehind
                                val n = buckets.size
                                val stepX = if (n > 1) size.width / (n - 1).toFloat() else size.width
                                val pts = ArrayList<Offset>(n)
                                var b2 = 0
                                while (b2 < n) {
                                    val ratio = if (axisMax > 0f) values[b2] / axisMax else 0f
                                    val y = size.height - size.height * ratio * progress.value
                                    pts.add(Offset(if (n > 1) b2 * stepX else size.width / 2f, y))
                                    b2++
                                }
                                val path = if (chartType == 1) {
                                    val pl = androidx.compose.ui.graphics.Path()
                                    pl.moveTo(pts[0].x, pts[0].y)
                                    var q = 1
                                    while (q < pts.size) {
                                        pl.lineTo(pts[q].x, pts[q].y)
                                        q++
                                    }
                                    pl
                                } else {
                                    monotonePath(pts)
                                }
                                // 面积
                                val fill = androidx.compose.ui.graphics.Path()
                                fill.addPath(path)
                                fill.lineTo(pts[pts.size - 1].x, size.height)
                                fill.lineTo(pts[0].x, size.height)
                                fill.close()
                                drawPath(fill, p.highlight.copy(alpha = 0.13f))
                                drawPath(
                                    path,
                                    p.highlight,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * density)
                                )
                                // 平均线统一在叠加层画（虚线 + 标签），这里不再重复绘制
                            }
                        )
                        // 折线图的悬停：按最近点
                        Row(Modifier.fillMaxSize()) {
                            var b3 = 0
                            while (b3 < buckets.size) {
                                val index = b3
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(170.dp)
                                        .onGloballyPositioned { coords ->
                                            if (hovered == index) {
                                                val pos = coords.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                                                hoverLeftPx = pos.x
                                                hoverBarW = coords.size.width.toFloat()
                                            }
                                        }
                                        .onPointerEvent(PointerEventType.Enter) {
                                            hovered = index
                                            hoverLeftPx = -1f
                                        }
                                        .onPointerEvent(PointerEventType.Exit) {
                                            if (hovered == index) hovered = -1
                                        }
                                )
                                b3++
                            }
                        }
                    }
                    // 叠加层：平均虚线 + 十字光标（在柱/线之上、浮窗之下）
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            if (buckets.isEmpty()) return@drawBehind
                            // 平均虚线
                            if (average > 0f && buckets.size > 1 && axisMax > 0f) {
                                val avgY = size.height - size.height * (average / axisMax).coerceIn(0f, 1f)
                                drawLine(
                                    color = p.subText.copy(alpha = 0.6f),
                                    start = Offset(0f, avgY),
                                    end = Offset(size.width, avgY),
                                    strokeWidth = 1.2f,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(6f, 5f)
                                    )
                                )
                                // 平均线只画虚线；数值改在图表上方说明行里显示（避免在图表内压住图形）
                            }
                            // 十字光标：竖向虚线 + 实心数据点
                            if (hovered >= 0 && hovered < buckets.size) {
                                val n = buckets.size
                                val cx = if (chartType == 0 || n == 1) {
                                    (hovered + 0.5f) * size.width / n
                                } else {
                                    hovered * size.width / (n - 1).toFloat()
                                }
                                drawLine(
                                    color = p.subText.copy(alpha = 0.45f),
                                    start = Offset(cx, 0f),
                                    end = Offset(cx, size.height),
                                    strokeWidth = 1f,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(3f, 4f)
                                    )
                                )
                                val ratio = if (axisMax > 0f) values[hovered] / axisMax else 0f
                                val cy = size.height - size.height * ratio.coerceIn(0f, 1f)
                                drawCircle(
                                    color = p.highlight,
                                    radius = 4f,
                                    center = Offset(cx, cy)
                                )
                            }
                        }
                    )
                    // 悬浮卡片：跟随鼠标（绝对定位，不参与布局）
                    if (hovered >= 0 && hovered < buckets.size && mouseX >= 0f) {
                        val bucket = buckets[hovered]
                        val cardW = 176.dp
                        val cardWPx = with(dLocal) { cardW.toPx() }
                        var leftPx = mouseX + 16f
                        if (plotPx > 0f && leftPx + cardWPx > plotPx) {
                            leftPx = mouseX - cardWPx - 16f
                        }
                        if (leftPx < 0f) leftPx = 0f
                        var topPx = mouseY - with(dLocal) { 84.dp.toPx() }
                        if (topPx < 0f) topPx = mouseY + 16f
                        Surface(
                            shape = SaltDesign.shapeMedium,
                            color = p.dialogSurface,
                            shadowElevation = 14.dp,
                            border = BorderStroke(1.dp, SolidColor(p.subText.copy(alpha = 0.18f))),
                            modifier = Modifier
                                .offset(
                                    x = with(dLocal) { leftPx.toDp() },
                                    y = with(dLocal) { topPx.toDp() }
                                )
                                .width(cardW)
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(bucket.title, fontSize = SaltDesign.fontSizeSub, color = p.text)
                                Spacer(Modifier.height(4.dp))
                                Row {
                                    Text("次数", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                                    Spacer(Modifier.weight(1f))
                                    Text(bucket.count.toString() + " 次", fontSize = SaltDesign.fontSizeSub, color = p.text)
                                }
                                Row {
                                    Text("时长", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                                    Spacer(Modifier.weight(1f))
                                    Text(formatMs(bucket.ms), fontSize = SaltDesign.fontSizeSub, color = p.highlight)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // X 轴标签
                val step = RangeBuckets.axisLabelStep(buckets.size)
                Row(Modifier.fillMaxWidth()) {
                    var b4 = 0
                    while (b4 < buckets.size) {
                        val show = b4 % step == 0 || b4 == buckets.size - 1
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (show) {
                                Text(buckets[b4].label, fontSize = 10.sp, color = p.subText, maxLines = 1)
                            }
                        }
                        b4++
                    }
                }
            }
        }

        // 悬浮卡片在图表区内部绘制（见上方 Box），这里保持空
    }
}



internal val presetOptions = arrayOf(
    RangePreset.Today,
    RangePreset.Last7,
    RangePreset.Last30,
    RangePreset.ThisMonth,
    RangePreset.ThisYear,
    RangePreset.Custom
)



/** 柱 / 折线 / 曲线 的小图标 */
@Composable
internal fun ChartTypeGlyph(kind: Int, color: Color) {
    Box(
        Modifier.size(16.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.11f
            when (kind) {
                0 -> {
                    val barW = s * 0.16f
                    drawRect(color, Offset(s * 0.16f, s * 0.55f), Size(barW, s * 0.4f))
                    drawRect(color, Offset(s * 0.42f, s * 0.35f), Size(barW, s * 0.6f))
                    drawRect(color, Offset(s * 0.68f, s * 0.15f), Size(barW, s * 0.8f))
                }
                1 -> {
                    drawLine(color, Offset(s * 0.12f, s * 0.78f), Offset(s * 0.40f, s * 0.46f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.40f, s * 0.46f), Offset(s * 0.62f, s * 0.66f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.62f, s * 0.66f), Offset(s * 0.88f, s * 0.22f), strokeWidth = w)
                }
                else -> {
                    drawLine(color, Offset(s * 0.12f, s * 0.72f), Offset(s * 0.30f, s * 0.40f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.30f, s * 0.40f), Offset(s * 0.52f, s * 0.74f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.52f, s * 0.74f), Offset(s * 0.70f, s * 0.30f), strokeWidth = w)
                    drawLine(color, Offset(s * 0.70f, s * 0.30f), Offset(s * 0.88f, s * 0.52f), strokeWidth = w)
                }
            }
        }
    )
}



/** 日期导航条：‹ [标签 📅] ›，中间点击打开日历 */
/** 小日历图标 */
@Composable
internal fun CalendarGlyph(color: Color) {
    Box(
        Modifier.size(13.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.10f
            drawRoundRect(
                color,
                topLeft = Offset(w, s * 0.16f),
                size = Size(s - w * 2f, s * 0.72f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f),
                style = Stroke(width = w)
            )
            drawLine(
                color,
                Offset(w, s * 0.40f),
                Offset(s - w, s * 0.40f),
                strokeWidth = w
            )
            drawLine(color, Offset(s * 0.32f, 0f), Offset(s * 0.32f, s * 0.22f), strokeWidth = w)
            drawLine(color, Offset(s * 0.68f, 0f), Offset(s * 0.68f, s * 0.22f), strokeWidth = w)
        }
    )
}



/** 把最大值抬到「整」刻度（1/2/5 × 10^n），并留 12% 顶部余量避免最高柱贴顶 */
internal fun niceAxisMax(value: Float): Float {
    if (value <= 0f) return 1f
    val padded = value * 1.12f
    val exp = Math.floor(Math.log10(padded.toDouble())).toInt()
    var base = 1.0
    var e = 0
    while (e < exp) {
        base *= 10.0
        e++
    }
    val norm = padded / base
    val niceNorm = when {
        norm <= 1.0 -> 1.0
        norm <= 2.0 -> 2.0
        norm <= 5.0 -> 5.0
        else -> 10.0
    }
    return (niceNorm * base).toFloat()
}



/** Y 轴单位标题：只有小时 / 分钟两档（短文案，避免 40dp 列宽截断） */
internal fun axisUnitMs(axisMax: Float): String {
    return if (axisMax >= 3_600_000f) "小时" else "分钟"
}



/** 毫秒值换算成当前轴单位下的数值 */
internal fun axisValueMs(value: Float, axisMax: Float): Double {
    val unit = if (axisMax >= 3_600_000f) 3_600_000.0 else 60_000.0
    return value / unit
}



/**
 * 按刻度间隔的小数位格式化，避免「0 / 0 / 0 / 1」这种截断重复
 *
 * 例：step=0.5 → 输出 0、0.5、1、1.5、2；step=10 → 输出 0、10、20
 */
internal fun formatAxisNumber(value: Double, step: Double): String {
    val safeStep = if (step <= 0.0) 1.0 else step
    val digits = try {
        java.math.BigDecimal.valueOf(safeStep).stripTrailingZeros().scale().coerceIn(0, 2)
    } catch (t: Throwable) {
        0
    }
    return try {
        java.math.BigDecimal.valueOf(value)
            .setScale(digits, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    } catch (t: Throwable) {
        value.toLong().toString()
    }
}



/** Y 轴刻度文字（单位由标题承担，这里只给数字） */
internal fun axisTickLabel(value: Float, byTime: Boolean, axisMax: Float, step: Float): String {
    if (!byTime) return value.toInt().toString()
    if (value <= 0f) return "0"
    return formatAxisNumber(axisValueMs(value, axisMax), axisValueMs(step, axisMax))
}



/**
 * Fritsch–Carlson 单调三次插值
 *
 * 关键：对每个点的切线做保单调限幅，曲线只会落在相邻两点的取值范围内，
 * 不会像 Catmull-Rom 那样在数据突变处冲出绘图区（表现为曲线跑到 x 轴下面）。
 */
internal fun monotonePath(pts: List<androidx.compose.ui.geometry.Offset>): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    val n = pts.size
    if (n == 0) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (n == 1) return path

    // 1) 每个区间的斜率
    val slope = FloatArray(n - 1)
    var i = 0
    while (i < n - 1) {
        val dx = pts[i + 1].x - pts[i].x
        slope[i] = if (dx != 0f) (pts[i + 1].y - pts[i].y) / dx else 0f
        i++
    }

    // 2) 每个数据点的切线：极值点取 0，否则取左右斜率均值
    val m = FloatArray(n)
    m[0] = slope[0]
    m[n - 1] = slope[n - 2]
    i = 1
    while (i < n - 1) {
        m[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
        i++
    }

    // 3) 保单调限幅（Fritsch–Carlson）
    i = 0
    while (i < n - 1) {
        if (slope[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
        } else {
            val a = m[i] / slope[i]
            val b = m[i + 1] / slope[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * slope[i]
                m[i + 1] = t * b * slope[i]
            }
        }
        i++
    }

    // 4) 用限幅后的切线画三次贝塞尔
    i = 0
    while (i < n - 1) {
        val dx = pts[i + 1].x - pts[i].x
        path.cubicTo(
            pts[i].x + dx / 3f, pts[i].y + m[i] * dx / 3f,
            pts[i + 1].x - dx / 3f, pts[i + 1].y - m[i + 1] * dx / 3f,
            pts[i + 1].x, pts[i + 1].y
        )
        i++
    }
    return path
}




