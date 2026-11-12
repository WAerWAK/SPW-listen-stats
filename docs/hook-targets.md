# Hook 目标定位手册

宿主每次更新都会重排混淆名（连 patch 版本也会变）。1.5.0 起不再依赖"版本 → 硬编码类名"，
而是**从宿主字节码按结构发现**，失败时才回退到版本表，都失败就不注入（采集照常）。

- 代码：`"ui/discovery/"` 下的 `"HostBytecodeIndex"` / `"HostUiDiscovery"` / `"HostTargetResolver"`；版本表在 `"ui/HostUiTargets.kt"`；句柄所有权在 `"ui/HookHandles.kt"`
- 依赖：宿主自带的 `net.bytebuddy.jar.asm`（不打包、不需要新增依赖）
- 缓存：数据目录 `"host-targets.txt"`，key = 宿主 jar 绝对路径 + SHA-256 + 插件版本，`format=4`
- 诊断：数据目录 `hook-diag.txt`，每次注入写 `resolve source=scan|cache|table`、`install ok handles=N …warnings=N via=… ms=…`

## 0. 句柄所有权与可选组（1.5.1.1 起）

- **只有 close 成功才算释放**：`HookHandles` 逆序关闭，失败的句柄留在集合里；`HookRegistrationFailure.rollbackComplete=false`
  时调用方在信息页/日志提示“重启 SPW 后再启用”，并阻止再次注册（同一 call site 重复注册不会报错，只会叠渲染）。
- **可选组降级**：结构发现里只有面板（AppNavigation 调用点）是必需项；标题栏与内层材质点失败只写 `warnings`，
  面板/浮钮照常注入。`HostUiTargets.titlebarAvailable=false` 时 `TitlebarButton.register()` 直接返回 null。
- **不重试的情形**：权限不足/被收回、线程中断、任务取消（`HookFailurePolicy.noRetry`），避免反复扫码。
- 缓存格式 3 修正了两个 1.5.1 的解析 bug（导致缓存一直不命中、命中后又丢内层点位）：
  `caption.control` 行只有 3 段；`layer/content` 行是 4 段（旧代码分别要求 4/5 段）。
- **缓存只在“扫描 + 完整安装”后写入**（`HostTargetResolver.saveCache`）：缺标题栏/材质时不写，
  避免下次启动命中一份不完整目标。
- **会话代号**（`ui/HostInstallSession.kt`）：每次启用分配一个代号，`stop()` 递增；
  扫描前后与注册前各校验一次，停用后迟到的扫描结果不会把 hook 装进去。
- **注册与释放都在宿主界面线程（AWT EDT）**：扫描仍在后台线程；安装等待 EDT 最多 5 s，
  超时写 `edt-timeout` 并放弃本次注册（不影响听歌记录）。停用不等待 EDT。
- **缓存命中先做反射预检**（标题栏 caption 方法 + 内层材质方法）：预检失败写
  `cache-preflight-failed` 并丢弃缓存全扫一次（只重试一次）。

## 0.1 入口开关与配置直达（1.5.1.3+）

- **入口开关**：`showFloatingButton` / `showTitlebarButton` 存在 `config.json`（宿主配置页 switch），
  插件用 `ui/EntrySwitches.kt` 跟随：配置变更监听（有就即时）+ 600 ms 轮询兜底，
  状态只在 EDT 更新。**开关只切绘制，hook 常驻**（不因开关反复注册/卸载）。
- **旧值迁移**：`ui-state.json` 的 `entryEnabled` 只在 config 里还没有新键时迁移一次。
- **面板直达配置页**：`ui/HostConfigRoute.kt` 反射构造 `ScreenRoute$ModConfigContent(pluginId)`，
  用 `NavigationHook` 捕获的 Navigator 实例调 `navigate`；任一前提不满足就提示，不回退到自开窗口。
- **诊断报告**：`DiagReport.write()` 写 `诊断报告-<时间戳>.txt`（版本/权限/注入态/计时异常/
  `hook-diag.txt` 与 `listen-stats.log` 尾部）；**不含**歌名/歌手/音频路径（回归测试会断言）。

