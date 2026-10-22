# Hook 目标定位手册

宿主更新后，下面这些混淆名大概率会变。本文记录**怎么重新找**，以及已确认的结果。

代码里通过 `ui/HostUiTargets.kt` 按宿主版本选择目标（`PluginContext.spwVersion`），
调用点不再写死类名。新增宿主版本时在那里加一条分支即可。

## 已确认的注入目标

| 用途 | SPW 1.19.0 | SPW 1.19.1 | 说明 |
| --- | --- | --- | --- |
| 面板 / 材质桥接层 inClass | `androidx.compose.ui.ư` (`\u06C4`) | `androidx.compose.ui.у` (`\u0443`) | `AppNavigationKt.AppNavigation` 的调用点，两版都唯一 |
| 标题栏按钮 className | `androidx.compose.ui.Ӵ` (`\u04F4`) | `androidx.compose.ui.๊` (`\u063A`) | 标题栏里的 DebugCaptionButton 模板 |
| 标题栏按钮 inClass | `androidx.compose.ui.ȅ` (`\u0205`) | `androidx.compose.ui.๊` → 见下 | 1.19.1 改为 `androidx.compose.ui.๏` (`\u09F1`) |

方法名两版都是 `\u037F`（`Ϳ`）。

### 1.19.1 的两处关键差异

1. **标题栏容器只接受原生 caption button**
   自绘组件（`Box { ... }`）插进去会被排到容器行首（表现为按钮跑到左上角或侧边栏顶部）。
   必须反射调用宿主 caption 组件：
   `com.xuncorp.voxzen.ui.component.ԩ.Ϳ(Function0, String, Modifier, boolean, String, float, Function3<BoxScope>, Composer, int, int)`，
   把 hook 回调里的 `currentComposer` 传进去。

2. **`META-INF/runtime.kotlin_module` 被清空**
   宿主把该模块压成了 24 字节空壳，Kotlin 编译器因此看不到 `androidx.compose.runtime` 的顶层函数
   （`remember` / `mutableStateOf` / `LaunchedEffect` …），插件源码整体无法编译。
   解决：`tools/gen-runtime-module-patch.py` 重新生成包映射，产出 `libs/runtime-module-patch.jar`
   作为 compileOnly 的前置依赖（不打包进插件）。

## 重新定位的步骤

### 1. 找「哪个类调用了某个方法」

用 Python 解析 class 文件常量池里的 Methodref（比字符串搜索准）：

```python
# 解析 constant_pool，找 owner=? 且 name=? 的 Methodref
# 输出所有引用该方法的 class 文件名（ascii 转义显示非 ASCII 名）
```

注意：**不要用 PowerShell 的 `Select-String` 直接搜 `\u063A` 这类转义文本**，
javap 输出里是真实字符，必须用 `[char]0x063A` 拼字符串来匹配。

### 2. 反汇编目标类

`javap` 直接用非 ASCII 类名当参数会被命令行编码搞坏，先把 class 提取成 ASCII 文件名：

```powershell
$target = 'androidx/compose/ui/<类名>.class'
$e = $z.Entries | Where-Object { $_.FullName -eq $target }
[IO.File]::WriteAllBytes('D:\tmp\probe\probe.class', $eBytes)
& 'D:\Projects\.tools\jdk25\bin\javap.exe' -p -c 'D:\tmp\probe\probe.class'
```

要点：`-p` 必须加（private 的 Composable 也要看到）；环境变量设 `JAVA_TOOL_OPTIONS=-Dstdout.encoding=UTF-8`。

### 3. 组装三元组

| Hook API 参数 | 取值 |
| --- | --- |
| `className` | 被调用 Composable 所在类 |
| `methodName` | 该函数名 |
| `parameterTypes` | 只填**业务参数**（不含 `Composer` / `int`）；用于在多个重载中消歧，无重载可传 `emptyList()` |
| `inClass` | 调用点所在类，且该类内该调用必须**唯一**（否则注册报歧义） |

## 已知限制

- **inClass 内必须唯一**：标题栏主类 `androidx.compose.ui.ע` 里 caption button 有 3 处同签名调用，
  右侧按钮组 `androidx.compose.ui.ȅ` 里图标按钮有 2 处同签名调用 —— 这些都无法用 `afterComposable` 精确定位。
  只有 `DebugCaptionButton`（1.19.1 的 `\u063A`）在标题栏里是唯一调用，所以标题栏入口挂它。
- **ComposableSingletons 类里的插入可能不渲染**：`ȅ` 的缓存 lambda 内 afterComposable 注册成功但内容不进组合，
  这是本次排查花时间最多的坑。
- `appendContent` 只能包装**非 composable** 的 `Function1` DSL 参数，标题栏几层都是 composable lambda，用不了。
