package com.spwmods.waerwak.listenstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.stats.TrackDict
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign

/**
 * 图表选中时段的歌曲明细
 *
 * 数据来自 events 里的 spans 与选中区间求交（秒级）；明细超过保留期时退化为按天聚合。
 * 行点击打开曲目详情，便于从"哪天听得多"直接跳到"哪首"。
 */
@Composable
internal fun RangeDetailPanel(
    title: String,
    fromMs: Long,
    toMs: Long,
    onClose: () -> Unit
) {
    val p = LocalSaltPalette.current
    val rows = remember(fromMs, toMs) { StatsStore.trackDetailsInRange(fromMs, toMs) }
    var total = 0L
    var i = 0
    while (i < rows.size) {
        total += rows[i].ms
        i++
    }
    Spacer(Modifier.height(14.dp))
    SaltGroup {
        GroupHeader("歌曲明细 · " + title)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "共 " + rows.size + " 首 · " + formatMsPrecise(total),
                fontSize = SaltDesign.fontSizeSub,
                color = p.subText,
                modifier = Modifier.weight(1f)
            )
            SmallButton("收起", true, onClose)
        }
        if (rows.isEmpty()) {
            EmptyHint("这个时间段没有明细（可能已超过明细保留天数）")
        }
        var shown = 0
        while (shown < rows.size && shown < 12) {
            RowDivider()
            val row = rows[shown]
            shown++
            val entry = TrackDict.get(row.key)
            Surface(
                onClick = { TrackDetailState.open.value = row.key },
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CompactListRow(
                        coverId = if (entry == null) "" else entry.id,
                        title = if (row.title.isBlank()) "未知曲目" else row.title,
                        summary = row.artist + (if (row.count > 0) " · " + row.count + " 次" else ""),
                        value = formatMsPrecise(row.ms),
                        badge = missingBadgeOf(row.key)
                    )
                }
            }
        }
    }
}