## 0.2 左侧栏入口（1.5.2.1，可选组）

- 发现（`ui/HostSidebar.kt`，纯结构判据）：
  1. `routeRow`：3 参 composable（route + Composer + int），方法体里同时调用原生侧栏行（11 参）与 `stringResource`；
  2. `row`：11 参静态 composable，前 5 参 = String/boolean/Modifier/boolean/Modifier，后 4 参 = Function3/Composer/int/int；
  3. `routeGetter`：routeRow 里对 route 参数的零参 getter（判断 MusicLibrary）；
  4. `accessor`：routeRow 同类的公开静态包装（没有就直接用 routeRow）；
  5. `lazyCaller`：调用 accessor 的 `Function4.invoke`（4 个 Object 参数、返回 Object）；
  6. `style`：routeRow 同类里 `CompositionLocalProvider(ProvidedValue, Function2, Composer, int)` 的唯一调用点。
- 注册（`ui/SidebarEntry.kt`）：`afterComposable` priority **25**（不被外层 replace 影响），
  只对 `ScreenRoute$MusicLibrary` 绘制；行本体直接反射调用宿主原生 11 参行，传入
  `("统计", active, null, false, modifier, null, null, icon, composer, 0, 108)`。
- 选中态抑制：统计页打开时在宿主唯一 Provider 调用点提供"未选中副本"的 `SidebarItemStyle`
  （反射 10 参构造器），统计行内用 `LocalUnmaskedSidebarStyle` 恢复原样式；构造失败就只保留入口（可能双高亮）。
- 入口互斥：`EntrySwitches.showSidebar`（可用且开启）优先，标题栏按钮自动让位；
  两者都不可用时只能从配置页/浮钮打开。开关项 `showSidebarEntry` 默认关。

## 1. 结构判据（自动发现规则）

只有"调用关系 + 描述符 + 唯一性"，没有任何混淆名：

| 用途 | 判据 | 唯一性要求 |
| --- | --- | --- |
| 面板调用点 `pageCaller` | 调用了 `AppNavigationKt.AppNavigation(Composer, I)V` 的**外部类** | 外部调用类唯一，且该类内该调用点计数 = 1 |
| 标题栏行 `titlebarCaller` | 调用"标题栏零参 composable"的类 | 唯一 |
| 标题栏模板 `debugCaption` | 该行里**排在标题栏零参 composable 前一个**的零参 composable | 唯一，且其调用类 == 标题栏行 |
| caption 控件 `captionClass` | 值类型 10 参静态 composable（内部转调 9 参重载），签名含 `Function0;String;Modifier;Z;String;F;Function3;Composer;II` | 唯一 |
| 材质 Layer（B2） | 面板调用类里 `(Modifier,Z,Function3,Composer,II)V` 的调用，且方法体内自调同描述符 + 调 `DrawModifierKt` | 唯一 |

> 任何一步不唯一 → `"…：期望唯一匹配，实际 N"` → 本次不注入 UI，日志与信息页显示原因。

## 2. 当前目标（SPW 1.19.3，实测）

| 用途 | 值 |
| --- | --- |
| 面板调用点 (inClass) | `androidx.compose.ui.Ɏ`（`\u024e`） |
| 标题栏行 (inClass) | `androidx.compose.ui.ઈ`（`\u0a88`） |
| 标题栏模板 (className) | `androidx.compose.ui.ݕ`（`\u0755`），方法名 `Ϳ`（`\u037f`） |
| caption 控件 | `com.xuncorp.voxzen.ui.component.ԩ`（`\u0529`），10 参 `Ϳ` |
| 材质 Layer | `androidx.compose.ui.ՙ.Ϳ(Modifier,Z,Function3,Composer,II)V`（`\u0559`），caller = 面板调用点（**已实现但默认关闭**，见下） |
| 导航 hook | `Navigator` / `navigate` / `replace` / `forward` / `back` 未变 |

