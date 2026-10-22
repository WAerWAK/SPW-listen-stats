@file:OptIn(UnstableSpwWorkshopApi::class, ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spwmods.waerwak.listenstats.ui.LocalSaltPalette
import com.spwmods.waerwak.listenstats.ui.SaltDesign
import com.spwmods.waerwak.listenstats.ui.rememberCover
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** SaltUI 分组的标题行：强调色竖条 + 标题 */
@Composable
internal fun GroupHeader(title: String) {
    Row(
        Modifier.padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = SaltDesign.shapeInner,
            color = LocalSaltPalette.current.highlight,
            modifier = Modifier.size(width = 3.dp, height = 16.dp)
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            fontSize = SaltDesign.fontSizeSectionTitle,
            fontWeight = SaltDesign.weightSectionTitle,
            color = LocalSaltPalette.current.text
        )
    }
}


/** 紧凑版条目（窄列用）：封面 + 标题/副标题 + 右侧单值 */
@Composable
internal fun CompactListRow(coverId: String, title: String, summary: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackCover(coverId, 30.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(
                title,
                fontSize = SaltDesign.fontSizeMain,
                lineHeight = 17.sp,
                color = LocalSaltPalette.current.text
            )
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    summary,
                    fontSize = SaltDesign.fontSizeSub,
                    lineHeight = 14.sp,
                    color = LocalSaltPalette.current.subText
                )
            }
        }
        Text(value, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
    }
}


/** SaltUI 风格的圆角分组容器（RoundedColumn 的简化版） */
@Composable
internal fun SaltGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SaltDesign.shapeLarge,
        color = LocalSaltPalette.current.subBackground
    ) {
        Column(
            Modifier.padding(horizontal = SaltDesign.padding, vertical = SaltDesign.subPadding),
            content = content
        )
    }
}


/** 行分隔线：SPW 原版无行间分隔线，只保留等高空白（1dp 占位避免行距跳动） */
@Composable
internal fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp))
}

// ---------------------------------------------------------------- Tab: 排行（歌曲 / 专辑 / 艺术家 合并）


/** 可滚动区域（宿主裁掉了 verticalScroll / LazyColumn，这里用滚轮 + 拖拽 + 偏移自实现）
 *  [resetKey] 变化时重置滚动位置（每个 Tab 各自独立） */
@Composable
internal fun ScrollArea(
    resetKey: Any,
    barRightExtra: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    var viewportH by remember { mutableStateOf(0) }
    var contentH by remember { mutableStateOf(0) }
    var offsetY by remember(resetKey) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val rawMax = (contentH - viewportH).coerceAtLeast(0).toFloat()
    // 差值很小时（不到 60px）不开启滚动：宁可底部少显示一点，也不让顶部被裁
    val maxOffset = if (rawMax < 60f) 0f else rawMax
    val effOffset = if (offsetY > maxOffset) maxOffset else if (offsetY < 0f) 0f else offsetY
    val step = if (viewportH > 0) (viewportH / 4f).coerceAtLeast(60f) else 60f
    var scrollEvents by remember(resetKey) { mutableStateOf(0) }
    val p = LocalSaltPalette.current
    // 滚动条隐藏（用户反馈无滚动条观感更好）
    val barColor = Color.Transparent

    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned {
                    viewportH = it.size.height
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    scrollEvents = scrollEvents + 1
                    // 注意：onPointerEvent 的回调会被 pointerInput 冻结在首次组合，
                    // 所以这里必须从 State 实时读取，不能用组合期的局部变量
                    val vh = viewportH
                    val ch = contentH
                    val max = (ch - vh).coerceAtLeast(0).toFloat()
                    if (max > 0f) {
                        val stepNow = (vh / 4f).coerceAtLeast(60f)
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        offsetY = (offsetY + dy * stepNow).coerceIn(0f, max)
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        // 以无界高度测量（允许溢出），但报告高度不超过父约束，并从 (0,0) 放置
                        val placeable = measurable.measure(
                            constraints.copy(maxHeight = Constraints.Infinity)
                        )
                        layout(placeable.width, placeable.height.coerceAtMost(constraints.maxHeight)) {
                            placeable.place(0, 0)
                        }
                    }
                    .graphicsLayer {
                        val vh = viewportH
                        val ch = contentH
                        val max = (ch - vh).coerceAtLeast(0).toFloat()
                        val off = if (offsetY > max) max else if (offsetY < 0f) 0f else offsetY
                        translationY = -off
                    }
            ) {
                Box(
                    Modifier.fillMaxWidth().onSizeChanged { contentH = it.height }
                ) {
                    content()
                }
            }
            // 滚动条 + 顶/底渐变：全部在绘制阶段按 State 实时计算，避免被组合期快照冻结
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val vh = viewportH.toFloat()
                    val ch = contentH.toFloat()
                    val max = (ch - vh).coerceAtLeast(0f)
                    if (max > 0f && ch > 0f) {
                        val off = if (offsetY > max) max else if (offsetY < 0f) 0f else offsetY
                        val barW = 8.dp.toPx()
                        val barH = (size.height * (vh / ch)).coerceAtLeast(32.dp.toPx())
                        val barY = (off / max) * (size.height - barH)
                        // 内容居中时向外偏移，使滚动条始终贴近窗口右边缘
                        val extra = barRightExtra.toPx()
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(size.width + extra - barW - 4.dp.toPx(), barY),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(barW / 2f)
                        )

                    }
                }
            )
        }
    }
}


