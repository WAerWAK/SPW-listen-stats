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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.MissingState
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import java.time.LocalDate

@Composable
internal fun RankingTab(tick: Long) {
    var kind by remember { mutableStateOf(0) }
    // 排序键：0=时长 1=次数 2=跳过 3=上次播放 4=曲目名；默认时长降序
    var sortKey by remember { mutableStateOf(0) }
    var asc by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(0) }
    var songsPage by remember { mutableStateOf(0) }
    var albumsPage by remember { mutableStateOf(0) }
    var artistsPage by remember { mutableStateOf(0) }
    // 每页条数（10/15/20/30/50，默认 10）
    var pageSize by remember { mutableStateOf(10) }
    val searchState = remember { TextFieldState("") }
    val rawQuery = searchState.text.toString()
    // 输入防抖：每敲一个字都全量重排太浪费，停手 150ms 再查
    var query by remember { mutableStateOf("") }
    LaunchedEffect(rawQuery) {
        kotlinx.coroutines.delay(150L)
        if (query != rawQuery) {
            query = rawQuery
            // 搜索结果变短时停留在旧页码会显示空列表 + 「4 / 1」这种矛盾状态
            songsPage = 0
            albumsPage = 0
            artistsPage = 0
        }
    }
    val today = LocalDate.now()
    val sinceDate: String? = when (range) {
        1 -> today.minusDays(30).toString()
        2 -> today.minusDays(7).toString()
        else -> null
    }

    Column(Modifier.fillMaxWidth()) {
        // 上排：榜单切换（3/5）+ 搜索框（2/5）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(3f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RangeTab("总榜", range == 0, Modifier.weight(1f)) { range = 0; songsPage = 0; albumsPage = 0; artistsPage = 0 }
                RangeTab("月榜", range == 1, Modifier.weight(1f)) { range = 1; songsPage = 0; albumsPage = 0; artistsPage = 0 }
                RangeTab("周榜", range == 2, Modifier.weight(1f)) { range = 2; songsPage = 0; albumsPage = 0; artistsPage = 0 }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(2f)) {
                SearchField(searchState)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 下排：视图切换（排序改为点列头，不再单独放一排按钮）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(2f)) {
                UnderlineTab("歌曲", kind == 0, Modifier.weight(1f), height = 32.dp) { kind = 0; songsPage = 0 }
                UnderlineTab("专辑", kind == 1, Modifier.weight(1f), height = 32.dp) { kind = 1; albumsPage = 0 }
                UnderlineTab("艺术家", kind == 2, Modifier.weight(1f), height = 32.dp) { kind = 2; artistsPage = 0 }
            }
            // 视图切换固定占 2/5，其余留白（与删除排序按钮前的宽度一致）
            Spacer(Modifier.weight(3f))
        }
        Spacer(Modifier.height(10.dp))
        // 点同一列切换升降序；换列时用该列的默认方向（名称升序、其余降序）
        val onSort: (Int) -> Unit = { key ->
            if (sortKey == key) {
                asc = !asc
            } else {
                sortKey = key
                asc = key == 4
            }
            songsPage = 0
            albumsPage = 0
            artistsPage = 0
        }
        when (kind) {
            0 -> TrackListView(
                songsPage, tick, sortKey, asc, sinceDate, query, pageSize,
                { songsPage = it },
                { pageSize = it; songsPage = 0 },
                onSort
            )
            1 -> AggregateListView(
                true, albumsPage, tick, sortKey, asc, sinceDate, query, pageSize,
                { albumsPage = it },
                { pageSize = it; albumsPage = 0 },
                onSort
            )
            else -> AggregateListView(
                false, artistsPage, tick, sortKey, asc, sinceDate, query, pageSize,
                { artistsPage = it },
                { pageSize = it; artistsPage = 0 },
                onSort
            )
        }
    }
}


/** 搜索框（Material3 OutlinedTextField + 新版 TextFieldState，支持中文输入法） */
@Composable
internal fun SearchField(state: TextFieldState) {
    val p = LocalSaltPalette.current
    Surface(shape = SaltDesign.shapeMedium, color = p.subBackground, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchGlyph(p.subText)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                state = state,
                modifier = Modifier.weight(1f),
                lineLimits = TextFieldLineLimits.SingleLine,
                contentPadding = PaddingValues(0.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = SaltDesign.fontSizeSub,
                    color = p.text
                ),
                placeholder = {
                    Text("搜索歌曲/歌手/专辑", fontSize = SaltDesign.fontSizeSub, color = p.subText)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = p.highlight,
                    focusedTextColor = p.text,
                    unfocusedTextColor = p.text,
                    focusedPlaceholderColor = p.subText,
                    unfocusedPlaceholderColor = p.subText
                )
            )
        }
    }
}


/** 放大镜图标 */
@Composable
internal fun SearchGlyph(color: Color) {
    Box(
        Modifier.size(13.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.12f
            drawCircle(
                color,
                radius = s * 0.30f,
                center = Offset(s * 0.42f, s * 0.42f),
                style = Stroke(width = w)
            )
            drawLine(color, Offset(s * 0.63f, s * 0.63f), Offset(s * 0.95f, s * 0.95f), strokeWidth = w)
        }
    )
}


