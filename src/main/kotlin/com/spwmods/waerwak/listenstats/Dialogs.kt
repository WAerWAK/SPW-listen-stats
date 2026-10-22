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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.CountRule
import com.spwmods.waerwak.listenstats.stats.StorageRule
import com.spwmods.waerwak.listenstats.stats.PlayEvent
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import com.spwmods.waerwak.listenstats.ui.UiStateStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 计数门槛编辑器（面板内弹层） */
@Composable
internal fun ThresholdEditor(onDismiss: () -> Unit) {
    var minSec by remember { mutableStateOf(CountRule.minSeconds) }
    var pct by remember { mutableStateOf(CountRule.percent) }
    var mode by remember { mutableStateOf(CountRule.mode) }
    var ignoreSec by remember { mutableStateOf(CountRule.ignoreMinSeconds) }
    val p = LocalSaltPalette.current

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
            Column(Modifier.width(460.dp).padding(20.dp)) {
                GroupHeader("计数门槛")
                StepperRow(
                    label = "最少时长",
                    value = minSec.toString() + " 秒",
                    canMinus = minSec > CountRule.LIMIT_MIN_SEC,
                    canPlus = minSec < CountRule.LIMIT_MAX_SEC,
                    onMinus = { minSec = minSec - 1 },
                    onPlus = { minSec = minSec + 1 }
                )
                Spacer(Modifier.height(6.dp))
                StepperRow(
                    label = "曲目百分比",
                    value = pct.toString() + " %",
                    canMinus = pct > 0,
                    canPlus = pct < 100,
                    onMinus = { pct = pct - 5 },
                    onPlus = { pct = pct + 5 }
                )
                Spacer(Modifier.height(6.dp))
                StepperRow(
                    label = "忽略极短播放",
                    value = if (ignoreSec <= 0) "关闭" else ignoreSec.toString() + " 秒以下不记",
                    canMinus = ignoreSec > CountRule.LIMIT_IGNORE_MIN_SEC,
                    canPlus = ignoreSec < CountRule.LIMIT_IGNORE_MAX_SEC,
                    onMinus = { ignoreSec = (ignoreSec - 1).coerceAtLeast(CountRule.LIMIT_IGNORE_MIN_SEC) },
                    onPlus = { ignoreSec = (ignoreSec + 1).coerceAtMost(CountRule.LIMIT_IGNORE_MAX_SEC) }
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "取值方式",
                        fontSize = SaltDesign.fontSizeMain,
                        color = p.text,
                        modifier = Modifier.weight(1f)
                    )
                    SortChip("取较大", mode == "max", Modifier.width(96.dp)) { mode = "max" }
                    Spacer(Modifier.width(8.dp))
                    SortChip("取较小", mode == "min", Modifier.width(96.dp)) { mode = "min" }
                }
                Spacer(Modifier.height(14.dp))
                val preview = if (mode == "min") {
                    "听满 " + minSec + " 秒 与 " + pct + "% 时长的较小者"
                } else {
                    "听满 " + minSec + " 秒 与 " + pct + "% 时长的较大者"
                }
                Text(preview, fontSize = SaltDesign.fontSizeSub, color = p.labelAccent)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ignoreSec <= 0) {
                        "未开启忽略：极短的播放也会被记录（当前跳过判定只看是否达到门槛）"
                    } else {
                        "听不足 " + ignoreSec + " 秒：整条丢弃（不计次数、不计时长、不记跳过）"
                    },
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.subText
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("取消", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        onClick = {
                            CountRule.save(minSec, pct, mode, ignoreSec)
                            onDismiss()
                        },
                        shape = SaltDesign.shapeSmall,
                        color = p.highlight,
                        modifier = Modifier.width(110.dp)
                    ) {
                        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                            Text("保存", fontSize = SaltDesign.fontSizeMain, color = p.onHighlight)
                        }
                    }
                }
            }
        }
    }
}


/** 曲目详情弹层状态 */
internal object TrackDetailState {
    val open = mutableStateOf<String?>(null)
}