/** SaltUI Item：左侧标题 + 右侧值 */
@Composable
internal fun ItemRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = SaltDesign.fontSizeMain,
            color = LocalSaltPalette.current.text,
            modifier = Modifier.weight(1f)
        )
        Text(value, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
    }
}

// ---------------------------------------------------------------- 通用小组件


/**
 * 排序方向小三角
 *
 * 始终占住 12dp：不显示时留空，避免切换排序列时文本左右跳动。
 */
@Composable
internal fun SortArrowSlot(active: Boolean, up: Boolean, color: Color) {
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier.size(9.dp).drawBehind {
                    val s = size.minDimension
                    val path = Path()
                    if (up) {
                        path.moveTo(s * 0.10f, s * 0.68f)
                        path.lineTo(s * 0.5f, s * 0.30f)
                        path.lineTo(s * 0.90f, s * 0.68f)
                    } else {
                        path.moveTo(s * 0.10f, s * 0.32f)
                        path.lineTo(s * 0.5f, s * 0.70f)
                        path.lineTo(s * 0.90f, s * 0.32f)
                    }
                    path.close()
                    drawPath(path, color)
                }
            )
        }
    }
}


/** 可点击的数值列头（带排序箭头） */
@Composable
internal fun SortHeaderCell(
    label: String,
    key: Int,
    width: androidx.compose.ui.unit.Dp,
    currentSort: Int,
    asc: Boolean,
    onSort: (Int) -> Unit
) {
    val p = LocalSaltPalette.current
    Surface(
        onClick = { onSort(key) },
        color = Color.Transparent,
        modifier = Modifier.width(width)
    ) {
        Row(
            Modifier.fillMaxWidth().height(36.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 16.sp,
                color = if (currentSort == key) p.highlight else p.subText
            )
            SortArrowSlot(currentSort == key, asc, p.highlight)
        }
    }
}


/**
 * SaltUI Item 风格的表头：强调色竖条 + 大标题 + 可点击排序的数值列
 *
 * 点击「曲目」按名称排序，点击数值列按对应字段排序；同一列再点一次切换升/降序。
 */
@Composable
internal fun ListHeader(
    mainTitle: String,
    currentSort: Int,
    asc: Boolean,
    onSort: (Int) -> Unit
) {
    val p = LocalSaltPalette.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp
    ) {
        Row(
            Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = { onSort(4) },
                color = Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    Modifier.fillMaxWidth().height(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = SaltDesign.shapeSmall,
                        color = p.highlight,
                        modifier = Modifier.size(width = 4.dp, height = 18.dp)
                    ) {}
                    Spacer(Modifier.width(10.dp))
                    Text(
                        mainTitle,
                        fontSize = 20.sp,
                        color = if (currentSort == 4) p.highlight else p.text
                    )
                    SortArrowSlot(currentSort == 4, asc, p.highlight)
                }
            }
            SortHeaderCell("时长", 0, 60.dp, currentSort, asc, onSort)
            SortHeaderCell("次数", 1, 52.dp, currentSort, asc, onSort)
            SortHeaderCell("跳过", 2, 52.dp, currentSort, asc, onSort)
            SortHeaderCell("上次播放", 3, 116.dp, currentSort, asc, onSort)
        }
    }
    Spacer(Modifier.height(6.dp))
}


