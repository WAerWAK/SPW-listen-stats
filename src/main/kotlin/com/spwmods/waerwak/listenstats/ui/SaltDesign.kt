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
 * 对齐 SaltUI / SPW 原版的桌面端设计 token
 *
 * 数值来源：
 *  - 着色/字号/圆角：SPW 1.19.0 经典浅深主题实测取样
 *  - 尺寸：SaltUI desktop `SaltDimens`（padding 16dp）
 *
 * 圆角用自绘 Shape 实现（宿主把 RoundedCornerShape(Dp)/CornerSize(Dp) 都 R8 掉了）。
 * 颜色统一走 [SaltPalette]，这里只放尺寸、形状与字排版。
 */
object SaltDesign {

    // ---------------------------------------------------------------- 尺寸
    val padding = 16.dp
    val subPadding = 14.dp

    /** 信息页密集小按钮的高度（比标准控件矮，避免视觉过重） */
    val segmentHeight = 26.dp

    // ---------------------------------------------------------------- 形状（SPW 原版）
    /** 内按钮（分段控件内部按钮）5dp */
    val shapeInner: Shape = ExactRoundedShape(5f)
    /** 封面缩略图圆角 4dp */
    val shapeCover: Shape = ExactRoundedShape(4f)
    /** 控件 / 输入框 8dp */
    val shapeSmall: Shape = ExactRoundedShape(8f)
    val shapeMedium: Shape = ExactRoundedShape(8f)
    /** 卡片 / 分组容器 15dp */
    val shapeLarge: Shape = ExactRoundedShape(15f)
    val circle: Shape = ExactRoundedShape(-1f)

    // ---------------------------------------------------------------- 文字（SPW 原版 AppType）
    /** 页面大标题 27sp / 620 */
    val fontSizeTitle = 27.sp
    val weightTitle = FontWeight(620)
    /** 分组标题 16sp SemiBold */
    val fontSizeSectionTitle = 16.sp
    val weightSectionTitle = FontWeight.SemiBold
    /** 正文 13sp */
    val fontSizeMain = 13.sp
    /** 次级文字 12sp */
    val fontSizeSub = 12.sp
    /** KPI 数字 26sp / 550 */
    val fontSizeKpi = 26.sp
    val weightKpi = FontWeight(550)
    /** KPI 单位 17sp（汉字取数字的 2/3） */
    val fontSizeKpiUnit = 17.sp
}

/**
 * 一套配色（深/浅 × 经典/材质）
 *
 * [isDark] 之外的所有字段都由宿主的深浅色决定；
 * [saltPalette] 的 material=true 分支用于 DWM 材质（亚克力/云母）下的透明背景：
 * 卡片与控件改为半透明覆盖层，让窗口材质透出来。
 */
data class SaltPalette(
    val isDark: Boolean,
    val highlight: Color,
    val text: Color,
    val subText: Color,
    /** 次级标签色（KPI 标签等），弱于 highlight */
    val labelAccent: Color,
    val background: Color,
    val card: Color,
    val subBackground: Color,
    val onHighlight: Color,
    val error: Color,
    val circleBackground: Color,
    val circleBackgroundStrong: Color,
    val circleBorder: Color,
    /** 弹窗/浮窗/下拉框的底：始终不透明（无遮罩时靠它保证可读性） */
    val dialogSurface: Color
)

private val DarkClassic = SaltPalette(
    isDark = true,
    highlight = Color(0xFF79B5FF),
    text = Color(0xFFEBEEF1),
    subText = Color(0xFFA0A3AB),
    labelAccent = Color(0xFF8FC7E8),
    background = Color(0xFF232323),
    card = Color(0xFF2A2A2A),
    subBackground = Color(0xFF2B2D32),
    onHighlight = Color(0xFF0E1418),
    error = Color(0xFFFF8A8A),
    circleBackground = Color(0xE6232323),
    circleBackgroundStrong = Color(0xF22A2C31),
    circleBorder = Color(0x33FFFFFF),
    dialogSurface = Color(0xFF2E2E2E)
)

private val LightClassic = SaltPalette(
    isDark = false,
    highlight = Color(0xFF2777D8),
    text = Color(0xFF25272B),
    subText = Color(0xFF777B82),
    labelAccent = Color(0xFF3A7EA6),
    background = Color(0xFFF8F8F8),
    card = Color(0xFFFCFCFC),
    subBackground = Color(0xFFEDEEF1),
    onHighlight = Color(0xFFFFFFFF),
    error = Color(0xFFCF4545),
    circleBackground = Color(0xF2FFFFFF),
    circleBackgroundStrong = Color(0xFFFFFFFF),
    circleBorder = Color(0x1A000000),
    dialogSurface = Color(0xFFFFFFFF)
)

/** 深色材质：卡片/控件 34% 中性灰覆盖（让 DWM 背景透出） */
private val DarkMaterial = DarkClassic.copy(
    background = Color.Transparent,
    card = Color(0xFF7C7C7C).copy(alpha = 0.34f),
    subBackground = Color(0xFF7C7C7C).copy(alpha = 0.34f),
    subText = Color(0xFFCCD0D5).copy(alpha = 0.91f),
    // 弹窗在用材质背景时保持不透明（否则文字读不清）
    dialogSurface = Color(0xFF3A3A3A)
)

/** 浅色材质：卡片/控件 72% 不透明底 */
private val LightMaterial = LightClassic.copy(
    background = Color.Transparent,
    card = LightClassic.card.copy(alpha = 0.72f),
    subBackground = LightClassic.subBackground.copy(alpha = 0.72f),
    dialogSurface = Color(0xFFF7F7F7)
)

val SaltDarkPalette: SaltPalette = DarkClassic
val SaltLightPalette: SaltPalette = LightClassic

/** 按 深浅 × 是否材质 取配色 */
fun saltPalette(dark: Boolean, material: Boolean): SaltPalette {
    if (dark) return if (material) DarkMaterial else DarkClassic
    return if (material) LightMaterial else LightClassic
}

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