/** 分组详情弹窗的打开状态（专辑 / 艺术家） */
internal object GroupDetailState {
    /** 非空表示打开；内容为 "album|name|owner" 或 "artist|name|" */
    val open = mutableStateOf("")
}


/** 专辑 / 艺术家详情弹窗：组内曲目列表 + 汇总 */
@Composable
internal fun GroupDetailDialog(payload: String, onDismiss: () -> Unit) {
    val p = LocalSaltPalette.current
    val parts = payload.split('|')
    val byAlbum = parts.size > 0 && parts[0] == "album"
    val name = if (parts.size > 1) parts[1] else ""
    val owner = if (parts.size > 2) parts[2] else ""
    val items = remember(payload) { StatsStore.tracksOfGroup(byAlbum, name, owner) }
    var page by remember(payload) { mutableStateOf(0) }
    val pageSize = 10
    val pageCount = if (items.isEmpty()) 1 else (items.size + pageSize - 1) / pageSize
    val safePage = if (page >= pageCount) pageCount - 1 else page

    var totalCount = 0
    var totalMs = 0L
    var i = 0
    while (i < items.size) {
        totalCount += items[i].count
        totalMs += items[i].listenedMs
        i++
    }

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
            Column(Modifier.width(620.dp).padding(20.dp)) {
                GroupHeader(if (byAlbum) "专辑" else "艺术家")
                Text(
                    name + (if (owner.isNotEmpty()) "（" + owner + "）" else ""),
                    fontSize = 22.sp,
                    color = p.text
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    totalCount.toString() + " 次 · " + formatMs(totalMs) + " · " + items.size + " 首曲目",
                    fontSize = SaltDesign.fontSizeMain,
                    color = p.labelAccent
                )
                Spacer(Modifier.height(12.dp))
                if (items.isEmpty()) {
                    EmptyHint("没有可显示的曲目")
                }
                var from = safePage * pageSize
                var shown = 0
                while (from < items.size && shown < pageSize) {
                    val t = items[from]
                    if (shown > 0) RowDivider()
                    Row(
                        Modifier.fillMaxWidth().height(46.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrackCover(t.id, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                t.title.ifBlank { "未知曲目" },
                                fontSize = SaltDesign.fontSizeMain,
                                lineHeight = 16.sp,
                                color = p.text
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                t.artist,
                                fontSize = SaltDesign.fontSizeSub,
                                lineHeight = 13.sp,
                                color = p.subText
                            )
                        }
                        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(t.count.toString() + " 次", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        }
                        Box(Modifier.width(74.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(formatMs(t.listenedMs), fontSize = SaltDesign.fontSizeSub, color = p.subText)
                        }
                    }
                    from++
                    shown++
                }
                if (pageCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SmallButton("上一页", safePage > 0) { page = safePage - 1 }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (safePage + 1).toString() + " / " + pageCount,
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallButton("下一页", safePage < pageCount - 1) { page = safePage + 1 }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}


/** 曲目详情弹层 */
@Composable
internal fun TrackDetailDialog(trackKey: String, onDismiss: () -> Unit) {
    val p = LocalSaltPalette.current
    val agg = remember(trackKey) { StatsStore.trackByKey(trackKey) }
    var history by remember(trackKey) { mutableStateOf<ArrayList<PlayEvent>>(ArrayList()) }
    var loading by remember(trackKey) { mutableStateOf(true) }
    LaunchedEffect(trackKey) {
        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            StatsStore.trackHistory(agg?.id ?: "", 20)
        }
        val target = ArrayList<PlayEvent>()
        var i = 0
        while (i < list.size) {
            target.add(list[i])
            i++
        }
        history = target
        loading = false
    }

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
            Column(Modifier.width(580.dp).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TrackCover(agg?.id ?: "", 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            agg?.title?.ifBlank { "未知曲目" } ?: "未知曲目",
                            fontSize = SaltDesign.fontSizeMain,
                            color = p.text
                        )
                        Spacer(Modifier.height(2.dp))
                        val artist = agg?.artist ?: ""
                        val album = agg?.album ?: ""
                        Text(
                            artist + (if (album.isBlank()) "" else " · " + album),
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                val skips = agg?.skipCount ?: 0
                val plays = (agg?.count ?: 0) + skips
                val skipRate = if (plays > 0) skips * 100 / plays else 0
                Row(Modifier.fillMaxWidth()) {
                    KpiCell("总播放", (agg?.count ?: 0).toString() + " 次", Modifier.weight(1f))
                    KpiCell("累计时长", formatMs(agg?.listenedMs ?: 0L), Modifier.weight(1f))
                    KpiCell("跳过率", skipRate.toString() + "%", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "首次 " + dayText(agg?.firstPlayed ?: 0L) + " · 最近 " + formatLastPlayed(agg?.lastPlayed ?: 0L) +
                        " · 跳过 " + skips + " 次",
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.subText
                )
                Spacer(Modifier.height(14.dp))
                GroupHeader("最近播放记录")
                if (loading) {
                    Text(
                        "正在读取播放记录…",
                        fontSize = SaltDesign.fontSizeSub,
                        color = p.subText
                    )
                } else if (history.isEmpty()) {
                    Text(
                        "保留期内没有逐次明细（明细只保留最近 N 天，可在信息页调整）",
                        fontSize = SaltDesign.fontSizeSub,
                        color = p.subText
                    )
                }
                var i = 0
                while (i < history.size) {
                    if (i > 0) RowDivider()
                    val e = history[i]
                    Row(
                        Modifier.fillMaxWidth().height(30.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(140.dp)) {
                            Text(
                                formatDateTime(e.t),
                                fontSize = SaltDesign.fontSizeSub,
                                color = p.subText
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            Text(
                                formatMs(e.ms),
                                fontSize = SaltDesign.fontSizeSub,
                                color = p.text
                            )
                        }
                        Box(Modifier.width(70.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                if (e.skipped) "跳过" else if (e.counted) "计入" else "未计入",
                                fontSize = SaltDesign.fontSizeSub,
                                color = if (e.skipped) p.error else p.subText
                            )
                        }
                    }
                    i++
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}


internal fun formatDateTime(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    return fmt.format(Date(timestamp))
}


internal fun dayText(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    return fmt.format(Date(timestamp))
}


/** 某天的播放明细弹层 */
@Composable
internal fun DayDetailDialog(date: String, onDismiss: () -> Unit) {
    val p = LocalSaltPalette.current
    val items = remember(date) { StatsStore.dayDetail(date) }
    var page by remember(date) { mutableStateOf(0) }
    val pageSize = 10
    val pageCount = if (items.isEmpty()) 1 else (items.size + pageSize - 1) / pageSize
    val safePage = if (page >= pageCount) pageCount - 1 else page
    var totalCount = 0
    var totalMs = 0L
    var i = 0
    while (i < items.size) {
        totalCount += items[i].count
        totalMs += items[i].listenedMs
        i++
    }

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
            Column(Modifier.width(560.dp).padding(20.dp)) {
                GroupHeader(date + "（" + weekdayText(date) + "）")
                Text(
                    totalCount.toString() + " 次 · " + formatMs(totalMs) + " · " + items.size + " 首曲目",
                    fontSize = SaltDesign.fontSizeMain,
                    color = p.labelAccent
                )
                Spacer(Modifier.height(12.dp))
                if (items.isEmpty()) {
                    EmptyHint("当天没有播放记录")
                }
                var from = safePage * pageSize
                var shown = 0
                while (from < items.size && shown < pageSize) {
                    val item = items[from]
                    if (shown > 0) RowDivider()
                    val coverId = remember(item.key) {
                        val agg = StatsStore.trackByKey(item.key)
                        if (agg == null) "" else agg.id
                    }
                    Row(
                        Modifier.fillMaxWidth().height(46.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrackCover(coverId, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                item.title.ifBlank { "未知曲目" },
                                fontSize = SaltDesign.fontSizeMain,
                                lineHeight = 17.sp,
                                color = p.text
                            )
                            if (item.artist.isNotBlank()) {
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    item.artist,
                                    fontSize = SaltDesign.fontSizeSub,
                                    lineHeight = 14.sp,
                                    color = p.subText
                                )
                            }
                        }
                        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(item.count.toString() + " 次", fontSize = SaltDesign.fontSizeSub, color = p.text)
                        }
                        Box(Modifier.width(74.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(formatMs(item.listenedMs), fontSize = SaltDesign.fontSizeSub, color = p.subText)
                        }
                    }
                    from++
                    shown++
                }
                if (pageCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "共 " + items.size + " 首",
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.weight(1f))
                        SmallButton("上一页", safePage > 0) { page = safePage - 1 }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (safePage + 1).toString() + " / " + pageCount,
                            fontSize = SaltDesign.fontSizeSub,
                            color = p.subText
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallButton("下一页", safePage < pageCount - 1) { page = safePage + 1 }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}


/** 全局字体大小编辑弹层（所有字号同比例放大） */
@Composable
internal fun FontSizeEditor(onDismiss: () -> Unit) {
    var extra by remember { mutableStateOf(OverlayInjection.fontExtraPercent.value) }
    val p = LocalSaltPalette.current
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
            Column(Modifier.width(480.dp).padding(20.dp)) {
                GroupHeader("字体大小")
                StepperRow(
                    label = "字体增加值",
                    value = if (extra <= 0) "默认" else "+" + extra + "%",
                    canMinus = extra > 0,
                    canPlus = extra < 60,
                    onMinus = { extra = (extra - 5).coerceAtLeast(0) },
                    onPlus = { extra = (extra + 5).coerceAtMost(60) }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "所有文字按相同比例放大，布局与间距不受影响；热力图的月份与星期标签固定大小，不跟随此设置",
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.subText
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("取消", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        onClick = {
                            OverlayInjection.fontExtraPercent.value = extra
                            UiStateStore.saveFontExtraPercent(extra)
                            onDismiss()
                        },
                        shape = SaltDesign.shapeSmall,
                        color = p.highlight,
                        modifier = Modifier.width(110.dp)
                    ) {
                        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                            Text("保存", fontSize = SaltDesign.fontSizeMain, color = p.onHighlight)
                        }
                    }
                }
            }
        }
    }
}


/** 明细保留天数编辑弹层 */
@Composable
internal fun RetentionEditor(onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(StorageRule.rawEventKeepDays) }
    val p = LocalSaltPalette.current
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
            Column(Modifier.width(480.dp).padding(20.dp)) {
                GroupHeader("明细保留天数")
                StepperRow(
                    label = "保留最近",
                    value = days.toString() + " 天",
                    canMinus = days > StorageRule.LIMIT_MIN_DAYS,
                    canPlus = days < StorageRule.LIMIT_MAX_DAYS,
                    onMinus = { days = (days - 7).coerceAtLeast(StorageRule.LIMIT_MIN_DAYS) },
                    onPlus = { days = (days + 7).coerceAtMost(StorageRule.LIMIT_MAX_DAYS) }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "超过该天数的原始播放明细会移到 expired 目录（不删除）；聚合数据永久保留",
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.subText
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SortChip("取消", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        onClick = {
                            StorageRule.save(days)
                            onDismiss()
                        },
                        shape = SaltDesign.shapeSmall,
                        color = p.highlight,
                        modifier = Modifier.width(110.dp)
                    ) {
                        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                            Text("保存", fontSize = SaltDesign.fontSizeMain, color = p.onHighlight)
                        }
                    }
                }
            }
        }
    }
}


@Composable
internal fun StepperRow(
    label: String,
    value: String,
    canMinus: Boolean,
    canPlus: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    val p = LocalSaltPalette.current
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = SaltDesign.fontSizeMain, color = p.text, modifier = Modifier.weight(1f))
        StepButton("−", canMinus, onMinus)
        Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
            Text(value, fontSize = SaltDesign.fontSizeMain, color = p.highlight)
        }
        StepButton("+", canPlus, onPlus)
    }
}


@Composable
internal fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalSaltPalette.current
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeSmall,
        color = if (enabled) p.subBackground else Color.Transparent,
        modifier = Modifier.size(32.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                glyph,
                fontSize = SaltDesign.fontSizeMain,
                color = if (enabled) p.text else p.subText.copy(alpha = 0.4f)
            )
        }
    }
}

