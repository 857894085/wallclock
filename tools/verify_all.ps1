# WallClock 全量校验脚本
#
# 一次跑完所有回归与产物校验，任何一项失败就整体失败（exit 1）。
# 出包之后跑这个，确认可以交付。
#
# Usage: powershell -ExecutionPolicy Bypass -File tools\verify_all.ps1
#
# 注意：各步骤的脚本块里**不能写 exit**——PowerShell 中 scriptblock 里的 exit
# 会直接终止整个脚本，而不是只结束这一步。这里靠「上一步为 0 才跑下一步」链式判定。

$ErrorActionPreference = "Continue"

# Python 子进程按 UTF-8 吐字节流；Windows PowerShell 5.1 默认用系统 ANSI(GBK)
# 去解码原生进程输出，中文会整段乱码。把控制台解码也设成 UTF-8 就对齐了。
# JVM 那边默认还是按系统 ANSI 输出，所以下面所有 java 调用都显式带 -Dfile.encoding=UTF-8，
# 否则会把 Python 这边修好的编码又反过来搞乱。
$env:PYTHONIOENCODING = "utf-8"
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

$Proj = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Toolchain = "C:\Users\vincent\.workbuddy\binaries\android-toolchain"
$Jdk = Join-Path $Toolchain "jdk17\jdk-17.0.13+11"
$Javac = Join-Path $Jdk "bin\javac.exe"
$Java = Join-Path $Jdk "bin\java.exe"
$Py = "C:\Users\vincent\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
$Sdk = Join-Path $Toolchain "sdk"
$BT = (Get-ChildItem (Join-Path $Sdk "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$apksigner = Join-Path $BT "apksigner.bat"
$aapt2 = Join-Path $BT "aapt2.exe"

$env:JAVA_HOME = $Jdk
$Build = Join-Path $Proj "build"
$Apk = Join-Path $Proj "output\WallClock.apk"
$Log = Join-Path $Build "verify_all.log"

New-Item -ItemType Directory -Force -Path $Build | Out-Null

$lines = New-Object System.Collections.ArrayList
$results = New-Object System.Collections.ArrayList
$failedCount = 0

function Step($name, [scriptblock]$cmd) {
    [void]$lines.Add("")
    [void]$lines.Add("########## $name ##########")
    $global:LASTEXITCODE = 0
    $out = ""
    try {
        $out = (& $cmd 2>&1 | Out-String)
    } catch {
        $out = "EXCEPTION: " + $_.Exception.Message
        $global:LASTEXITCODE = 1
    }
    $code = $LASTEXITCODE
    if ($out -and $out.Trim().Length -gt 0) {
        [void]$lines.Add($out.Trim())
    }
    $ok = ($code -eq 0)
    if (-not $ok) {
        [void]$lines.Add("!! 失败，exit code = $code")
        $script:failedCount++
    }
    [void]$results.Add([pscustomobject]@{ Name = $name; Ok = $ok })
}

function Skip($name, $reason) {
    [void]$lines.Add("")
    [void]$lines.Add("########## $name ##########")
    [void]$lines.Add("跳过：$reason")
    $script:failedCount++
    [void]$results.Add([pscustomobject]@{ Name = $name; Ok = $false })
}

# ---------------------------------------------------------------- 桌面单测

$lunarDir = Join-Path $Build "lunartest"
New-Item -ItemType Directory -Force -Path $lunarDir | Out-Null
Step "1) 农历引擎 TestLunar（逐日比对 73018 天基准）" {
    & $Javac -encoding UTF-8 -nowarn -d $lunarDir `
        (Join-Path $Proj "src\com\vincent\wallclock\gen\LunarTables.java") `
        (Join-Path $Proj "src\com\vincent\wallclock\LunarCalendar.java") `
        (Join-Path $Proj "tools\TestLunar.java")
    if ($LASTEXITCODE -eq 0) {
        $csv = Join-Path $Proj "tools\reference_lunar.csv"
        if (Test-Path $csv) {
            & $Java '-Dfile.encoding=UTF-8' -cp $lunarDir TestLunar $csv
        } else {
            & $Java '-Dfile.encoding=UTF-8' -cp $lunarDir TestLunar
        }
    }
}

$almanacDir = Join-Path $Build "almanactest"
New-Item -ItemType Directory -Force -Path $almanacDir | Out-Null
Step "2) 黄历引擎 TestAlmanac（真实样本 + 1900-2099 全量不变量）" {
    & $Javac -encoding UTF-8 -nowarn -d $almanacDir `
        (Join-Path $Proj "src\com\vincent\wallclock\gen\LunarTables.java") `
        (Join-Path $Proj "src\com\vincent\wallclock\gen\AlmanacTables.java") `
        (Join-Path $Proj "src\com\vincent\wallclock\LunarCalendar.java") `
        (Join-Path $Proj "src\com\vincent\wallclock\Almanac.java") `
        (Join-Path $Proj "tools\TestAlmanac.java")
    if ($LASTEXITCODE -eq 0) {
        & $Java '-Dfile.encoding=UTF-8' -cp $almanacDir TestAlmanac
    }
}

$layoutDir = Join-Path $Build "layouttest"
New-Item -ItemType Directory -Force -Path $layoutDir | Out-Null
Step "3) 版式算术 TestLayout（契约穷举 + 16 种真实分辨率 + 字号层级 + 卡片高度）" {
    & $Javac -encoding UTF-8 -nowarn -d $layoutDir `
        (Join-Path $Proj "src\com\vincent\wallclock\gen\Layout.java") `
        (Join-Path $Proj "src\com\vincent\wallclock\gen\Type.java") `
        (Join-Path $Proj "src\com\vincent\wallclock\gen\Cards.java") `
        (Join-Path $Proj "tools\TestLayout.java")
    if ($LASTEXITCODE -eq 0) {
        & $Java '-Dfile.encoding=UTF-8' -cp $layoutDir TestLayout
    }
}

$clockDir = Join-Path $Build "clocktest"
New-Item -ItemType Directory -Force -Path $clockDir | Out-Null
Step "4) 时钟样式算术 TestClock（七段 / 点阵字模 + 配色 + 字高反推）" {
    & $Javac -encoding UTF-8 -nowarn -d $clockDir `
        (Join-Path $Proj "src\com\vincent\wallclock\gen\ClockSpec.java") `
        (Join-Path $Proj "tools\TestClock.java")
    if ($LASTEXITCODE -eq 0) {
        & $Java '-Dfile.encoding=UTF-8' -cp $clockDir TestClock
    }
}

$lightDir = Join-Path $Build "lighttest"
New-Item -ItemType Directory -Force -Path $lightDir | Out-Null
Step "5) 光线感应规则 TestLightRule（阈值 / 滞回 / 单调性 / 穷举）" {
    & $Javac -encoding UTF-8 -nowarn -d $lightDir `
        (Join-Path $Proj "src\com\vincent\wallclock\gen\LightRule.java") `
        (Join-Path $Proj "tools\TestLightRule.java")
    if ($LASTEXITCODE -eq 0) {
        & $Java '-Dfile.encoding=UTF-8' -cp $lightDir TestLightRule
    }
}

# ---------------------------------------------------------------- 产物校验

if (-not (Test-Path $Apk)) {
    Skip "6) APK 结构 verify_apk.py" "APK 不存在，请先跑 tools\build.ps1"
    Skip "7) dex 类与内置证书 check_dex_classes.py" "APK 不存在"
    Skip "8) 签名与清单 apksigner / aapt2" "APK 不存在"
} else {
    Step "6) APK 结构 verify_apk.py（dex 魔数 / arsc 不压缩 / 对齐 / 编码 / 图标 / 清单）" {
        & $Py (Join-Path $Proj "tools\verify_apk.py") $Apk
    }

    Step "7) dex 类与内置证书 check_dex_classes.py（必需类 + 3 张证书）" {
        & $Py (Join-Path $Proj "tools\check_dex_classes.py") $Apk
    }

    Step "8) 签名与清单 apksigner / aapt2 badging" {
        & $apksigner verify --verbose $Apk
        if ($LASTEXITCODE -eq 0) {
            & $aapt2 dump badging $Apk | Select-String -Pattern "^package:"
        }
    }
}

# ---------------------------------------------------------------- 汇总

[void]$lines.Add("")
[void]$lines.Add("########## 汇总 ##########")
foreach ($r in $results) {
    [void]$lines.Add(("  [{0}] {1}" -f $(if ($r.Ok) { "通过" } else { "失败" }), $r.Name))
}
[void]$lines.Add("")
[void]$lines.Add("RESULT: " + $(if ($failedCount -eq 0) { "全部通过" } else { "$failedCount 项失败" }))

$lines -join "`r`n" | Set-Content -Encoding UTF8 $Log
if ($failedCount -gt 0) { exit 1 } else { exit 0 }
