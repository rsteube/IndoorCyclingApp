<#
.SYNOPSIS
    Records the screen demo Play asks for when declaring a foreground service type.

.DESCRIPTION
    Play requires a video showing the user journey that actually uses
    FOREGROUND_SERVICE_CONNECTED_DEVICE. For this app that is: a real BLE
    trainer connected, a ride running, the app sent to the background, the
    ongoing notification visible with live ride data, and the ride still
    running on return.

    Record on a real phone with a real trainer. The emulator cannot do this:
    it has no Bluetooth, and the mock trainer is a debug affordance that a
    reviewer should never be shown.

    Two limits of `adb shell screenrecord` shape the choreography below:
    it stops the moment the screen turns off, so the demonstration backgrounds
    the app instead of locking the phone, and it caps at three minutes.

.PARAMETER Serial
    adb serial of the phone. Optional if only one device is attached.

.PARAMETER Seconds
    Recording length. Default 90, which is comfortable for the steps.

.PARAMETER OutFile
    Where to write the .mp4. Defaults to the scratch dir beside the repo, not
    inside it: this is a throwaway artefact and does not belong in git.

.EXAMPLE
    .\scripts\record_permission_demo.ps1 -Seconds 90
#>
[CmdletBinding()]
param(
    [string] $Serial,
    [int]    $Seconds = 90,
    [string] $OutFile
)

$ErrorActionPreference = 'Continue'

if (-not $Serial) {
    $devices = (& adb.exe devices 2>$null) -split "`n" |
        Where-Object { $_ -match '^\S+\s+device\s*$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
    if ($devices.Count -eq 0) { throw "No device attached. Plug the phone in and enable USB debugging." }
    if ($devices.Count -gt 1) { throw "Several devices attached ($($devices -join ', ')). Pass -Serial." }
    $Serial = $devices[0]
}
if (-not $OutFile) {
    $OutFile = Join-Path (Split-Path -Parent $PSScriptRoot) "..\indoorbike-fgs-demo.mp4"
    $OutFile = [System.IO.Path]::GetFullPath($OutFile)
}

function Adb { & adb.exe -s $Serial @args 2>$null }

$pkg = (Adb shell pm list packages app.indoorbike | Out-String)
if ($pkg -notmatch 'package:app.indoorbike\s*$' -and $pkg -notmatch 'package:app.indoorbike\r?\n') {
    Write-Host "Note: the release build (app.indoorbike) was not found on this device." -ForegroundColor Yellow
    Write-Host "Record the release build, not the debug one: a reviewer must not see the mock trainer." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Recording $Seconds seconds from $Serial" -ForegroundColor Cyan
Write-Host ""
Write-Host "Do this while it records:" -ForegroundColor Cyan
Write-Host "  1. Open Indoor Bike. Show the trainer paired and connected."
Write-Host "  2. Start a workout and pedal until power and cadence are live."
Write-Host "  3. Swipe down. Show the 'Indoor Bike - Ride in progress' notification"
Write-Host "     with the ride data updating in it."
Write-Host "  4. Press Home. Stay on the home screen for ~10 seconds with the"
Write-Host "     notification still visible, so it is clear the ride continues"
Write-Host "     while the app is not in front."
Write-Host "  5. Reopen the app. Show the ride still running, timer advanced."
Write-Host "  6. End the ride. Show the notification disappearing."
Write-Host ""
Write-Host "Do NOT lock the screen: screenrecord stops when the display does." -ForegroundColor Yellow
Write-Host ""

3..1 | ForEach-Object { Write-Host "  starting in $_..."; Start-Sleep -Seconds 1 }
Write-Host "  RECORDING" -ForegroundColor Green

Adb shell screenrecord --time-limit $Seconds --bit-rate 8000000 /sdcard/fgs-demo.mp4

Write-Host "  done, pulling..."
Adb pull /sdcard/fgs-demo.mp4 $OutFile | Out-Null
Adb shell rm -f /sdcard/fgs-demo.mp4 | Out-Null

if (Test-Path $OutFile) {
    $mb = [math]::Round((Get-Item $OutFile).Length / 1MB, 1)
    Write-Host ""
    Write-Host "Wrote $OutFile ($mb MB)" -ForegroundColor Green
    Write-Host "Upload it to YouTube as UNLISTED and paste that link into the Play Console form."
} else {
    Write-Host "Nothing was pulled. Did the screen turn off during recording?" -ForegroundColor Red
}