/** 曲目封面（无封面时显示占位音符） */
@Composable
internal fun TrackCover(coverId: String, diameter: androidx.compose.ui.unit.Dp) {
    val p = LocalSaltPalette.current
    val bitmap = rememberCover(coverId)
    Surface(
        shape = SaltDesign.shapeCover,
        color = p.subBackground,
        modifier = Modifier.size(diameter)
    ) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                if (bitmap != null) {
                    drawImage(
                        image = bitmap,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    )
                } else {
                    val s = size.minDimension
                    val c = p.subText.copy(alpha = 0.30f)
                    drawCircle(c, radius = s * 0.13f, center = Offset(s * 0.38f, s * 0.68f))
                    drawRect(c, Offset(s * 0.46f, s * 0.24f), Size(s * 0.07f, s * 0.46f))
                    drawRect(c, Offset(s * 0.46f, s * 0.24f), Size(s * 0.24f, s * 0.08f))
                }
            }
        ) {}
    }
}


/** SaltUI Item 风格的两行条目：序号 + 封面 + 标题/副标题 + 右侧数值列 */
@Composable
internal fun ListRow(
    rank: Int,
    coverId: String,
    title: String,
    summary: String,
    count: String,
    duration: String,
    skip: String,
    lastPlayed: String = "",
    trackKey: String,
    onClick: (String) -> Unit,
    onRowClick: (() -> Unit)? = null
) {
    Surface(
        onClick = {
            if (onRowClick != null) {
                onRowClick()
            } else if (trackKey.isNotBlank()) {
                onClick(trackKey)
            }
        },
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                rank.toString(),
                fontSize = SaltDesign.fontSizeSub,
                color = LocalSaltPalette.current.subText,
                modifier = Modifier.width(24.dp)
            )
            TrackCover(coverId, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    title,
                    fontSize = SaltDesign.fontSizeMain,
                    lineHeight = 17.sp,
                    color = LocalSaltPalette.current.text
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    summary,
                    fontSize = SaltDesign.fontSizeSub,
                    lineHeight = 14.sp,
                    color = LocalSaltPalette.current.subText
                )
            }
            Box(Modifier.width(60.dp), contentAlignment = Alignment.CenterEnd) {
                Text(duration, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
            }
            Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                Text(count, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.text)
            }
            Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                Text(skip, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
            }
            if (lastPlayed.isNotEmpty()) {
                Box(Modifier.width(116.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(lastPlayed, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
                }
            }
        }
    }
}




@Composable
internal fun Pager(
    page: Int,
    pageCount: Int,
    onPage: (Int) -> Unit,
    total: Int,
    pageSize: Int = 10,
    onPageSize: ((Int) -> Unit)? = null
) {
    var sizeOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "共 " + total + " 条",
            fontSize = SaltDesign.fontSizeSub,
            color = LocalSaltPalette.current.subText
        )
        if (onPageSize != null) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.height(26.dp)) {
                Surface(
                    onClick = { sizeOpen = !sizeOpen },
                    shape = SaltDesign.shapeSmall,
                    color = LocalSaltPalette.current.subBackground
                ) {
                    Row(
                        Modifier.height(26.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pageSize.toString() + " 条/页",
                            fontSize = SaltDesign.fontSizeSub,
                            color = LocalSaltPalette.current.text
                        )
                        Spacer(Modifier.width(5.dp))
                        ChevronGlyph(left = false, color = LocalSaltPalette.current.subText, rotateDown = true)
                    }
                }
                DropdownMenu(
                    expanded = sizeOpen,
                    onDismissRequest = { sizeOpen = false },
                    modifier = Modifier.width(120.dp),
                    containerColor = LocalSaltPalette.current.dialogSurface
                ) {
                    var oi = 0
                    while (oi < pageSizeOptions.size) {
                        val size = pageSizeOptions[oi]
                        DropdownMenuItem(
                            text = {
                                Text(
                                    size.toString() + " 条/页",
                                    fontSize = SaltDesign.fontSizeSub,
                                    color = if (size == pageSize) LocalSaltPalette.current.highlight
                                    else LocalSaltPalette.current.text
                                )
                            },
                            onClick = {
                                sizeOpen = false
                                onPageSize(size)
                            }
                        )
                        oi++
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        SmallButton("上一页", page > 0) { onPage(page - 1) }
        Spacer(Modifier.width(8.dp))
        Text(
            (page + 1).toString() + " / " + pageCount,
            fontSize = SaltDesign.fontSizeSub,
            color = LocalSaltPalette.current.subText
        )
        Spacer(Modifier.width(8.dp))
        SmallButton("下一页", page < pageCount - 1) { onPage(page + 1) }
    }
}


internal val pageSizeOptions = intArrayOf(10, 15, 20, 30, 50)



/** 下划线式 Tab（第一层：歌曲/专辑/艺术家） */
@Composable
internal fun UnderlineTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 42.dp,
    onClick: () -> Unit
) {
    val p = LocalSaltPalette.current
    Surface(onClick = onClick, color = Color.Transparent, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    fontSize = SaltDesign.fontSizeMain,
                    color = if (selected) p.highlight else p.subText
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = if (selected) p.highlight else p.subText.copy(alpha = 0.15f)
            ) {}
        }
    }
}


