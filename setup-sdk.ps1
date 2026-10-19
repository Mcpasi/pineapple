<#
.SYNOPSIS
  Installs the Android SDK packages TorrentTunes needs, via sdkmanager.
  Run AFTER the command-line tools are present at %LOCALAPPDATA%\Android\Sdk.
#>
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$Sdk = "$env:LOCALAPPDATA\Android\Sdk"
$SdkManager = "$Sdk\cmdline-tools\latest\bin\sdkmanager.bat"

if (-not (Test-Path $SdkManager)) {
    throw "sdkmanager not found at $SdkManager. Install cmdline-tools first."
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "Accepting licenses..." -ForegroundColor Cyan
# Pipe 'y' repeatedly to accept all licenses
$yes = ("y`n" * 30)
$yes | & $SdkManager --licenses | Out-Null

Write-Host "Installing packages (this pulls platform-tools, android-35, build-tools)..." -ForegroundColor Cyan
& $SdkManager `
    "platform-tools" `
    "platforms;android-35" `
    "build-tools;35.0.0"

Write-Host "Done. Verifying..." -ForegroundColor Green
& $SdkManager --list_installed
