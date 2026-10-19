<#
.SYNOPSIS
    生成创意工坊封面图（1024×1024 PNG）

.DESCRIPTION
    用 HTML + 无头 Edge 截图的方式绘制封面：
      1. 准备字体（默认霞鹜文楷 Mono Medium，与插件实际界面字体一致）
      2. 把 cover.html 复制到临时目录
      3. Edge headless 截图
      4. 结果写入 docs/screenshots/workshop-cover.png

    字体不随仓库提交，每次生成时从本地拷贝（可用 -FontFile 换成其它字体）。

.EXAMPLE
    pwsh -File tools\cover\make-cover.ps1
#>
param(
    [string]$FontFile = 'D:\Apps\Scoop\apps\LXGWWenKaiMono\1.530\LXGWWenKaiMono-Medium.ttf',
    [string]$Out = 'docs\screenshots\workshop-cover.png',
    [string]$Edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    [int]$Size = 1024
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)

if (-not (Test-Path $Edge)) {
    throw "找不到 Edge：$Edge（需要它做无头截图）"
}
if (-not (Test-Path $FontFile)) {
    throw "找不到字体文件：$FontFile"
}

$work = Join-Path $env:TEMP ('spw-cover-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null

try {
    # 1. 准备字体（cover.html 里引用 lxgw.ttf）
    Write-Host '准备封面字体…'
    Copy-Item $FontFile (Join-Path $work 'lxgw.ttf') -Force

    # 2. 复制 HTML
    Copy-Item (Join-Path $scriptDir 'cover.html') (Join-Path $work 'cover.html') -Force

    # 3. 无头截图
    Write-Host "渲染封面（$Size×$Size）…"
    $png = Join-Path $work 'cover.png'
    $html = 'file:///' + ((Join-Path $work 'cover.html') -replace '\\', '/')
    & $Edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 `
        --run-all-compositor-stages-before-draw --virtual-time-budget=3000 `
        --screenshot="$png" --window-size="$Size,$Size" $html 2>&1 | Out-Null

    if (-not (Test-Path $png)) { throw 'Edge 没有生成截图' }

    # 4. 输出
    $outPath = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $repoRoot $Out }
    $outDir = Split-Path -Parent $outPath
    if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
    Copy-Item $png $outPath -Force

    $kb = [math]::Round((Get-Item $outPath).Length / 1KB, 0)
    Write-Host "完成：$outPath（$kb KB）"
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
