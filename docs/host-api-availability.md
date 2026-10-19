# 宿主 API 可用性清单

> **这份文档解决的问题**：宿主（Salt Player for Windows）对 jar 做了 R8 裁剪，插件能用哪些 Compose API
> 只能靠试。本清单把已实测的结论固定下来，避免重复踩坑。
>
> 尤其注意 **§4 静默无效**：那几项的编译是通过的，但行为不对或不生效——只靠"能编译"判断可用性会出错。

## 0. 验证环境

| 项 | 值 |
|---|---|
| 宿主 | SPW 1.19.0（Steam 版） |
| 编译桩 | 宿主 fat jar（`app/ffmpeg-x64.dll`）作 `compileOnly`，API 面与运行时一致 |
| 工具链 | Kotlin 2.4.21 + Compose Multiplatform 插件 |
| 最后更新 | 2026-10-19（插件 1.2.0 开发期） |

## 1. 验证方式

**编译期**：写探针文件把可疑 API 全部写一遍，跑 `gradle compileKotlin`。
`Unresolved reference` / `Cannot access` = 被裁；编译通过 = 至少 API 存在。

**运行期**：编译通过 ≠ 生效。必须在面板里实际渲染确认（§4 的坑都是这么发现的）。

探针模板：

```kotlin
// 每次只留一个 API，编译错误会精确指向行号
@Composable
fun probeSomething() {
    Box(Modifier.blur(10.dp)) { }
}
```

## 2. 已验证可用（项目实际在用）

### 2.1 布局与容器

| API | 备注 |
|---|---|
| `Box` / `Column` / `Row` / `Spacer` | 正常 |
| `Modifier.fillMaxSize / fillMaxWidth / fillMaxHeight` | 正常 |
| `Modifier.width / height / size / padding / offset / weight` | 正常 |
| `Alignment` / `Arrangement` | 正常 |
| 自定义 `Layout { }` | 可用，`ScrollArea` 与 `place(0,0)` 修裁切都靠它 |
| `Modifier.onGloballyPositioned` | 可用（读取容器像素尺寸） |

### 2.2 背景 / 形状 / 边框

| 想要 | 状态 | 正确做法 |
|---|---|---|
| 纯色背景 | `Modifier.background` ❌ 被裁 | `Surface(color = ...) { }` |
| 圆角 / 圆形 | `CircleShape`、`RoundedCornerShape(...)`、`CornerSize(Dp)` ❌ 全被裁 | 自定义 `Shape`：`SaltDesign.ExactRoundedShape(radiusDp)`；圆形传 `-1f` |
| 边框 | `Modifier.border` ❌ 被裁 | `Surface(shape = ..., border = BorderStroke(1.dp, SolidColor(color)))` |
| 裁剪 | `Modifier.clip(shape)` ✅ 可用 | 但只能用自定义 `Shape`（内建 shape 全部被裁） |
| 阴影 | `Modifier.shadow(...)` ✅ 编译可用 | 运行效果未验证 |

> `RoundedCornerShape` 的所有便捷重载（`(Dp)`、`(Int)` 百分比）以及工厂 `CornerSize(Dp)` 都被裁，
> 只剩需要 4 个 `CornerSize` 参数的全参重载——而 `CornerSize` 是 interface 无法实例化，所以整体不可用。

### 2.3 绘制与图片

| API | 状态 |
|---|---|
| `Modifier.drawBehind { }` | ✅ 主要绘制手段 |
| `DrawScope.drawImage / drawRect / drawCircle / drawLine / drawPath` | ✅ |
| `ImageBitmap` | ✅ |
| `androidx.compose.foundation.Image` | ❌ 被裁 → 用 `drawBehind { drawImage(bitmap, dstSize = ...) }` |
| `org.jetbrains.skia.Image / Surface / toComposeImageBitmap` | ✅（封面 128px 降采样） |
| `BufferedImage.toComposeImageBitmap()` | ✅（Compose Desktop 扩展，磨砂背景用） |

### 2.4 图形效果

