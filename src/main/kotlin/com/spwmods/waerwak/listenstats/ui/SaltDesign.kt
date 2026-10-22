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
    /** 面板底色（不透明兜底色）：材质模式下用于桥接层不可用时的回退 */
    val background: Color,
    val subBackground: Color,
    /** 分组卡片的 1px 描边（8 种组合都有，颜色按宿主实测分档） */
    val cardBorder: Color = Color.Transparent,
    /**
     * 面板根是否透明
     *
     * true  = 材质样式（云母 / 亚克力）：根 Surface 透明，露出宿主 DWM 材质
     * false = 经典样式，或宿主自己在材质上盖了近不透明的着色层（云母 Alt）：
     *         根 Surface 直接用 [background] 实色
     */
    val transparentPanel: Boolean = false,
    val onHighlight: Color,
    val error: Color,
    val circleBackground: Color,
    val circleBackgroundStrong: Color,
    val circleBorder: Color,
    /** 弹窗/浮窗/下拉框的底：始终不透明（无遮罩时靠它保证可读性） */
    val dialogSurface: Color
)

/*
 * 经典（椒盐经典）样式分层：
 *   窗口/面板底 = 深 #232323 / 浅 #F8F8F8
 *   内容区卡片  = 深 #2A2A2A / 浅 #FBFBFB（宿主把内容区做成一张大圆角卡片）
 *   卡片边框    = 1px 实色（深 #3B3B3B / 浅 #E8E8E8）
 * 插件面板铺满内容区，所以根底色用窗口色、分组卡片用内容区色，才和宿主的分层一致。
 */
private val DarkClassic = SaltPalette(
    isDark = true,
    highlight = Color(0xFF79B5FF),
    text = Color(0xFFEBEEF1),
    subText = Color(0xFFA0A3AB),
    labelAccent = Color(0xFF8FC7E8),
    background = Color(0xFF232323),
    subBackground = Color(0xFF2A2A2A),
    // 经典样式的 1px 淡边框：深色背景 #232323 上提亮 8% 的实色
    cardBorder = Color(0xFF3B3B3B),
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
    subBackground = Color(0xFFFBFBFB),
    // 经典样式的 1px 淡边框：浅色背景 #F8F8F8 上压暗 6% 的实色
    cardBorder = Color(0xFFE8E8E8),
    onHighlight = Color(0xFFFFFFFF),
    error = Color(0xFFCF4545),
    circleBackground = Color(0xF2FFFFFF),
    circleBackgroundStrong = Color(0xFFFFFFFF),
    circleBorder = Color(0x1A000000),
    dialogSurface = Color(0xFFFFFFFF)
)

/*
 * 材质样式保留「透出 DWM 材质」的做法：面板根透明，卡片用半透明覆盖层压上去。
 * alpha 全部按宿主截图反推（背景 → 宿主分组卡片）：
 *   深色云母  #1E2237 → #2A2F3B（+12）
 *   深色亚克力 #3C3F46 → #4B4E54（+16）
 *   浅色云母  #F6F8FB → #FDFDFE（+7）
 *   浅色亚克力 #BCBDC2 → #CBCDD0（+15）
 * 宿主的分组卡片都带 1px 淡边框，材质样式统一给 cardBorder。
 */

/** 深色云母：14% 中性灰覆盖 */
private val DarkMica = DarkClassic.copy(
    background = Color(0xFF1E2237),
    subBackground = Color(0xFF7C7C7C).copy(alpha = 0.14f),
    cardBorder = Color(0xFF303447),
    transparentPanel = true,
    subText = Color(0xFFCCD0D5).copy(alpha = 0.91f),
    // 弹窗在用材质背景时保持不透明（否则文字读不清）
    dialogSurface = Color(0xFF3A3A3A)
)

/** 深色亚克力：25% 中性灰覆盖 */
private val DarkAcrylic = DarkClassic.copy(
    background = Color(0xFF3C3F46),
    subBackground = Color(0xFF7C7C7C).copy(alpha = 0.25f),
    cardBorder = Color(0xFF595C62),
    transparentPanel = true,
    subText = Color(0xFFCCD0D5).copy(alpha = 0.91f),
    dialogSurface = Color(0xFF3A3A3A)
)

/** 浅色云母：87% 白覆盖（宿主卡片 #FDFDFE，边框 #E3E3E4） */
private val LightMica = LightClassic.copy(
    background = Color(0xFFF6F8FB),
    subBackground = Color.White.copy(alpha = 0.87f),
    cardBorder = Color(0xFFE3E3E4),
    transparentPanel = true,
    dialogSurface = Color(0xFFF7F7F7)
)

/** 浅色亚克力：背景 #BCBDC2 → 卡片 #CBCDD0，26% 白 */
private val LightAcrylic = LightClassic.copy(
    background = Color(0xFFBCBDC2),
    subBackground = Color.White.copy(alpha = 0.26f),
    cardBorder = Color(0xFFADAFB3),
    transparentPanel = true,
    dialogSurface = Color(0xFFF7F7F7)
)

/*
 * 云母 Alt 和云母不是同一块材质底：宿主在 DWM 材质上又盖了一层近不透明的着色
 * （深色 #2A2A2A → 内容区 #071026，浅色 → #E4E9F1）。
 * 所以这里用不透明根 + 实色，否则会透出 DWM 原来的灰底，与宿主差一大截。
 * 注意卡片是比背景「亮」（+13），不是更暗。
 */

/** 深色云母 Alt：背景 #071026 → 卡片 #141C31，6% 白；边框 #293145 */
private val DarkMicaAlt = DarkClassic.copy(
    background = Color(0xFF071026),
    subBackground = Color.White.copy(alpha = 0.055f),
    cardBorder = Color(0xFF293145),
    transparentPanel = false,
    subText = Color(0xFFCCD0D5).copy(alpha = 0.91f),
    dialogSurface = Color(0xFF3A3A3A)
)

/** 浅色云母 Alt：背景 #E4E9F1 → 卡片 #F4F6FA，55% 白 */
private val LightMicaAlt = LightClassic.copy(
    background = Color(0xFFE4E9F1),
    subBackground = Color.White.copy(alpha = 0.55f),
    cardBorder = Color(0xFFD3D8E0),
    transparentPanel = false,
    dialogSurface = Color(0xFFF7F7F7)
)

val SaltDarkPalette: SaltPalette = DarkClassic

/** 按 深浅 × 是否材质 × 窗口样式（亚克力 / 云母）取配色 */
fun saltPalette(dark: Boolean, material: Boolean, windowStyle: String = "WindowsMica"): SaltPalette {
    if (!material) return if (dark) DarkClassic else LightClassic
    if (windowStyle == "WindowsAcrylic") return if (dark) DarkAcrylic else LightAcrylic
    // 云母 Alt 的材质底色与云母不同（浅色更暗、深色更蓝），单独一档
    if (windowStyle == "WindowsMicaAlt") return if (dark) DarkMicaAlt else LightMicaAlt
    return if (dark) DarkMica else LightMica
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
