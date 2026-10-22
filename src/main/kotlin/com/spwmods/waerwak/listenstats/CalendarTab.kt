@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spwmods.waerwak.listenstats.stats.DayAgg
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.RangePreset
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import java.time.LocalDate

@Composable
internal fun CalendarTab(tick: Int, onSelectDay: (String) -> Unit) {
    // 全量按天数据：热力图的年份视图 / 时长分布的自定义区间都按日历查值，
    // 用「最近 N 天」会让更早的日期静默变成「无收听」（显示范围外还会漏年份）
    val days = remember(tick) { StatsStore.allDays() }
    val map = HashMap<String, DayAgg>()
    var i = 0
    while (i < days.size) {
        map[days[i].date] = days[i]
        i++
    }

    // 时长分布状态
    var preset by remember { mutableStateOf(RangePreset.Today) }
    var customFrom by remember { mutableStateOf(LocalDate.now().minusDays(29)) }
    var customTo by remember { mutableStateOf(LocalDate.now()) }
    var byTime by remember { mutableStateOf(true) }
    var chartType by remember { mutableStateOf(0) } // 0=柱状 1=折线 2=曲线

    val pickResult = CalState.result.value
    LaunchedEffect(pickResult) {
        if (pickResult != null) {
            if (CalState.pickTarget.value == 1) {
                customTo = pickResult
            } else {
                customFrom = pickResult
            }
            CalState.result.value = null
            CalState.pickTarget.value = 0
            preset = RangePreset.Custom
        }
    }

    var contentWidthPx by remember { mutableStateOf(0) }
    val dLocal = LocalDensity.current
    // 两个组件与内容同宽（内容本身已限制在屏幕宽度的可配置百分比内）
    val targetWidth = if (contentWidthPx > 0) {
        with(dLocal) { contentWidthPx.toDp() }
    } else {
        0.dp
    }
    // 设计宽：仅用于信息页展示（格子尺寸现在按实际内容宽计算）

    Column(
        Modifier.fillMaxSize().onGloballyPositioned { contentWidthPx = it.size.width },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ActivityHeatmap(
            daily = map,
            tick = tick,
            width = targetWidth,
            onSelectDay = onSelectDay
        )
        Spacer(Modifier.height(26.dp))
        if (targetWidth > 0.dp) {
            DurationChart(
                width = targetWidth,
                tick = tick,
                preset = preset,
                customFrom = customFrom,
                customTo = customTo,
                byTime = byTime,
                chartType = chartType,
                daily = map,
                onPreset = { preset = it },
                onPickFrom = {
                    CalState.pickTarget.value = 0
                    CalState.seed.value = customFrom
                    CalState.open.value = true
                },
                onPickTo = {
                    CalState.pickTarget.value = 1
                    CalState.seed.value = customTo
                    CalState.open.value = true
                },
                onByTime = { byTime = it },
                onChartType = { chartType = it }
            )
        }
    }
}



/** 年份下拉项 */
internal class YearOption(val value: Int, val label: String)



/** 日历弹窗状态（由面板层渲染） */
internal object CalState {
    val open = mutableStateOf(false)
    val seed = mutableStateOf(LocalDate.now())
    val result = mutableStateOf<LocalDate?>(null)
    /** 自定义范围选择目标：0 = 开始日期，1 = 结束日期 */
    val pickTarget = mutableStateOf(0)
}



/** 月历选择器（选一天） */
@Composable
internal fun CalendarPicker(
    initial: LocalDate,
    maxDate: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalSaltPalette.current
    var month by remember { mutableStateOf(initial.withDayOfMonth(1)) }
    val firstMonday = month.minusDays((month.dayOfWeek.value - 1).toLong())
    val maxMonthStart = maxDate.withDayOfMonth(1)
    val canForward = month.isBefore(maxMonthStart)

    Box(
        Modifier.fillMaxSize().dismissOnOutsidePress(onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = SaltDesign.shapeLarge,
            color = p.dialogSurface,
            shadowElevation = 18.dp,
            modifier = Modifier.consumePress()
        ) {
            Column(Modifier.width(360.dp).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = { month = month.minusMonths(1) },
                        shape = SaltDesign.shapeSmall,
                        color = Color.Transparent,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ChevronGlyph(left = true, color = p.subText)
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            month.year.toString() + " 年 " + month.monthValue + " 月",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text
                        )
                    }
                    Surface(
                        onClick = { if (canForward) month = month.plusMonths(1) },
                        shape = SaltDesign.shapeSmall,
                        color = Color.Transparent,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ChevronGlyph(
                                left = false,
                                color = if (canForward) p.subText else p.subText.copy(alpha = 0.25f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    var c = 0
                    while (c < 7) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(weekdayShort(c), fontSize = 11.sp, color = p.subText)
                        }
                        c++
                    }
                }
                Spacer(Modifier.height(4.dp))
                var r = 0
                while (r < 6) {
                    Row(Modifier.fillMaxWidth()) {
                        var c = 0
                        while (c < 7) {
                            val date = firstMonday.plusDays((r * 7 + c).toLong())
                            val inMonth = date.monthValue == month.monthValue
                            val future = date.isAfter(maxDate)
                            val selected = date == initial
                            Box(
                                Modifier.weight(1f).height(34.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    onClick = { if (!future) onPick(date) },
                                    shape = SaltDesign.circle,
                                    color = if (selected) p.highlight else Color.Transparent,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            date.dayOfMonth.toString(),
                                            fontSize = 12.sp,
                                            color = when {
                                                selected -> p.onHighlight
                                                future -> p.subText.copy(alpha = 0.22f)
                                                inMonth -> p.text
                                                else -> p.subText.copy(alpha = 0.45f)
                                            }
                                        )
                                    }
                                }
                            }
                            c++
                        }
                    }
                    r++
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}



internal fun weekdayShort(index: Int): String = when (index) {
    0 -> "一"
    1 -> "二"
    2 -> "三"
    3 -> "四"
    4 -> "五"
    5 -> "六"
    else -> "日"
}