| API | 状态 |
|---|---|
| `Modifier.graphicsLayer { }` | ✅ 可用 `scaleX/scaleY/translationX/translationY/alpha/renderEffect` |
| `BlurEffect(rx, ry, TileMode)` 配 `graphicsLayer { renderEffect = ... }` | ✅ 编译可用 |
| `Modifier.blur(48.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)` | ✅ **已验证运行生效**（磨砂背景；`Unbounded` 避免边缘透明） |
| `Modifier.blur` 的默认 `BlurredEdgeTreatment.Rectangle` | ⚠️ 会把模糊后的边界裁掉，全屏背景需改用 `Unbounded` |

### 2.5 文本与字体

| API | 状态 |
|---|---|
| `Text` / `TextStyle` / `LocalTextStyle` | ✅ |
| `LocalTextStyle provides TextStyle(fontFamily = ...)` | ✅ 全局字体注入方式 |
| `FontFamily(Font(identity = "...", data = ByteArray))` | ✅ **唯一正确**的自定义字体构造方式 |
| `FontFamily(path)` / `FontFamily(File)` | ⚠️ 编译通过但静默无效，见 §4.1 |
| `java.awt.Font.createFont(TRUETYPE_FONT, InputStream)` | ✅ 字体字节预校验 |

宿主内置字体资源的读法：

```kotlin
val stream = com.xuncorp.voxzen.util.AppConfig::class.java.classLoader
    ?.getResourceAsStream("composeResources/voxzen.composeapp.generated.resources/font/misans.ttf")
```

### 2.6 输入与键盘

| API | 状态 |
|---|---|
| `OutlinedTextField` + `TextFieldState` + `TextFieldLineLimits` | ✅ 唯一可用的文本输入，**中文 IME 正常** |
| `Modifier.focusTarget` / `FocusRequester` | ✅ |
| `Modifier.onPreviewKeyEvent` | ✅ |
| `BasicTextField` | ❌ 被裁 |
| `Modifier.focusable()` | ❌ 被裁 |

### 2.7 指针交互

| API | 状态 |
|---|---|
| `Modifier.onPointerEvent(PointerEventType.Press / Release / Move / Enter / Exit / Scroll)` | ✅ 唯一的点击/悬停/滚轮手段 |

### 2.8 滚动

| API | 状态 |
|---|---|
| `LazyColumn` / `LazyRow` / `rememberLazyListState` | ❌ 全部被裁 |
| `Modifier.verticalScroll` / `horizontalScroll` / `rememberScrollState` | ❌ 全部被裁 |

替代：本项目手写的 `ScrollArea`（`onPointerEvent(Scroll)` 改偏移 + `graphicsLayer { translationY }` + `drawBehind` 自绘滚动条）。

### 2.9 Material3

| API | 状态 |
|---|---|
| `MaterialTheme` / `darkColorScheme` | ✅ |
| `Surface(color =, shape =, border =)` | ✅ |
| `Card`（含 `onClick`） | ✅ 需 `@OptIn(ExperimentalMaterial3Api::class)` |
| `DropdownMenu` / `DropdownMenuItem` | ✅ 编译可用（尚未在项目中用于生产） |

### 2.10 状态与副作用

`remember` / `mutableStateOf` / `derivedStateOf` / `LaunchedEffect` / `DisposableEffect` /
`staticCompositionLocalOf` / `CompositionLocalProvider` —— 全部正常。

### 2.11 JDK / AWT

| API | 状态 |
|---|---|
| `java.awt.Robot().createScreenCapture(Rectangle)` | ✅（磨砂背景截图） |
| `java.awt.Window.getWindows()` | ✅（定位宿主窗口） |
| `java.awt.image.BufferedImage` + `Graphics2D` 缩放 | ✅ |

## 3. 被裁清单（编译期直接失败）

```
androidx.compose.foundation.Image
androidx.compose.foundation.background          (Modifier.background)
androidx.compose.foundation.border              (Modifier.border)
androidx.compose.foundation.clickable           (Modifier.clickable)
androidx.compose.foundation.horizontalScroll
androidx.compose.foundation.verticalScroll
androidx.compose.foundation.rememberScrollState
androidx.compose.foundation.lazy.*              (LazyColumn / LazyRow / rememberLazyListState)
androidx.compose.foundation.shape.CircleShape
androidx.compose.foundation.shape.RoundedCornerShape   (所有便捷重载；CornerSize 工厂也缺)
androidx.compose.foundation.text.BasicTextField
androidx.compose.ui.focus.focusable
```

