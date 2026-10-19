# 校验插件字节码引用的宿主导成员是否真实存在（防运行时 NoSuchMethodError）
#
# 原理：用 javap 反汇编插件 class，提取所有 Method/InterfaceMethod/Field 引用，
# 再对每个被引用的宿主类跑一遍 javap -p，确认类与方法/字段名都在。
#
# 为什么需要：宿主 fat jar 被 R8 裁剪过，某些 Compose API 在元数据里已不存在；
# 插件必须只使用宿主真实保留的成员，这个检查把问题提前到构建期。
#
# 用法：
#   pwsh -File tools\verify-host-api.ps1 `
#       -PluginDir build\classes\kotlin\main `
#       -HostJar "D:\...\app\ffmpeg-x64.dll"

param(
    [Parameter(Mandatory = $true)][string]$PluginDir,
    [Parameter(Mandatory = $true)][string]$HostJar,
    [string]$Javap = ""
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Javap)) {
    $candidates = @(
        $env:SPW_JAVAP,
        'D:\Projects\.tools\jdk25\bin\javap.exe',
        (Join-Path $env:JAVA_HOME 'bin\javap.exe')
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($candidates.Count -eq 0) {
        throw "未找到 javap，请用 -Javap 指定（宿主类需要 Java 25 的 javap）"
    }
    $Javap = $candidates[0]
}
if (-not (Test-Path $Javap)) { throw "javap 不存在：$Javap" }
if (-not (Test-Path $PluginDir)) { throw "插件 class 目录不存在：$PluginDir" }
if (-not (Test-Path $HostJar)) { throw "宿主 jar 不存在：$HostJar" }

$pluginRoot = (Resolve-Path $PluginDir).Path
$HostJar = (Resolve-Path $HostJar).Path

# 只看宿主侧命名空间
# 注：kotlin/ 与 kotlinx/ 不参与校验 —— 多文件类 facade（CollectionsKt extends CollectionsKt__*）
# 会让 javap 看起来“没有这个方法”，属于误报，且 stdlib 不可能被裁剪
$hostPrefixes = @('androidx/', 'com/xuncorp/', 'org/pf4j/')

$refsByOwner = @{}
$pluginClasses = Get-ChildItem -Path $PluginDir -Recurse -Filter *.class

foreach ($file in $pluginClasses) {
    $rel = $file.FullName.Substring($pluginRoot.Length + 1)
    $className = $rel.Replace('\', '/')
    if ($className.EndsWith('.class')) { $className = $className.Substring(0, $className.Length - 6) }
    $className = $className.Replace('/', '.')

    $lines = & $Javap -p -c -classpath $pluginRoot $className 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "javap 失败：$className"
        continue
    }
    foreach ($line in $lines) {
        if ($line -match '//\s+(?:Method|InterfaceMethod|Field)\s+([\w$/]+)\.([\w$<>]+):') {
            $owner = $matches[1]
            $member = $matches[2]
            if ($owner -notmatch '/') { continue }        # 同类内部调用
            $isHost = $false
            foreach ($p in $hostPrefixes) {
                if ($owner.StartsWith($p)) { $isHost = $true; break }
            }
            if (-not $isHost) { continue }
            if (-not $refsByOwner.ContainsKey($owner)) {
                $refsByOwner[$owner] = [System.Collections.Generic.HashSet[string]]::new()
            }
            $null = $refsByOwner[$owner].Add($member)
        }
    }
}

$totalMembers = 0
$missingClasses = [System.Collections.Generic.List[string]]::new()
$missingMembers = [System.Collections.Generic.List[string]]::new()
$memberCache = @{}

function Get-AvailableMembers {
    param([string]$Dotted, [int]$Depth = 0)
    if ($memberCache.ContainsKey($Dotted)) { return $memberCache[$Dotted] }
    $set = [System.Collections.Generic.HashSet[string]]::new()
    $memberCache[$Dotted] = $set

    $out = & $Javap -p -classpath $HostJar $Dotted 2>&1
    if ($LASTEXITCODE -ne 0) {
        $memberCache[$Dotted] = $null
        return $null
    }

    $parents = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $out) {
        if ($line -match '([\w$<>]+)\(') {
            $null = $set.Add($matches[1])
        } elseif ($line -match '([\w$]+);\s*$') {
            $null = $set.Add($matches[1])
        }
        if ($line -match '\bextends\s+([\w.$]+)') { $parents.Add($matches[1]) }
        if ($line -match '\bimplements\s+([\w.$]+)') { $parents.Add($matches[1]) }
    }

    if ($Depth -lt 2) {
        foreach ($parent in $parents) {
            if ($parent -eq 'java.lang.Object') { continue }
            $sub = Get-AvailableMembers -Dotted $parent -Depth ($Depth + 1)
            if ($null -ne $sub) {
                foreach ($m in $sub) { $null = $set.Add($m) }
            }
        }
    }
    return $set
}

foreach ($owner in ($refsByOwner.Keys | Sort-Object)) {
    $members = $refsByOwner[$owner]
    $totalMembers += $members.Count

    $dotted = $owner.Replace('/', '.')
    $available = Get-AvailableMembers -Dotted $dotted
    if ($null -eq $available) {
        $missingClasses.Add($owner)
        continue
    }

    foreach ($m in $members) {
        if (-not $available.Contains($m)) {
            $missingMembers.Add("$dotted :: $m")
        }
    }
}

"插件 class：$($pluginClasses.Count)"
"引用的宿主类：$($refsByOwner.Count)"
"引用的宿主成员：$totalMembers"
""

if ($missingClasses.Count -eq 0 -and $missingMembers.Count -eq 0) {
    "OK：所有引用都能在宿主中找到"
    exit 0
}

if ($missingClasses.Count -gt 0) {
    "!! 宿主中不存在的类（$($missingClasses.Count)）："
    $missingClasses | ForEach-Object { "   - $_" }
}
if ($missingMembers.Count -gt 0) {
    "!! 宿主中不存在的成员（$($missingMembers.Count)）："
    $missingMembers | ForEach-Object { "   - $_" }
}
exit 1