`hook-diag.txt` 成功样本（1.5.1.1 起多了 `handles` / `material` / `warnings`）：

    resolve  source=cache  session=1  page=androidx.compose.ui.Ɏ  titlebar=androidx.compose.ui.ઈ  caption=androidx.compose.ui.ݕ  …\ffmpeg-x64.dll
    material  inner  androidx/compose/animation/SharedTransitionScopeKt#SharedTransitionLayout @ com/xuncorp/voxzen/ui/navigation/AppNavigationKt
    install  ok session=1 handles=4 layer=false material=true nav=true titlebar=true titlebarErr=- warnings=0 via=cache ms=454

停用/启用时应看到成对的：`cancel-pending session=…`（停用第一步）→ `release session=… ok`（句柄已全部释放）。

> **Layer 点位为什么默认关闭**：包住宿主 Layer 后，我们的面板会被放到窗口原点（盖住顶栏），
> 且隐藏宿主内容会连侧栏/顶栏一起隐藏；旧点位（替换 `AppNavigation` 调用点）才能让面板落在内容区。
> 代码（`HostLayerStage` / `resolveLayerMethod`）与发现结果（`resolved.layer`）都保留，
> 打开开关是 `OverlayInjection.USE_LAYER_INJECTION`。重新打开前需要先解决"只隐藏在内容区"的方案，
> 并和"多插件共存"一起验证。

## 2.5 内层材质点位（多插件共存用，1.5.1 起）

替换 AppNavigation **根**会被其它插件用更高的 `replaceComposable` priority 抢占（宿主只渲染优先级最高的那一个，
不报错、不链式）。SPW-Lyrics 3.4.0 用 priority=80 抢占同一处；本插件默认 priority=50 会静默失效 →
材质层不组合 → 面板变实色。因此材质隐藏改挂 **AppNavigation 内部**、对方不 hook 的调用点：

| 优先级 | 目标 | 1.19.3 值 | 判据 |
|---|---|---|---|
| 首选 | SharedTransitionLayout | `androidx.compose.animation.SharedTransitionScopeKt.SharedTransitionLayout(Modifier,Function3,Composer,II)V` @ inClass `com.xuncorp.voxzen.ui.navigation.AppNavigationKt` | AppNavigationKt 类内**唯一** SharedTransitionLayout 调用 |
| 备用 | NavDisplay | `androidx/navigation3/ui/NavDisplayKt.NavDisplay(...)` @ inClass AppNavigationKt | 类内唯一 NavDisplay 调用（SharedTransitionLayout 判据失效时） |

- 面板入口仍是 `afterComposable(AppNavigation, inClass=pageCaller)`：AFTER 与 REPLACE 独立、永远执行，
  所以即使根替换被抢，面板仍然出现（只是材质曾失效）。
- 注入成功时 `hook-diag.txt` 写 `material  inner <owner>#<method> @ <caller>`；失败回退旧点位写 `material  appnav`。
- 旧根替换仅在内层点发现失败时使用（单插件场景同样走内层点，行为一致）。

## 3. 历史目标（版本表回退用）

| 用途 | SPW 1.19.0 | SPW 1.19.1 |
| --- | --- | --- |
| 面板 / 材质 inClass | `androidx.compose.ui.ư`（`\u06c4`） | `androidx.compose.ui.у`（`\u0443`） |
| 标题栏模板 className | `androidx.compose.ui.Ӵ`（`\u04f4`） | `androidx.compose.ui.๊`（`\u063a`） |
| 标题栏 inClass | `androidx.compose.ui.ȅ`（`\u0205`） | `androidx.compose.ui.๏`（`\u09f1`） |

1.19.0 走自绘标题栏按钮；1.19.1+ 必须**反射调用宿主 caption 组件**（否则按钮会被排到行首）：
`com.xuncorp.voxzen.ui.component.ԩ.Ϳ(Function0, String, Modifier, boolean, String, float, Function3<BoxScope>, Composer, int, int)`，
把 hook 拿到的 `currentComposer` 传进去。

