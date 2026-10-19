# 听歌统计 · Salt Player for Windows 插件

> **本插件 100% 由 AI 生成。**

记录你听了多少次、听了多久、什么时候听、最爱听什么。全部数据保存在本地，无网络请求。

---

## 界面

### 概览

总播放次数、累计收听时长、曲目数、连续收听天数，以及最常听 Top 5 与最近播放（含封面）。

<img src="docs/screenshots/overview.png" width="100%" alt="概览页">

### 排行

总榜 / 月榜 / 周榜 × 歌曲 / 专辑 / 艺术家 × 次数 / 最近 / 时长；列表显示封面，点歌曲可看历史播放记录。

<img src="docs/screenshots/ranking.png" width="100%" alt="排行页">

### 活跃度

GitHub 风格年度热力图（点击格子查看当天明细），以及收听时段柱状图（日 / 周 / 月 / 年粒度、次数 / 时长切换、日历选日期、周视图按天分层着色）。

<img src="docs/screenshots/activity.png" width="100%" alt="活跃度页">

### 信息

插件与宿主环境、采集状态、数据占用、权限状态；可调整「明细保留天数」。

<img src="docs/screenshots/info.png" width="100%" alt="信息页">

---

## 功能说明

### 采集规则

- 一遍播放的边界：换曲、停止/结束、已计数后又回到开头
- 有效时长 = `min(位置增量, 墙钟增量)` —— 暂停、卡顿、拖进度条都不会虚增
- **计数门槛可自定义**：最少秒数 与「曲目时长 × 百分比」之间取较大 / 较小（默认 5 秒 / 50% / 取较小）
- 未达门槛的一遍记作「跳过」

### 数据存储（三层，体积可控）

| 层 | 位置 | 保留 |
|---|---|---|
| 曲目字典 | `tracks.jsonl` | 永久（每首歌一条元信息） |
| 按月聚合 | `daily/YYYY-MM.jsonl` | 永久（每次播放约 85 字节） |
| 原始明细 | `events/YYYY-MM-DD.jsonl` | 默认 90 天，可自定义；超期移到 `expired/` |

数据目录：`%APPDATA%\Salt Player for Windows\workshop\data\com.spwmods.waerwak.listenstats`

### 其他

- 浮钮可拖动，位置记忆；拖到边缘会自动贴边隐藏；拖动不会误触打开面板
- 按 `Esc` 逐层退出（先关弹层，再关面板）
- 面板下沿留空，避开迷你播放条
- 跟随宿主深色/浅色主题；支持 SPW 壁纸背景
- 播放中数据自动刷新（活跃度页除外，需手动刷新以省性能）

---

## 安装

**方式一（推荐）**：在 SPW 的创意工坊中搜索「听歌统计」订阅。

**方式二**：下载 Release 里的 `.spmod`，放入
`%APPDATA%\Salt Player for Windows\workshop\plugins`，并在该目录的 `enabled.txt` 中加入插件 ID：

```
com.spwmods.waerwak.listenstats
```

首次启用需要在 SPW 的插件权限里允许 **读取音乐库 / 快捷键 / 界面注入**。

---

## 使用

1. 启动 SPW 后，右侧边缘会出现圆形浮钮（可拖动）
2. 也可以到 **设置 → 快捷键** 里给「打开听歌统计面板」绑定一个键位
3. 插件配置页（模组管理 → 听歌统计）提供：

| 按钮 | 作用 |
|---|---|
| 计数门槛设置 | 调整最少秒数 / 百分比 / 取大取小 |
| 导出 CSV | 导出曲目汇总表（UTF-8 BOM，Excel 可直接打开） |
| 备份数据 | 打包为 zip（字典 + 聚合 + 明细 + 配置 + 浮钮位置） |
| 恢复数据 | 从 zip 恢复（现有数据会改名 `.bak` 保留） |
| 清空统计 | 内存清零，数据文件改名 `.bak`（可恢复） |
| 安装 / 卸载浮层 | 手动挂载或移除面板注入 |

---

## 权限说明

| 权限 | 用途 |
|---|---|
| `library-read` | 读取曲目信息与封面图 |
| `class-transform` | 注入全屏统计面板 |
| `key-bindings` | 注册「打开面板」的全局快捷键（默认不绑定） |

**隐私**：所有统计与配置只写入上述本地目录，插件不发起任何网络请求。

---

## 已知限制

- **字体无法与宿主同步**：SPW 使用 MiSans 并通过 Compose Resources 加载，而宿主 R8 裁掉了 Compose 的字体解析链路；实测自定义字体 / 内置 Inter / 系统默认三者的渲染结果完全一致，插件只能使用系统字体
- 无搜索功能（面板内无法做中文输入法输入）
- 无云端同步，跨设备请用「备份数据 / 恢复数据」

---

## 构建

需要 JDK 21+ 与 Gradle 8.x。

1. 复制配置模板并按本机环境填写：
   ```bash
   cp gradle.properties.example gradle.properties
   ```
   把 `spwHostJar` 指向本机 SPW 安装目录下的 fat jar（Steam 版是 `app/ffmpeg-x64.dll`）。
2. 构建：
   ```bash
   gradle build
   ```
3. 产物：`build/libs/plugin-com.spwmods.waerwak.listenstats-<version>.spmod`

一键部署（开发用，会重启 SPW）：

```powershell
pwsh -File tools/deploy.ps1 -SpmodPath build/libs/plugin-com.spwmods.waerwak.listenstats-1.0.0.spmod
```

---

## 目录结构

```
src/main/kotlin/com/spwmods/waerwak/listenstats/
├── ListenStatsPlugin.kt        插件入口（启动/停止、快捷键注册）
├── ListenStatsExtension.kt     播放扩展点（转发信号给采集层）
├── HostProbe.kt                宿主环境探测与数据目录定位
├── OverlayInjection.kt         Hook 注入 + 全部面板 UI
├── stats/
│   ├── SessionTracker.kt       一遍播放状态机（次数/时长/跳过）
│   ├── StatsStore.kt           存储（字典 + 按月聚合 + 明细）与查询
│   ├── StatsModels.kt          数据模型与阈值
│   ├── CountRule.kt            计数门槛配置
│   ├── StorageRule.kt          明细保留天数配置
│   ├── TrackDict.kt            曲目字典（hex key → 元信息）
│   └── JsonLite.kt             极简 JSON 读写
└── ui/
    ├── SaltDesign.kt           SaltUI 风格设计 token 与调色板
    ├── HostAppearance.kt       宿主深浅色同步
    ├── WallpaperLoader.kt      壁纸背景
    ├── CoverCache.kt           封面缩略图缓存（LRU + 128px 降采样）
    └── UiStateStore.kt         浮钮位置持久化
```

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