/** 实心小 chip（第二层：排序方式） */
@Composable
internal fun SortChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val p = LocalSaltPalette.current
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeSmall,
        color = if (selected) p.highlight else p.subBackground,
        modifier = modifier
    ) {
        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeSub,
                color = if (selected) p.onHighlight else p.subText
            )
        }
    }
}


@Composable
internal fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = SaltDesign.shapeInner,
        color = if (selected) LocalSaltPalette.current.highlight else LocalSaltPalette.current.subBackground
    ) {
        Box(
            Modifier.height(SaltDesign.segmentHeight).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeSub,
                color = if (selected) LocalSaltPalette.current.onHighlight else LocalSaltPalette.current.text
            )
        }
    }
}


/** 小号文字按钮（分页、关闭等） */
@Composable
internal fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = SaltDesign.shapeSmall,
        color = if (enabled) LocalSaltPalette.current.subBackground else Color.Transparent
    ) {
        Box(
            Modifier.height(26.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = SaltDesign.fontSizeSub,
                color = if (enabled) LocalSaltPalette.current.text else LocalSaltPalette.current.subText.copy(alpha = 0.35f)
            )
        }
    }
}



@Composable
internal fun EmptyHint(text: String) {
    Text(text, fontSize = SaltDesign.fontSizeSub, color = LocalSaltPalette.current.subText)
    Spacer(Modifier.height(4.dp))
}



internal fun weekdayText(date: String): String = try {
    val d = LocalDate.parse(date)
    when (d.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
} catch (t: Throwable) {
    ""
}


internal fun formatLastPlayed(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    val fmt = SimpleDateFormat("yy-MM-dd HH:mm", Locale.ROOT)
    return fmt.format(Date(timestamp))
}


internal fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0 分"
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    if (hours > 0L && minutes > 0L) return hours.toString() + " 小时 " + minutes + " 分"
    if (hours > 0L) return hours.toString() + " 小时"
    return minutes.toString() + " 分钟"
}


/** 左右/向下箭头（自绘 V 形） */
@Composable
internal fun ChevronGlyph(left: Boolean, color: Color, rotateDown: Boolean = false) {
    Box(
        Modifier.size(14.dp).drawBehind {
            val s = size.minDimension
            val w = s * 0.12f
            if (rotateDown) {
                drawLine(color, Offset(s * 0.18f, s * 0.38f), Offset(s * 0.5f, s * 0.66f), strokeWidth = w)
                drawLine(color, Offset(s * 0.5f, s * 0.66f), Offset(s * 0.82f, s * 0.38f), strokeWidth = w)
            } else if (left) {
                drawLine(color, Offset(s * 0.62f, s * 0.15f), Offset(s * 0.30f, s * 0.5f), strokeWidth = w)
                drawLine(color, Offset(s * 0.30f, s * 0.5f), Offset(s * 0.62f, s * 0.85f), strokeWidth = w)
            } else {
                drawLine(color, Offset(s * 0.38f, s * 0.15f), Offset(s * 0.70f, s * 0.5f), strokeWidth = w)
                drawLine(color, Offset(s * 0.70f, s * 0.5f), Offset(s * 0.38f, s * 0.85f), strokeWidth = w)
            }
        }
    )
}