/** 榜单切换按钮（Tab 卡片样式，无图标） */
@Composable
internal fun RangeTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val p = LocalSaltPalette.current
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeMedium,
        color = if (selected) p.subBackground else Color.Transparent,
        border = BorderStroke(
            1.dp,
            SolidColor(if (selected) p.highlight else p.subText.copy(alpha = 0.18f))
        ),
        modifier = modifier
    ) {
        Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeMain,
                color = if (selected) p.highlight else p.subText
            )
        }
    }
}



@Composable
internal fun TrackListView(
    page: Int,
    tick: Long,
    sortKey: Int,
    asc: Boolean,
    sinceDate: String?,
    query: String,
    pageSize: Int,
    onPage: (Int) -> Unit,
    onPageSize: (Int) -> Unit,
    onSort: (Int) -> Unit
) {
    // 检测结果变了要重算：隐藏开关会改变过滤后的条数和分页，徽章也得跟着换
    val missingTick = MissingState.version.value
    val data = remember(page, tick, sortKey, asc, sinceDate, query, pageSize, missingTick) {
        StatsStore.trackPageRange(page, pageSize, sortKey, asc, query, sinceDate)
    }
    Column(Modifier.fillMaxWidth()) {
        SaltGroup {
            ListHeader("曲目", sortKey, asc, onSort)
            if (data.items.isEmpty()) {
                EmptyHint("还没有数据：听几首歌再来")
            }
            var i = 0
            while (i < data.items.size) {
                if (i > 0) RowDivider()
                val t = data.items[i]
                ListRow(
                    rank = page * pageSize + i + 1,
                    coverId = t.id,
                    title = t.title.ifBlank { "未知曲目" },
                    summary = t.artist + (if (t.album.isBlank()) "" else " · " + t.album),
                    count = t.count.toString() + " 次",
                    duration = formatMs(t.listenedMs),
                    skip = if (t.skipCount > 0) t.skipCount.toString() + " 次" else "-",
                    lastPlayed = formatLastPlayed(t.lastPlayed),
                    trackKey = t.key,
                    badge = missingBadgeOf(t.key),
                    onClick = { key -> TrackDetailState.open.value = key }
                )
                i++
            }
        }
        Spacer(Modifier.height(10.dp))
        Pager(page = page, pageCount = data.pageCount, onPage = onPage, total = data.total,
            pageSize = pageSize, onPageSize = onPageSize)
    }
}


@Composable
internal fun AggregateListView(
    byAlbum: Boolean,
    page: Int,
    tick: Long,
    sortKey: Int,
    asc: Boolean,
    sinceDate: String?,
    query: String,
    pageSize: Int,
    onPage: (Int) -> Unit,
    onPageSize: (Int) -> Unit,
    onSort: (Int) -> Unit
) {
    val missingTick = MissingState.version.value
    val data = remember(byAlbum, page, tick, sortKey, asc, sinceDate, query, pageSize, missingTick) {
        StatsStore.aggregatePageRange(page, pageSize, byAlbum, sinceDate, query, sortKey, asc)
    }
    Column(Modifier.fillMaxSize()) {
        SaltGroup {
            ListHeader(if (byAlbum) "专辑" else "艺术家", sortKey, asc, onSort)
            if (data.items.isEmpty()) {
                EmptyHint(if (byAlbum) "还没有专辑数据" else "还没有艺术家数据")
            }
            var i = 0
            while (i < data.items.size) {
                if (i > 0) RowDivider()
                val row = data.items[i]
                ListRow(
                    rank = page * pageSize + i + 1,
                    coverId = row.coverId,
                    title = row.name,
                    summary = if (row.owner.isNotEmpty()) {
                        row.owner + " · " + row.trackCount + " 首曲目"
                    } else {
                        row.trackCount.toString() + " 首曲目"
                    },
                    count = row.count.toString() + " 次",
                    duration = formatMs(row.listenedMs),
                    skip = if (row.skipCount > 0) row.skipCount.toString() + " 次" else "-",
                    lastPlayed = formatLastPlayed(row.lastPlayed),
                    trackKey = "",
                    badge = missingGroupBadge(row.missingCount, row.trackCount),
                    onClick = { },
                    onRowClick = {
                        // 带上当前榜单范围：详情弹窗按同一范围聚合，数字才和列表行一致
                        GroupDetailState.open.value = (if (byAlbum) "album" else "artist") +
                            "\u0001" + row.name + "\u0001" + row.owner +
                            "\u0001" + (sinceDate ?: "")
                    }
                )
                i++
            }
        }
        Spacer(Modifier.height(10.dp))
        Pager(page = page, pageCount = data.pageCount, onPage = onPage, total = data.total,
            pageSize = pageSize, onPageSize = onPageSize)
    }
}

// ---------------------------------------------------------------- Tab: 日历

