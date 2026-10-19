package com.spwmods.waerwak.listenstats.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 对齐 SaltUI 的桌面端设计 token
 *
 * 来源（Moriafly/SaltUI，Apache-2.0）：
 *  - `SaltColors.defaultDark()/defaultLight()`
 *  - `SaltShapesDefaults`（desktop）：small 4dp / medium 6dp / large 10dp
 *  - `SaltDimens`（desktop）：item 32dp / itemIcon 20dp / padding 16dp / subPadding 12dp
 *  - `SaltTextStyles`（desktop）：main 14sp / sub 12sp / paragraph 16sp / largeTitle 24sp SemiBold
 *
 * 圆角用自绘 Shape 实现（宿主把 RoundedCornerShape(Dp)/CornerSize(Dp) 都 R8 掉了）。
 */
object SaltDesign {

    // ---------------------------------------------------------------- 深色
    val highlight = Color(0xFF0088FF)
    val text = Color(0xFFEBEEF1)
    val subText = Color(0xBFE1E6EB)
    val background = Color(0xFF0E1015)
    val subBackground = Color(0x0FFFFFFF)
    val card = Color(0xFF16181F)
    val stroke = Color(0x1AFFFFFF)
    val onHighlight = Color(0xFFFFFFFF)
    val success = Color(0xFF63D18C)
    val warning = Color(0xFFFFB84D)
    val error = Color(0xFFFF6B6B)

    // ---------------------------------------------------------------- 浅色（SaltUI defaultLight）
    val lightHighlight = Color(0xFF0470E6)
    val lightText = Color(0xFF1E1715)
    val lightSubText = Color(0xFF8C8C8C)
    val lightBackground = Color(0xFFF3F3F3)
    val lightSubBackground = Color(0xCCFFFFFF)
    val lightCard = Color(0xFFFFFFFF)
    val lightOnHighlight = Color(0xFFFFFFFF)

    // ---------------------------------------------------------------- 液态玻璃
    val glass = Color(0x33FFFFFF)
    val glassStrong = Color(0x5CFFFFFF)
    val glassBorder = Color(0x4DFFFFFF)
    val lightGlass = Color(0x66000000)
    val lightGlassStrong = Color(0x99000000)
    val lightGlassBorder = Color(0x33000000)

    // ---------------------------------------------------------------- 尺寸（SaltUI dimens, desktop）
    val padding = 16.dp
    val subPadding = 14.dp
    val item = 32.dp
    val itemIcon = 20.dp

    // ---------------------------------------------------------------- 形状（SaltUI shapes, desktop）
    val shapeSmall: Shape = ExactRoundedShape(4f)
    val shapeMedium: Shape = ExactRoundedShape(6f)
    val shapeLarge: Shape = ExactRoundedShape(10f)
    val circle: Shape = ExactRoundedShape(-1f)

    // ---------------------------------------------------------------- 文字
    val fontSizeMain = 14.sp
    val fontSizeSub = 12.sp
    val fontSizeLabel = 13.sp
    val fontSizeParagraph = 16.sp
    val fontSizeTitle = 20.sp
    val fontSizeKpi = 22.sp
    val fontSizeLargeTitle = 24.sp
    val weightLargeTitle = FontWeight.SemiBold
    val weightMedium = FontWeight.Medium
}

/**
 * 一套完整的配色（深/浅两套，由宿主主题决定）
 */
class SaltPalette(
    val isDark: Boolean,
    val highlight: Color,
    val text: Color,
    val subText: Color,
    /** 次级标签的浅蓝强调色（用于 KPI 标签等） */
    val labelAccent: Color,
    val background: Color,
    val card: Color,
    val subBackground: Color,
    val onHighlight: Color,
    val error: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    val circleBackground: Color,
    val circleBackgroundStrong: Color,
    val circleBorder: Color
)

val SaltDarkPalette = SaltPalette(
    isDark = true,
    highlight = Color(0xFF4CC2FF),
    text = Color(0xFFEBEEF1),
    subText = Color(0xBFE1E6EB),
    labelAccent = Color(0xFF8FC7E8),
    background = Color(0xFF12161B),
    card = Color(0xFF1E262D),
    subBackground = Color(0x14FFFFFF),
    onHighlight = Color(0xFF0E1418),
    error = Color(0xFFFF6B6B),
    glass = Color(0x33FFFFFF),
    glassStrong = Color(0x5CFFFFFF),
    glassBorder = Color(0x4DFFFFFF),
    circleBackground = Color(0xE61E262D),
    circleBackgroundStrong = Color(0xF2232C34),
    circleBorder = Color(0x33FFFFFF)
)

val SaltLightPalette = SaltPalette(
    isDark = false,
    highlight = Color(0xFF4CC2FF),
    text = Color(0xFF1E1715),
    subText = Color(0xFF7A7A7A),
    labelAccent = Color(0xFF3A7EA6),
    background = Color(0xFFFAFAFA),
    card = Color(0xFFFFFFFF),
    subBackground = Color(0x99FFFFFF),
    onHighlight = Color(0xFF0E1418),
    error = Color(0xFFD32F2F),
    glass = Color(0x66000000),
    glassStrong = Color(0x99000000),
    glassBorder = Color(0x33000000),
    circleBackground = Color(0xF2FFFFFF),
    circleBackgroundStrong = Color(0xFFFFFFFF),
    circleBorder = Color(0x1A000000)
)

val LocalSaltPalette = staticCompositionLocalOf { SaltDarkPalette }

/**
 * 精确圆角矩形（radiusDp <= 0 表示完全圆形）
 *
 * 为什么要自绘：宿主的 R8 只保留了 `RoundedCornerShape` 的 `CornerSize` 构造函数，
 * `RoundedCornerShape(Dp)` / `CornerSize(Dp)` 工厂都被裁掉了。
 */
internal class ExactRoundedShape(private val radiusDp: Float) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val limit = size.minDimension / 2f
        val radius = if (radiusDp <= 0f) limit else {
            val px = radiusDp * density.density
            if (px > limit) limit else px
        }
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                radiusX = radius,
                radiusY = radius
            )
        )
    }
}
