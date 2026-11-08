package com.spwmods.waerwak.listenstats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.TrackDict
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign

/**
 * 图表时段明细弹窗（点柱子 / 折线点后打开）
 *
 * 数据来自 events 里的 spans 与选中区间求交（秒级）；明细超过保留期时退化为按天聚合。
 */
internal object RangeDetailState {
    val open = mutableStateOf(false)
    val title = mutableStateOf("")
    val fromMs = mutableStateOf(0L)
    val toMs = mutableStateOf(0L)
}

@Composable
internal fun RangeDetailDialog(onDismiss: () -> Unit) {
    val p = LocalSaltPalette.current
    val title = RangeDetailState.title.value
    val fromMs = RangeDetailState.fromMs.value
    val toMs = RangeDetailState.toMs.value
    val rows = remember(fromMs, toMs) { StatsStore.trackDetailsInRange(fromMs, toMs) }
    var page by remember(fromMs, toMs) { mutableStateOf(0) }
    val pageSize = 10
    val pageCount = if (rows.isEmpty()) 1 else (rows.size + pageSize - 1) / pageSize
    val safePage = if (page >= pageCount) pageCount - 1 else page
    var totalCount = 0
    var totalMs = 0L
    var i = 0
    while (i < rows.size) {
        totalCount += rows[i].count
        totalMs += rows[i].ms
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
                GroupHeader(title)
                Text(
                    totalCount.toString() + " 次 · " + formatMsPrecise(totalMs) + " · " + rows.size + " 首曲目",
                    fontSize = SaltDesign.fontSizeMain,
                    color = p.labelAccent
                )
                Spacer(Modifier.height(12.dp))
                if (rows.isEmpty()) {
                    EmptyHint("这个时间段没有明细（可能已超过明细保留天数）")
                }
                var from = safePage * pageSize
                var shown = 0
                while (from < rows.size && shown < pageSize) {
                    val row = rows[from]
                    if (shown > 0) RowDivider()
                    val entry = remember(row.key) { TrackDict.get(row.key) }
                    Surface(
                        onClick = {
                            RangeDetailState.open.value = false
                            TrackDetailState.open.value = row.key
                        },
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(46.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TrackCover(if (entry == null) "" else entry.id, 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    if (row.title.isBlank()) "未知曲目" else row.title,
                                    fontSize = SaltDesign.fontSizeMain,
                                    lineHeight = 17.sp,
                                    color = p.text
                                )
                                if (row.artist.isNotBlank()) {
                                    Spacer(Modifier.height(1.dp))
                                    Text(
                                        row.artist,
                                        fontSize = SaltDesign.fontSizeSub,
                                        lineHeight = 14.sp,
                                        color = p.subText
                                    )
                                }
                            }
                            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                                Text(row.count.toString() + " 次", fontSize = SaltDesign.fontSizeSub, color = p.text)
                            }
                            Box(Modifier.width(84.dp), contentAlignment = Alignment.CenterEnd) {
                                Text(formatMsPrecise(row.ms), fontSize = SaltDesign.fontSizeSub, color = p.subText)
                            }
                        }
                    }
                    from++
                    shown++
                }
                if (pageCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    Pager(
                        page = safePage,
                        pageCount = pageCount,
                        onPage = { page = it },
                        total = rows.size
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    SortChip("关闭", selected = false, modifier = Modifier.width(96.dp)) { onDismiss() }
                }
            }
        }
    }
}
