# WallClock APK build script (no Gradle / no Android Studio)
#
# Pipeline: aapt2 compile -> aapt2 link -> javac -> d8 -> merge dex
#           -> zipalign -> apksigner -> verify
#
# Usage: powershell -ExecutionPolicy Bypass -File tools\build.ps1

# NOTE: must NOT be 'Stop' here: javac/d8 write notes to stderr and PowerShell 5.1
# would turn that into a terminating NativeCommandError. We rely on $LASTEXITCODE instead.
$ErrorActionPreference = "Continue"

$Proj = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Toolchain = "C:\Users\vincent\.workbuddy\binaries\android-toolchain"
$Jdk = Join-Path $Toolchain "jdk17\jdk-17.0.13+11"
$Sdk = Join-Path $Toolchain "sdk"
$Py = "C:\Users\vincent\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
$Build = Join-Path $Proj "build"
$OutDir = Join-Path $Proj "output"
$Log = Join-Path $Build "build.log"

New-Item -ItemType Directory -Force -Path $Build, $OutDir | Out-Null

$BT = (Get-ChildItem (Join-Path $Sdk "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$Plat = (Get-ChildItem (Join-Path $Sdk "platforms") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$aapt2 = Join-Path $BT "aapt2.exe"
$zipalign = Join-Path $BT "zipalign.exe"
$apksigner = Join-Path $BT "apksigner.bat"
$d8 = Join-Path $BT "d8.bat"
$androidJar = Join-Path $Plat "android.jar"

$env:JAVA_HOME = $Jdk
$env:PATH = "$(Join-Path $Jdk 'bin');$env:PATH"

$lines = New-Object System.Collections.ArrayList
$failed = $false

function Run($name, [scriptblock]$cmd) {
    [void]$lines.Add("===== $name =====")
    $global:LASTEXITCODE = 0
    $out = ""
    try {
        $out = (& $cmd 2>&1 | Out-String)
    } catch {
        $out = "EXCEPTION: " + $_.Exception.Message
    }
    $code = $LASTEXITCODE
    if ($out -and $out.Trim().Length -gt 0) { [void]$lines.Add($out.Trim()) }
    if ($code -ne 0) {
        [void]$lines.Add("!! step failed, exit code = $code")
        $script:failed = $true
    }
}

Run "environment" {
    "aapt2      = $aapt2"
    "zipalign   = $zipalign"
    "apksigner  = $apksigner"
    "d8         = $d8"
    "androidJar = $androidJar"
    & (Join-Path $Jdk "bin\javac.exe") -version
}

# 1) resources
Run "aapt2 compile" {
    & $aapt2 compile --dir (Join-Path $Proj "res") -o (Join-Path $Build "res.zip")
}

Run "aapt2 link" {
    & $aapt2 link -o (Join-Path $Build "base.apk") `
        -I $androidJar `
        --manifest (Join-Path $Proj "AndroidManifest.xml") `
        --java (Join-Path $Build "gen") `
        --auto-add-overlay `
        (Join-Path $Build "res.zip")
}

if (-not $failed) {
    # 2) java sources (including generated R.java)
    $sources = @()
    $sources += Get-ChildItem (Join-Path $Build "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    $sources += Get-ChildItem (Join-Path $Proj "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    $sourcesFile = Join-Path $Build "sources.txt"
    $sources | Set-Content -Encoding ASCII $sourcesFile
    [void]$lines.Add("source files: $($sources.Count)")

    Run "javac" {
        & (Join-Path $Jdk "bin\javac.exe") -encoding UTF-8 -source 8 -target 8 `
            -bootclasspath $androidJar -Xlint:-options -nowarn `
            -d (Join-Path $Build "classes") "@$sourcesFile"
    }
}

if (-not $failed) {
    # 3) dex  (d8 requires the output directory to already exist)
    New-Item -ItemType Directory -Force -Path (Join-Path $Build "dex") | Out-Null
    Run "jar" {
        & (Join-Path $Jdk "bin\jar.exe") cf (Join-Path $Build "classes.jar") -C (Join-Path $Build "classes") .
    }
    Run "d8" {
        & $d8 --release --min-api 19 --lib $androidJar `
            --output (Join-Path $Build "dex") (Join-Path $Build "classes.jar")
    }
}

if (-not $failed) {
    # 4) merge classes.dex into apk
    Run "merge dex" {
        & $Py (Join-Path $Proj "tools\add_dex.py") `
            (Join-Path $Build "base.apk") `
            (Join-Path $Build "dex\classes.dex") `
            (Join-Path $Build "unsigned.apk")
    }
}

if (-not $failed) {
    # 5) align + sign
    $ks = Join-Path $Proj "keystore\wallclock.jks"
    if (-not (Test-Path $ks)) {
        Run "keytool generate keystore" {
            New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null
            & (Join-Path $Jdk "bin\keytool.exe") -genkeypair -v `
                -keystore $ks -storepass wallclock -keypass wallclock `
                -alias wallclock -keyalg RSA -keysize 2048 -validity 10950 `
                -dname "CN=WallClock, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN"
        }
    }

    Run "zipalign" {
        & $zipalign -f -p 4 (Join-Path $Build "unsigned.apk") (Join-Path $Build "aligned.apk")
    }
    Run "apksigner sign" {
        & $apksigner sign --ks $ks --ks-pass pass:wallclock --key-pass pass:wallclock `
            --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
            --out (Join-Path $OutDir "WallClock.apk") (Join-Path $Build "aligned.apk")
    }
    Run "apksigner verify" {
        & $apksigner verify --verbose (Join-Path $OutDir "WallClock.apk")
    }
    Run "aapt2 dump badging" {
        & $aapt2 dump badging (Join-Path $OutDir "WallClock.apk")
    }
}

$apk = Join-Path $OutDir "WallClock.apk"
if (Test-Path $apk) {
    [void]$lines.Add("APK: " + $apk)
    [void]$lines.Add("SIZE: " + [math]::Round((Get-Item $apk).Length / 1KB, 1) + " KB")
}
[void]$lines.Add("RESULT: " + $(if ($failed) { "FAILED" } else { "OK" }))

$lines -join "`r`n" | Set-Content -Encoding UTF8 $Log
if ($failed) { exit 1 } else { exit 0 }