1.19.1 起宿主还把 `META-INF/runtime.kotlin_module` 压成 24 字节空壳，
Kotlin 编译器看不到 `androidx.compose.runtime` 的顶层函数；由 `tools/gen-runtime-module-patch.py`
生成 `libs/runtime-module-patch.jar` 作为 compileOnly 前置依赖（不打包）。

## 4. 宿主升级后的流程（5 步）

1. **离线验证**：`gradle runDataTests`。其中「宿主界面结构化发现与 1.19.3 目标一致」会在新宿主 jar 上
   跑一遍发现，并断言与本文件 §2 的值一致；不一致会直接 FAIL 并打印实际值。
2. **部署前门禁**：`gradle probeHookTargets`（默认扫 `build/host/spw-host.jar`）；换 jar 时用
   `-PspwProbeJar="<宿主jar>" -PspwProbeVersion=1.19.3` 直接与版本表对比。
   它不启动 SPW、不注册 hook，只用 `HostUiDiscovery` 扫一遍并打印五元组；发现不唯一或与版本表
   不一致就以退出码 1 结束，避免装上去才发现 (0 calls)。
3. **部署并看 `hook-diag.txt`**：`resolve source=scan`（换 jar 后必然重扫）+ `install ok layer=true`。
4. **失败定位**：`"layer-failed …"` → 自动回退旧点位，面板仍应可用；`"no-target"` / `"期望唯一匹配"` →
   按 §5 手工反汇编，确认结构判据是否需要调整（先把新值临时加进 `"HostUiTargets.kt"` 版本表兜底）。
5. **更新本文与版本表**：把 §2 的实测值更新，并在版本表里为新宿主加一条（仅在发现失败时用到）。

## 5. 手工定位（附录）

### 5.1 找"哪个类调用了某个方法"

用 Python 解析 class 文件常量池里的 Methodref（比字符串搜索准）：遍历 jar 内 `.class`，
筛常量池含 `"androidx/compose/runtime/"` 的类，再用 ASM/手写解析找 Methodref `owner=? name=?`。
（本仓库 `"HostBytecodeIndex.kt"` 就是现成的索引器，测试里 1.8 秒扫完 7 万个方法。）

### 5.2 反汇编目标类

`javap` 直接用非 ASCII 类名当参数会被命令行编码搞坏，先提取成 ASCII 文件名：

    $jar = [IO.Compression.ZipFile]::OpenRead($HostJar)
    $entry = $jar.GetEntry('androidx/compose/ui/<类名>.class')
    $out = [IO.File]::Create('D:\tmp\probe\probe.class')
    $entry.Open().CopyTo($out); $out.Dispose(); $jar.Dispose()
    & 'D:\Projects\.tools\jdk25\bin\javap.exe' -p -c 'D:\tmp\probe\probe.class'

要点：`-p` 必须加（private composable 也要看）；输出重定向到文件时显式用 UTF-8 读回
（`-J-Dstdout.encoding=UTF-8` 或 `JAVA_TOOL_OPTIONS`）。

### 5.3 Hook API 参数对应

| Hook API 参数 | 取值 |
| --- | --- |
| `className` | 被调用 composable 所在类 |
| `methodName` | 该函数名 |
| `parameterTypes` | 只填**业务参数**（不含 `Composer` / `int`），用于消歧；无重载传 `emptyList()` |
| `inClass` | 调用点所在类，且该类内该调用必须**唯一** |

## 6. 已知限制

- **inClass 内必须唯一**：标题栏主类里 caption button 有同名多调用，右侧按钮组里图标按钮也是；
  只有 DebugCaptionButton 模板在标题栏行里唯一，所以标题栏入口挂它。
- **ComposableSingletons 类里的插入可能不渲染**：某些缓存 lambda 内 afterComposable 注册成功但内容不进组合。
- `appendContent` 只能包装**非 composable** 的 `Function1` DSL 参数，标题栏几层都是 composable lambda，用不了。
- **多插件共存**：我们把面板包在宿主 Layer 外层（B2），尽量不与其他插件抢 `AppNavigation` 的替换点；
  与同类插件同时启用时的崩溃排查见 `D:\DeepSeek Harness\听歌统计-待办.md` 的 WAIT-1。