## 4. 静默无效 / 陷阱（⚠️ 编译通过但行为不对）

### 4.1 `FontFamily(path)` 静默回退系统字体

```kotlin
// ❌ 不报错，但渲染的是系统字体
FontFamily(Font(File("C:/xxx.ttf")))

// ✅ 正确：字节数组重载
FontFamily(Font(identity = "my-font", data = bytes))
```

**这个坑曾导致错误结论**：看到"自定义字体与系统字体渲染完全一致"，判定为"宿主裁掉了字体解析链路"，
实际是重载用错。教训：**行为与预期不符时，先怀疑调用方式，再怀疑环境能力**。

### 4.2 `Modifier.wrapContentHeight()` 的垂直居中

编译可用，但会让内容在父容器里垂直居中，而不是顶部对齐——表现为内容顶部被裁掉。
修复：用自定义 `Layout` 显式 `place(0, 0)`。

### 4.3 `onPointerEvent` 回调在首次组合时冻结

```kotlin
// ❌ 回调里用的是组合时的旧值
Box(Modifier.onPointerEvent(PointerEventType.Scroll) {
    offset -= delta          // offset 是首次组合时的快照
})

// ✅ 回调内实时读 State
Box(Modifier.onPointerEvent(PointerEventType.Scroll) {
    offsetState.value -= delta
})
```

同理适用于 `remember { }` 内捕获的 lambda。滚动跳动、悬停失灵两个 bug 都是这个原因。

### 4.4 `Modifier.shadow` 的运行效果未验证

`Modifier.shadow(...)` 编译通过，但 Skiko 是否真的画阴影需要渲染确认。

> 注：本项目的阴影全部走 `Surface(shadowElevation = ...)`，已确认生效，未使用 `Modifier.shadow`。
> 而 `Modifier.blur` 曾经也被列入本节，现已实测生效，移入 §2.4。

### 4.5 Kotlin 块注释支持嵌套

注释里写 `daily/*.jsonl` 会开启第二层块注释，导致编译时"注释未闭合"。

### 4.6 inline 集合扩展 + `@Composable` 可能触发编译器崩溃

Kotlin 2.4.21 + Compose 插件 in-process 编译时，`firstOrNull { }` / `map { }` / `filter { }`
等 inline 函数与 `@Composable` 混用曾导致 codegen 崩溃。本项目约定：循环一律手写 `for`，
排序一律显式 `Comparator`。

## 5. 误判记录（供后来者避坑）

| 事项 | 曾经的结论 | 实际情况 | 根因 |
|---|---|---|---|
| 字体同步 | "宿主裁掉了字体解析链路，不可行" | `Font(identity, data = ByteArray)` 一直可用 | 用了路径版 `FontFamily(path)`，静默失败被误读为环境限制 |
| 面板内搜索 | "输入法不可用，做不了搜索" | `OutlinedTextField` + `TextFieldState` 完全可用（中文正常） | 只验证了 `BasicTextField` / `focusable` 被裁，就推广到"所有输入不可行" |
| `Modifier.blur` | "被 R8 裁掉" | `Modifier.blur` 编译并生效（48dp 高斯，磨砂背景） | 未复测，直接采信早期结论 |
| 竞品字体实现 | 未发现 | 竞品 `ui/HostFont.kt` 40 行就写清了 | 读了竞品的 DB 和页面代码，漏读了这个文件 |

**通用教训**：
1. 一条调用路径失败 ≠ 能力不存在。至少验证 2–3 条路径再下结论。
2. 编译失败（被裁）和静默失败（行为不对）是两种完全不同的问题，处置方式也不同。
3. 有竞品 / 参考实现时，**整个仓库的文件列表都要过一遍**，不能只读"看起来相关"的几个。
