# Build/test helper for this repository.
# Uses the local toolchain in .tools when present (Windows), otherwise the caller's JAVA_HOME /
# ANDROID_HOME. Examples:
#   .\scripts\build.ps1                       # assembleDebug
#   .\scripts\build.ps1 testDebugUnitTest lintDebug
#   .\scripts\build.ps1 assembleRelease
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$localJdkRoot = Join-Path $root ".tools\jdk"
if (-not $env:JAVA_HOME -and (Test-Path $localJdkRoot)) {
    $jdk = Get-ChildItem $localJdkRoot -Directory | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}

$localSdk = Join-Path $root ".tools\android-sdk"
if (-not $env:ANDROID_HOME -and (Test-Path $localSdk)) {
    $env:ANDROID_HOME = $localSdk
    $env:ANDROID_SDK_ROOT = $localSdk
}

$localGradleHome = Join-Path $root ".tools\gradle-home"
if (Test-Path $localGradleHome) {
    $env:GRADLE_USER_HOME = $localGradleHome
    $localTemp = Join-Path $root ".tools\tmp"
    New-Item -ItemType Directory -Force -Path $localTemp | Out-Null
    $env:TEMP = $localTemp
    $env:TMP = $localTemp
}

if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @("assembleDebug")
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "Gradle tasks: $($GradleArgs -join ' ')"

& (Join-Path $root "gradlew.bat") @GradleArgs
exit $LASTEXITCODE
