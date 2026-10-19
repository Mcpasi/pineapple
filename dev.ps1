<#
.SYNOPSIS
  One-command build + install + launch for TorrentTunes on a connected device.

.EXAMPLE
  .\dev.ps1                  # build, install, launch
  .\dev.ps1 build            # just build the APK
  .\dev.ps1 test             # JVM unit tests (InnerTube response parsing)
  .\dev.ps1 install          # build + install (no auto-launch)
  .\dev.ps1 logs             # stream app logs (Search / Play / PlayerController)
  .\dev.ps1 devices          # list connected devices
  .\dev.ps1 pair 192.168.1.5 # pair with a device over Wi-Fi (Android 11+)
#>
param(
    [ValidateSet("run","build","test","install","logs","devices","pair","clean")]
    [string]$Action = "run",
    [string]$PairIp = ""
)

$ErrorActionPreference = "Stop"
$Project = $PSScriptRoot

# --- Toolchain paths -------------------------------------------------------
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
}
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"

$Adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
if (-not (Test-Path $Adb)) { $Adb = "adb" }  # fall back to PATH
$Package  = "com.torrenttunes.app"
$Activity = "$Package/.ui.MainActivity"
$Apk      = "$Project\app\build\outputs\apk\debug\app-debug.apk"

function Invoke-Gradle([string[]]$Tasks) {
    Push-Location $Project
    try {
        # Prefer the wrapper if present, else the Gradle we downloaded.
        if (Test-Path "$Project\gradlew.bat") {
            & ".\gradlew.bat" @Tasks
        } else {
            $gradleBat = Get-ChildItem "$env:USERPROFILE\.gradle-dist\gradle-*\bin\gradle.bat" -ErrorAction SilentlyContinue |
                         Select-Object -First 1
            if ($gradleBat) { & $gradleBat.FullName @Tasks }
            else { throw "No gradle found. Run: .\dev.ps1 after Gradle finishes installing." }
        }
        if ($LASTEXITCODE -ne 0) { throw "Gradle task failed: $($Tasks -join ' ')" }
    } finally { Pop-Location }
}

function Get-DeviceArg {
    $state = & $Adb get-state 2>$null
    return $true
}

switch ($Action) {
    "pair" {
        if (-not $PairIp) { $PairIp = Read-Host "Enter phone IP (e.g. 192.168.1.5)" }
        Write-Host "On the phone: Settings > Developer options > Pair device with pairing code" -ForegroundColor Cyan
        $port = Read-Host "Pairing port shown on phone"
        & $Adb pair "${PairIp}:${port}"
        Write-Host "Now run: .\dev.ps1 devices   (then connect to the connect-port if asked)" -ForegroundColor Yellow
    }

    "devices" {
        Write-Host "Connected devices:" -ForegroundColor Cyan
        & $Adb devices -l
        Write-Host "`nTo add a wireless device: .\dev.ps1 pair <phone-ip>" -ForegroundColor Yellow
    }

    "clean" { Invoke-Gradle @("clean") }

    "test" {
        Write-Host "Running JVM unit tests (InnerTube parsing)..." -ForegroundColor Cyan
        Invoke-Gradle @("test")
        $results = "$Project\app\build\test-results\testDebugUnitTest"
        if (Test-Path $results) {
            Get-ChildItem "$results\*.xml" | ForEach-Object {
                $x = [xml](Get-Content $_.FullName -Raw)
                Write-Host ("  {0}: {1} tests, {2} failures" -f $x.testsuite.name, $x.testsuite.tests, $x.testsuite.failures) -ForegroundColor $(if ([int]$x.testsuite.failures -gt 0) { "Red" } else { "Green" })
            }
        }
    }

    "build" {
        Write-Host "Building debug APK..." -ForegroundColor Cyan
        Invoke-Gradle @("clean","assembleDebug")
        if (Test-Path $Apk) {
            Write-Host "APK: $Apk ($([math]::Round((Get-Item $Apk).Length/1MB,2)) MB)" -ForegroundColor Green
        }
    }

    "install" {
        Invoke-Gradle @("clean","assembleDebug")
        if (-not (Test-Path $Apk)) { throw "APK not found at $Apk" }
        Write-Host "Installing on device..." -ForegroundColor Cyan
        & $Adb install -r $Apk
    }

    "logs" {
        Write-Host "Streaming logs for $Package (Ctrl+C to stop)..." -ForegroundColor Cyan
        & $Adb logcat -c
        & $Adb logcat --pid=$( (& $Adb shell pidof $Package).Trim() ) 2>$null
        # If app not running yet, fall back to tag filters
        & $Adb logcat -s Search:V Play:V PlayerController:V AndroidRuntime:E
    }

    "run" {
        Write-Host "==> Building..." -ForegroundColor Cyan
        # clean on purpose: incremental packaging was silently shipping ~60 MB
        # of untracked bytes (72 MB APK where the entries sum to 12 MB).
        Invoke-Gradle @("clean","assembleDebug")
        if (-not (Test-Path $Apk)) { throw "APK not found at $Apk" }

        $devices = (& $Adb devices).Count - 1   # minus header line
        if ($devices -lt 1) {
            Write-Host "No device connected. Connect via USB (enable USB debugging)" -ForegroundColor Red
            Write-Host "or pair wirelessly: .\dev.ps1 pair <phone-ip>" -ForegroundColor Yellow
            Write-Host "`nAPK is ready at: $Apk" -ForegroundColor Green
            break
        }

        Write-Host "==> Installing..." -ForegroundColor Cyan
        & $Adb install -r $Apk
        Write-Host "==> Launching $Activity ..." -ForegroundColor Cyan
        & $Adb shell am start -n $Activity
        Write-Host "Done. Stream logs anytime: .\dev.ps1 logs" -ForegroundColor Green
    }
}
