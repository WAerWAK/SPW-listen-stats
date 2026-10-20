# 一键部署：关闭 SPW → 清理旧版本 → 复制新包 → 启动 SPW
#
# 用法：
#   pwsh -File tools\deploy.ps1 -SpmodPath build\libs\plugin-com.spwmods.waerwak.listenstats-0.1.0-m07.spmod

param(
    [Parameter(Mandatory = $true)][string]$SpmodPath
)

$ErrorActionPreference = 'Stop'

$exe = 'D:\Games\Steam\Steam\steamapps\common\Salt Player for Windows\Salt Player for Windows.exe'
$plugins = Join-Path $env:APPDATA 'Salt Player for Windows\workshop\plugins'
$pluginPrefix = 'plugin-com.spwmods.waerwak.listenstats-'

if (-not (Test-Path $SpmodPath)) { throw "分发包不存在：$SpmodPath" }
if (-not (Test-Path $plugins)) { throw "插件目录不存在：$plugins" }
if (-not (Test-Path $exe)) { throw "SPW 可执行文件不存在：$exe" }

# 1. 关闭 SPW
$procs = Get-Process -Name 'Salt Player for Windows' -ErrorAction SilentlyContinue
if ($procs) {
    $procs | Stop-Process -Force
    Start-Sleep -Seconds 3
    "已结束 SPW 进程（等待 3 秒）"
} else {
    "SPW 未在运行"
}

# 2. 清理旧版本（移到回收站，可恢复）
Add-Type -AssemblyName Microsoft.VisualBasic
$old = Get-ChildItem $plugins -Filter "$pluginPrefix*" -ErrorAction SilentlyContinue
foreach ($item in $old) {
    if ($item.PSIsContainer) {
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory(
            $item.FullName, 'OnlyErrorDialogs', 'SendToRecycleBin'
        )
    } else {
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile(
            $item.FullName, 'OnlyErrorDialogs', 'SendToRecycleBin'
        )
    }
    "已移除旧版本：$($item.Name)"
}

# 3. 复制新包
Copy-Item $SpmodPath $plugins
"已复制：$(Split-Path $SpmodPath -Leaf)"

# 4. 启动 SPW（离线模式 + 创意工坊发布者模式，避免启动时联网校验/更新检查）
Start-Process $exe -ArgumentList '--offline', '--workshop-publisher'
Start-Sleep -Seconds 5
$running = Get-Process -Name 'Salt Player for Windows' -ErrorAction SilentlyContinue
if ($running) { "SPW 已启动" } else { "!! SPW 启动失败，请手动打开" }

"部署完成。当前插件目录："
Get-ChildItem $plugins -Filter "$pluginPrefix*" | ForEach-Object { "  $($_.Name)" }
