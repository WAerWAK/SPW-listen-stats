# SPW UI 逆向规范（SPW 1.19.0 / Steam）

本文是把 SPW 的界面实现逐层反编译后的结论汇总，用于在插件里"按 SPW 风格精准复刻"。

所有结论都可在本机 `app\ffmpeg-x64.dll`（fat jar）上复现，命令见文末。

---

## 1. 技术栈

| 层 | 实现 |
| --- | --- |
| UI 框架 | Compose Multiplatform（Desktop / Skia） |
| 设计系统 | **SaltUI**（`Moriafly/SaltUI`，Apache-2.0）+ SPW 定制 |
| 毛玻璃 | **Haze**（`dev.chrisbanes.haze`，类名未混淆） |
| 窗口装饰 | Nucleus（`dev.nucleusframework.window`）+ 自绘标题栏 |
| 字体 | **MiSans**（`font/misans.ttf`，打包在 composeResources）+ 系统回退 |
| 资源 | Compose Resources（`composeResources/voxzen.composeapp.generated.resources/**`） |

---

## 2. 配色（反编译实测值）

### 2.1 强调色

`com.xuncorp.voxzen.ui.theme.Ϳ`（单例，`public static long Ϳ()` 返回 Color）：

```
0xFF4CC2FF     ← SPW 强调色（亮蓝偏青）
```

> 注意：SaltUI 默认是 `#0088FF`，SPW **改成了 `#4CC2FF`**。这是"看起来不像"的首要原因。

### 2.2 主题包装 `com.xuncorp.voxzen.ui.theme.Ԩ`

原类名 **`AppTheme`**（字符串常量里可见 `com.xuncorp.voxzen.ui.theme.AppTheme`），签名：

```kotlin
@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit)
```

字节码里的 Color 常量：

| 值 | 推测用途 |
| --- | --- |
| `0xFF1E262D` | 深色模式面板 / 浮层 / 卡片底色（带蓝调） |
| `0xFFFAFAFA` | 浅色模式面板底色 |
| `0x99FFFFFF` | 浅色分层（白 60%） |
| `0xB9FFFFFF` | 浅色分层（白 73%） |
| `0x90000000` | 深色遮罩（黑 56%） |

### 2.3 窗口样式 `SpwWindowStyle`

6 个枚举值（常量名被混淆），`uiName` 里能读到 `Vibrancy` 等字样，说明样式包含
「纯色 / 壁纸 / 半透明（Vibrancy）」等组合。背景由
`AppConfig.wallpaperFilePath` + `SpwWindowStyle` 共同决定。

---

## 3. 布局与尺寸（SaltUI 桌面端）

`SaltDimens`（desktop actual）与 `SaltTextStyles`（desktop actual）反编译值：

| 项 | 值 |
| --- | --- |
| item 高度 | **32dp**（桌面；Android 56 / iOS 52） |
| item 图标 | **20dp** |
| 内边距 padding | **16dp** |
| 次级内边距 subPadding | **12dp** |
| 圆角 small / medium / large | **4 / 6 / 10 dp** |
| 正文 | 14sp |
| 次文本 | 12sp |
| 段落 | 16sp / 1.5em |
| 大标题 | 24sp SemiBold |
| 分组容器 | `RoundedColumn`：圆角 large(10dp) + `subBackground` + 16/12 padding，组间 16dp |

---

## 4. 字体（插件做不到，但要知道）

```java
AppTheme.getMiSansRegularFont()          // 从 composeResources 读 font/misans.ttf
composeResources/voxzen.composeapp.generated.resources/font/misans.ttf
```

- SPW 默认字体是 **MiSans**（含中/日/韩/英），不是 Inter、也不是系统雅黑
- 加载走 **Compose Resources**（`Res.font.misans`）
- **插件无法复用它**：资源路径在宿主 classpath 内，且宿主的 Compose 字体解析链路被 R8 裁剪
  （实测：自定义字体 / 内置 Inter / 强制 null 三者渲染完全一致）

结论：插件的字体 = 系统字体（Windows 上是微软雅黑），这是当前唯一可选项。

---

## 5. 背景与"毛玻璃"

| 元素 | SPW 的做法 |
| --- | --- |
| 窗口背景 | 壁纸图片（`AppConfig.wallpaperFilePath`）或纯色，由 `SpwWindowStyle` 决定 |
| 侧栏 / 播放条 / 面板 | **Haze 模糊**（`dev.chrisbanes.haze`），SaltUI 的 `SaltSurface` 封装 |
| 分层 | 叠加 `#99FFFFFF` / `#B9FFFFFF`（浅色）或 `#90000000`（深色） |

插件方面：
- Haze 的**类名可读**（`HazeState`、`BlurConfiguration`、`CupertinoMaterials`…），
  但需要拿到宿主已创建的 `HazeState`（藏在 SaltUI 的 `LocalHazeState`，方法名混淆 `material.Ϳ.Ϳ()`）
- 因此真模糊暂不可用；插件用"实心卡片 + 1dp 高光边框 + 阴影"近似

---

## 6. 组件结构（SaltUI 惯例）

```
Scaffold(
  contentPadding = PaddingValues(...)      // 页面级 16dp
) {
  RoundedColumn {                          // 分组：圆角 10dp + subBackground
    Item(title = "...", summary = "...")   // 行高 32dp，图标 20dp
    Divider()
    ItemSwitcher(...)                      // 开关行
  }
}
```

SPW 的插件配置页 `ModConfigScreen` 就是这个结构：
- `ModConfigScreen.kt:49`（入口）、`:80`（Loading）、`:95`（Content）
- 布局参数**不写在字节码里**，运行时从 `SaltDimens` 读

---

## 7. 插件侧能用 / 不能用

| 能力 | 状态 |
| --- | --- |
| 强调色、面板色、分层色 | ✅ 已接入（`SaltDesign` 使用实测值） |
| 尺寸/字号/圆角 | ✅ 已接入（SaltUI desktop 值） |
| 深浅色跟随宿主 | ✅ `AppConfig.isDarkTheme` |
| 自定义字体 / MiSans | ❌ 宿主裁掉字体渲染链路 |
| 壁纸背景 | ⚠️ 可读 `AppConfig.wallpaperFilePath`，但 Compose 图片加载与绘制 API 多被裁剪，需实测 |
| Haze 真模糊 | ⚠️ 需要混淆的 `LocalHazeState`，脆弱 |
| SaltUI 组件直调 | ❌ 类名/方法名全混淆（`material.Ϳ` = SaltSurface） |

---

## 8. 复现命令

```powershell
$jar = 'D:\Games\Steam\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll'
$javap = 'D:\Projects\.tools\jdk25\bin\javap.exe'   # 宿主是 class file 69，需 JDK 25

# 颜色常量（AppTheme）
# 先按"含 AppTheme 字符串"提取 theme 包下的类为 ascii 文件名，再：
& $javap -c -p themeN.class | Select-String '// long \d+l'

# 字体线索
& $javap -c -p themeN.class | Select-String 'font|misans|Font'

# SaltUI 尺寸/字号（源码，从 GitHub 取）
# https://raw.githubusercontent.com/Moriafly/SaltUI/main/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/SaltDimens.kt
# https://raw.githubusercontent.com/Moriafly/SaltUI/main/ui2/src/desktopMain/kotlin/com/moriafly/salt/ui/SaltShapes.desktop.kt
```
