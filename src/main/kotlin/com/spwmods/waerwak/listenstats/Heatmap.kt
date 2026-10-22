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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spwmods.waerwak.listenstats.stats.DayAgg
import com.spwmods.waerwak.listenstats.stats.HeatmapScale
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import java.time.LocalDate

/** 热力图：悬浮定位准确 + 描边高亮 + 跟随鼠标的提示卡片 + 年份切换 */
@Composable
internal fun ActivityHeatmap(
    daily: Map<String, DayAgg>,
    tick: Int,
    width: androidx.compose.ui.unit.Dp,
    onSelectDay: (String) -> Unit
) {
    val p = LocalSaltPalette.current
    val dLocal = LocalDensity.current
    val today = LocalDate.now()

    // -1 = 近一年；否则是具体年份
    var yearMode by remember { mutableStateOf(-1) }
    var yearPickerOpen by remember { mutableStateOf(false) }
    var containerPx by remember { mutableStateOf(0f) }
    var hoverKey by remember { mutableStateOf("") }
    var hoverX by remember { mutableStateOf(0f) }
    var hoverY by remember { mutableStateOf(0f) }

    // 年份选项：只列出「有数据」的年份（无数据不显示）
    val years = remember(tick, daily.size) {
        val set = sortedSetOf<Int>()
        for (entry in daily.entries) {
            val v = entry.value
            if (v.listenedMs <= 0L && v.count <= 0) continue
            val y = entry.key.substringBefore('-').toIntOrNull()
            if (y != null && y >= 2000 && y <= today.year + 1) set.add(y)
        }
        val list = ArrayList<YearOption>(set.size + 1)
        list.add(YearOption(-1, "近一年"))
        val arr = ArrayList<Int>(set)
        var idx = arr.size
        while (idx > 0) {
            val y = arr[idx - 1]
            list.add(YearOption(y, y.toString() + " 年"))
            idx--
        }
        list
    }

    // 显示范围：近一年 = 去年今天 → 今天；年份 = 1 月 1 日 → 12 月 31 日
    val firstDate: LocalDate
    val lastDate: LocalDate
    if (yearMode < 0) {
        firstDate = today.minusDays(365)
        lastDate = today
    } else {
        firstDate = LocalDate.of(yearMode, 1, 1)
        lastDate = LocalDate.of(yearMode, 12, 31)
    }
    // 网格按整周对齐，但只绘制 firstDate ~ lastDate 之间的格子（首列不一定是周一）
    val fullStart = firstDate.minusDays((firstDate.dayOfWeek.value - 1).toLong())
    val fullEnd = lastDate.plusDays((7 - lastDate.dayOfWeek.value).toLong())
    val fullWeeks = ((fullEnd.toEpochDay() - fullStart.toEpochDay()) / 7L).toInt() + 1

    // 动态色阶上限：当前显示范围内「收听时长最高的那一天」
    // 这样无论每天听几分钟还是几小时，热力图总有完整的深浅层次
    val highMs = remember(tick, yearMode) {
        var best = 0L
        for (entry in daily.entries) {
            val date = try {
                LocalDate.parse(entry.key)
            } catch (t: Throwable) {
                continue
            }
            if (date.isBefore(fullStart) || date.isAfter(fullEnd)) continue
            if (date.isAfter(today)) continue
            if (yearMode >= 0 && date.year != yearMode) continue
            val ms = entry.value.listenedMs
            if (ms > best) best = ms
        }
        best
    }

    val labelCol = 24.dp
    val labelGap = 4.dp
    val gap = 3.dp

    // 格子尺寸：按当前内容宽铺满完整周数，限制在 11–22dp；只有窄到 11dp 都放不下才减列
    var cellDp = 16.dp
    var weeks = fullWeeks
    var gridStart = fullStart
    val labelPx = with(dLocal) { (labelCol + labelGap).toPx() }
    val gapPx = with(dLocal) { gap.toPx() }
    if (containerPx > 0f) {
        val usable = containerPx - labelPx
        if (usable > 0f) {
            // 先把格子铺满完整周数（上限 22dp）。不再用 11dp 当下限——
            // 一旦被下限卡住，实际宽度会比可用宽度多出零点几像素，fit 取整后差 1 周，
            // 又走回「砍掉最早一周」的老路。
            val exact = (usable - gapPx * (fullWeeks - 1)) / fullWeeks
            var cell = with(dLocal) { exact.toDp() }
            if (cell > 22.dp) cell = 22.dp
            if (cell < 6.dp) cell = 6.dp
            cellDp = cell
            val cellPxNow = with(dLocal) { cellDp.toPx() }
            // 向上取整：可用宽度差零点几像素时仍算放得下
            val fit = Math.ceil(((usable + gapPx) / (cellPxNow + gapPx)).toDouble()).toInt()
            if (fit < fullWeeks) {
                // 只有窄到 6dp 仍放不下（极端窗口）才退化为截断，且保留最早的周
                weeks = if (fit < 8) 8 else fit
                gridStart = fullEnd.minusDays((weeks - 1L) * 7L + 6L)
            } else {
                weeks = fullWeeks
                gridStart = fullStart
            }
        }
    }
    val gridWidth = (cellDp + gap) * weeks - gap
    val gridHeight = (cellDp + gap) * 7 - gap
    val cellPx = with(dLocal) { cellDp.toPx() }
    // 月份 / 星期标签固定字号，不跟随「字体大小」设置（用 fontScale 反向补偿）
    val fixedLabelSize = ((cellDp.value * 0.62f).coerceIn(9f, 15f) / dLocal.fontScale).sp
    val labelSize = fixedLabelSize

    // 统计摘要
    val summary = remember(tick, yearMode) {
        var activeDays = 0
        var totalMs = 0L
        val from = firstDate
        val to = lastDate
        for (entry in daily.entries) {
            val d = try {
                LocalDate.parse(entry.key)
            } catch (t: Throwable) {
                continue
            }
            if (d.isBefore(from) || d.isAfter(to)) continue
            if (entry.value.listenedMs > 0L || entry.value.count > 0) {
                activeDays++
                totalMs += entry.value.listenedMs
            }
        }
        val label = if (yearMode < 0) "近一年" else yearMode.toString() + " 年"
        label + " · " + activeDays + " 个听歌日 · " + formatMs(totalMs)
    }

    Column(Modifier.width(width)) {
        // 标题行：左侧统计摘要，右侧年份下拉（用 DropdownMenu，不参与布局）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                summary,
                fontSize = SaltDesign.fontSizeSub,
                color = p.subText,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.height(28.dp).width(104.dp)) {
                Surface(
                    onClick = { yearPickerOpen = !yearPickerOpen },
                    shape = SaltDesign.shapeSmall,
                    color = p.subBackground,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val current = if (yearMode < 0) "近一年" else yearMode.toString() + " 年"
                        Text(current, fontSize = SaltDesign.fontSizeSub, color = p.text)
                        Spacer(Modifier.width(6.dp))
                        ChevronGlyph(left = false, color = p.subText, rotateDown = true)
                    }
                }
                DropdownMenu(
                    expanded = yearPickerOpen,
                    onDismissRequest = { yearPickerOpen = false },
                    modifier = Modifier.width(136.dp),
                    // 不给 containerColor 会取 MaterialTheme 的 surface——浅色主题下可能是深色，
                    // 与我们的浅色文本撞成黑底黑字
                    containerColor = p.dialogSurface
                ) {
                    var oi = 0
                    while (oi < years.size && oi < 8) {
                        val opt = years[oi]
                        DropdownMenuItem(
                            text = {
                                Text(
                                    opt.label,
                                    fontSize = SaltDesign.fontSizeSub,
                                    color = if (opt.value == yearMode) p.highlight else p.text
                                )
                            },
                            onClick = {
                                yearMode = opt.value
                                yearPickerOpen = false
                                hoverKey = ""
                            }
                        )
                        oi++
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier.fillMaxWidth().onGloballyPositioned { containerPx = it.size.width.toFloat() }
        ) {
            Row {
                // 星期标签（一 / 三 / 五 / 日），字号随格子缩放
                Column(
                    Modifier.width(labelCol).padding(top = 20.dp).height(gridHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    var d = 0
                    while (d < 7) {
                        val label = when (d) {
                            0 -> "一"
                            2 -> "三"
                            4 -> "五"
                            6 -> "日"
                            else -> ""
                        }
                        Text(label, fontSize = labelSize, color = p.subText)
                        d++
                    }
                }
                Spacer(Modifier.width(labelGap))
                Column {
                    // 月份标签
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        var w = 0
                        var lastMonth = -1
                        while (w < weeks) {
                            val m = gridStart.plusDays(w * 7L).monthValue
                            Box(Modifier.width(cellDp).height(16.dp)) {
                                if (m != lastMonth) {
                                    Text(
                                        m.toString(),
                                        fontSize = labelSize,
                                        lineHeight = (labelSize.value + 2f).sp,
                                        color = p.subText
                                    )
                                }
                            }
                            lastMonth = m
                            w++
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 整块网格一次绘制，命中靠坐标反推（精确到格）
                    Box(
                        Modifier
                            .width(gridWidth)
                            .height(gridHeight)
                            .onPointerEvent(PointerEventType.Move) { event ->
                                val change = event.changes.firstOrNull()
                                if (change != null) {
                                    val pos = change.position
                                    val step = cellPx + gapPx
                                    val col = (pos.x / step).toInt()
                                    val row = (pos.y / step).toInt()
                                    val insideX = pos.x - col * step <= cellPx
                                    val insideY = pos.y - row * step <= cellPx
                                    if (col >= 0 && col < weeks && row >= 0 && row < 7 && insideX && insideY) {
                                        val date = gridStart.plusDays(col * 7L + row)
                                        val inRange = !date.isBefore(firstDate) && !date.isAfter(lastDate)
                                        if (inRange && !date.isAfter(today)) {
                                            hoverKey = date.toString()
                                            hoverX = pos.x
                                            hoverY = pos.y
                                        } else {
                                            hoverKey = ""
                                        }
                                    } else {
                                        hoverKey = ""
                                    }
                                }
                            }
                            .onPointerEvent(PointerEventType.Exit) { hoverKey = "" }
                            .onPointerEvent(PointerEventType.Press) { event ->
                                val change = event.changes.firstOrNull()
                                if (change != null) {
                                    val pos = change.position
                                    val step = cellPx + gapPx
                                    val col = (pos.x / step).toInt()
                                    val row = (pos.y / step).toInt()
                                    val insideX = pos.x - col * step <= cellPx
                                    val insideY = pos.y - row * step <= cellPx
                                    if (col >= 0 && col < weeks && row >= 0 && row < 7 && insideX && insideY) {
                                        val date = gridStart.plusDays(col * 7L + row)
                                        val inRange = !date.isBefore(firstDate) && !date.isAfter(lastDate)
                                        if (inRange && !date.isAfter(today)) onSelectDay(date.toString())
                                    }
                                }
                            }
                            .drawBehind {
                                val radius = androidx.compose.ui.geometry.CornerRadius(cellPx * 0.18f, cellPx * 0.18f)
                                var w = 0
                                while (w < weeks) {
                                    var d = 0
                                    while (d < 7) {
                                        val date = gridStart.plusDays(w * 7L + d)
                                        val inRange = !date.isBefore(firstDate) && !date.isAfter(lastDate)
                                        if (inRange) {
                                            val x = w * (cellPx + gapPx)
                                            val y = d * (cellPx + gapPx)
                                            if (date.isAfter(today)) {
                                                // 年份视图：画一层更淡的底表示「还没到」；
                                                // 近一年视图不画未来格，让网格以今天结束
                                                // （两种模式下未来日期都不参与色阶，也不响应悬浮/点击）
                                                if (yearMode >= 0) {
                                                    drawRoundRect(
                                                        color = futureHeatColor(p.isDark),
                                                        topLeft = Offset(x, y),
                                                        size = Size(cellPx, cellPx),
                                                        cornerRadius = radius
                                                    )
                                                }
                                            } else {
                                                val key = date.toString()
                                                val agg = daily[key]
                                                val ms = if (agg == null) 0L else agg.listenedMs
                                                val level = HeatmapScale.level(ms, highMs)
                                                drawRoundRect(
                                                    color = heatColor(level, p.isDark),
                                                    topLeft = Offset(x, y),
                                                    size = Size(cellPx, cellPx),
                                                    cornerRadius = radius
                                                )
                                            }
                                        }
                                        d++
                                    }
                                    w++
                                }
                                // 悬浮格描边
                                if (hoverKey.isNotEmpty()) {
                                    val hoverDate = try {
                                        LocalDate.parse(hoverKey)
                                    } catch (t: Throwable) {
                                        null
                                    }
                                    if (hoverDate != null) {
                                        val col = ((hoverDate.toEpochDay() - gridStart.toEpochDay()) / 7L).toInt()
                                        val row = ((hoverDate.toEpochDay() - gridStart.toEpochDay()) % 7L).toInt()
                                        if (col >= 0 && col < weeks) {
                                            val x = col * (cellPx + gapPx)
                                            val y = row * (cellPx + gapPx)
                                            drawRoundRect(
                                                color = p.highlight,
                                                topLeft = Offset(x, y),
                                                size = Size(cellPx, cellPx),
                                                cornerRadius = radius,
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 1.6f * density
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                    )
                }
            }

            // 悬浮卡片：绝对定位覆盖，不参与布局（避免把下方组件顶下去）
            if (hoverKey.isNotEmpty()) {
                val hoverDate = try {
                    LocalDate.parse(hoverKey)
                } catch (t: Throwable) {
                    null
                }
                if (hoverDate != null) {
                    val agg = daily[hoverKey]
                    val ms = if (agg == null) 0L else agg.listenedMs
                    val count = if (agg == null) 0 else agg.count
                    val cardW = 168.dp
                    val cardWPx = with(dLocal) { cardW.toPx() }
                    val cardHPx = with(dLocal) { 64.dp.toPx() }
                    val labelPx = with(dLocal) { (labelCol + labelGap).toPx() }
                    // 鼠标位置换算到「网格左上角」作为基准
                    var leftPx = labelPx + hoverX + cellPx + 10f
                    if (containerPx > 0f && leftPx + cardWPx > containerPx) {
                        leftPx = labelPx + hoverX - cardWPx - 10f
                    }
                    if (leftPx < 0f) leftPx = 0f
                    var topPx = 20.dp.value * dLocal.density + hoverY
                    if (topPx + cardHPx > gridHeight.value * dLocal.density + 20.dp.value * dLocal.density) {
                        topPx = 20.dp.value * dLocal.density + hoverY - cardHPx - 8f
                    }
                    if (topPx < 0f) topPx = 0f
                    Surface(
                        shape = SaltDesign.shapeMedium,
                        color = p.dialogSurface,
                        shadowElevation = 14.dp,
                        border = BorderStroke(1.dp, SolidColor(p.subText.copy(alpha = 0.18f))),
                        modifier = Modifier
                            .offset(x = with(dLocal) { leftPx.toDp() }, y = with(dLocal) { topPx.toDp() })
                            .width(cardW)
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                hoverKey + "（" + weekdayText(hoverKey) + "）",
                                fontSize = SaltDesign.fontSizeSub,
                                color = p.text
                            )
                            Spacer(Modifier.height(4.dp))
                            Row {
                                Text("听歌时长", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                                Spacer(Modifier.weight(1f))
                                Text(formatMs(ms), fontSize = SaltDesign.fontSizeSub, color = p.highlight)
                            }
                            Row {
                                Text("播放次数", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                                Spacer(Modifier.weight(1f))
                                Text(count.toString() + " 次", fontSize = SaltDesign.fontSizeSub, color = p.text)
                            }
                        }
                    }
                }
            }
        }
    }
}



/** 热力图配色：按「当天收听时长」档位（0=无，1–4 递增）而非次数 */
internal fun heatColor(level: Int, dark: Boolean): Color {
    if (dark) {
        return when {
            level <= 0 -> Color(0x14FFFFFF)
            level == 1 -> Color(0x4D4CC2FF)
            level == 2 -> Color(0x804CC2FF)
            level == 3 -> Color(0xC04CC2FF)
            else -> Color(0xFF4CC2FF)
        }
    }
    return when {
        level <= 0 -> Color(0x14000000)
        level == 1 -> Color(0x4D0470E6)
        level == 2 -> Color(0x990470E6)
        level == 3 -> Color(0xCC0470E6)
        else -> Color(0xFF0470E6)
    }
}



/** 未来日期的格子：比「无收听」再淡一半，只保留网格存在感 */
internal fun futureHeatColor(dark: Boolean): Color =
    if (dark) Color(0x0AFFFFFF) else Color(0x0A000000)


