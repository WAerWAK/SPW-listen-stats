# Hook 目标定位手册

宿主更新后，下面这些混淆名大概率会变。本文记录**怎么重新找**，以及本次（SPW 1.19.0 / Steam）已经确认的结果。

## 已经确认的目标

```
className = androidx.compose.ui.ȫ        (U+022B)
methodName = Ϳ                          (U+037F)
inClass    = com.xuncorp.voxzen.ui.navigation.ComposableSingletons$AppNavigation_desktopKt
语义        = 插件配置页 ScreenRoute.ModConfigContent 的 Composable
```

完整签名：

```
public static final void Ϳ(
    com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent,
    androidx.compose.ui.શ,          // 配置页 ViewModel
    androidx.compose.runtime.Composer,
    int, int
)
```

调用点（inClass 内唯一）：

```
103: invokestatic androidx/compose/ui/ȫ.Ϳ:(Lcom/xuncorp/voxzen/ui/navigation/ScreenRoute$ModConfigContent;Landroidx/compose/ui/શ;Landroidx/compose/runtime/Composer;II)V
```

## 重新定位的步骤

### 1. 找到“哪个类实现了某个屏幕”

宿主的屏幕实现类被 R8 改成单字符名，但**字符串常量里保留了原始函数名**（例如 `ModConfigScreen`）。所以在 jar 里按字符串反查：

```powershell
$jar = 'D:\Games\Steam\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($jar)
$enc = [Text.Encoding]::GetEncoding(28591)   # Latin1：字节到字符一一对应
foreach ($e in $z.Entries) {
    if ($e.FullName -notlike '*.class') { continue }
    $s = $e.Open(); $ms = New-Object IO.MemoryStream; $s.CopyTo($ms); $s.Close()
    $t = $enc.GetString($ms.ToArray())
    if ($t -match 'ModConfigScreen|ModManagementScreen|WorkshopScreen') { $e.FullName }
}
```

### 2. 反汇编目标类

`javap` 直接用非 ASCII 类名当参数会被命令行编码搞坏，所以先提取成 ASCII 文件名：

```powershell
$target = 'androidx/compose/ui/ȫ.class'      # 换成上一步的结果
$e = $z.Entries | Where-Object { $_.FullName -eq $target }
$ms = New-Object IO.MemoryStream; $e.Open().CopyTo($ms)
[IO.File]::WriteAllBytes('D:\tmp\probe.class', $ms.ToArray())
```

```powershell
$javap = 'D:\Projects\.tools\jdk25\bin\javap.exe'   # 必须 JDK 25：宿主类是 class file 69
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
& $javap -p 'D:\tmp\probe.class'                    # 看方法名与签名
& $javap -p -c 'D:\tmp\probe.class'                 # 看字节码（找调用点）
Remove-Item Env:JAVA_TOOL_OPTIONS
```

要点：`-p` 必须加，`private static final` 的 Composable 也要看到。

### 3. 找调用方（inClass）

Hook API 要求 “`inClass` 内唯一匹配该 Composable 调用”。调用方通常是：
- `ComposableSingletons$AppNavigation_desktopKt`（导航树的 lambda 单例，绝大多数屏幕都在这）
- `AppNavigationKt` / `AppNavigation_desktopKt`（导航构建）
- 具体屏幕内部（组件级注入时）

反查方式：拿目标类的 UTF-8 字节序列在 jar 里搜引用者：

```powershell
$needle = $enc.GetString([Text.Encoding]::UTF8.GetBytes('androidx/compose/ui/ȫ'))
# 遍历所有 class，$t.Contains($needle) 的即是引用方
```

然后在引用方里 `javap -c` 搜 `invokestatic` / `invokeinterface` 到目标方法的行，确认**只出现一次**。

### 4. 组装三元组

| Hook API 参数 | 取值 |
| --- | --- |
| `className` | 被调用 Composable 所在的类（上一步的 `ȫ`） |
| `methodName` | 该函数名（`Ϳ`） |
| `parameterTypes` | UI Hook 只填**业务参数**，不含 `Composer`/`int`/`boolean`；若 `inClass` 内该调用唯一可传 `null` |
| `inClass` | 调用点所在类 |

源码里用 `\uXXXX` 写非 ASCII 名字，例如 `"androidx.compose.ui.\u022B"`、`"\u037F"`，避免源文件编码问题。

## 下一步可用的候选注入点

| 界面 | 特征字符串 | 备注 |
| --- | --- | --- |
| 插件配置页 | `ModConfigScreen` | 已确认，demo 用它 |
| 模组管理页 | `ModManagementScreen` | 命中 `androidx/compose/ui/ড়`、`ת`（含 `$ֈ`、`$ހ`） |
| 工坊页 | `WorkshopScreen` | 命中 `androidx/compose/ui/Ԁ`、`ʬ` |
| 侧栏/导航 | `ScreenRoute` + `NavigationBar` 相关 | 可用于加入口，需进一步定位 |

## 已知限制

- **注入位置由宿主布局决定**：`afterComposable` 只是“在调用点之后追加”，父容器若是 `Box` 会叠在页面上，若是 `Column` 则自然排在后面。实测确认前不要假设位置。
- **`replaceComposable` 不会渲染原组件**，除非能完整复刻，否则不要替换宿主实屏。
- **配置页 hook 是全局的**：`ModConfigContent` 是所有插件的配置页，注入内容会出现在任意插件的配置页。要限定到本插件，需要在注入内容里读取 `call.arguments`（其中含 `ScreenRoute.ModConfigContent` 数据）并判断插件 ID。
