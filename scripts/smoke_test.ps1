<#
.SYNOPSIS
    End-to-end smoke test for the Indoor Bike Android app.

.DESCRIPTION
    Builds the debug variant, installs to a connected device, walks through
    the brand-new-user flow (welcome banner -> onboarding sheet -> mock-trainer
    pair -> start a ride), captures a screenshot at each step, and scans
    logcat for fatal exceptions.

    Targets the `app.indoorbike.debug` package so the production install on the
    same device is undisturbed.

    Output:
        build/test-artifacts/<timestamp>/
            00-launch.png ... NN-step.png
            logcat.txt
            summary.txt   (PASS / FAIL + step list)

.PARAMETER DeviceId
    adb device serial. If omitted, uses the only connected device; errors if
    multiple are present.

.PARAMETER SkipBuild
    Skip gradle build/install — useful when iterating on the script.

.EXAMPLE
    .\scripts\smoke_test.ps1

.EXAMPLE
    .\scripts\smoke_test.ps1 -DeviceId 82bc875c
#>
param(
    [string]$DeviceId = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Pkg = "app.indoorbike.debug"
$Activity = "$Pkg/app.indoorbike.MainActivity"

# ── Helpers ──────────────────────────────────────────────────────────────────

function Resolve-Device {
    param([string]$Hint)
    if ($Hint) { return $Hint }
    $devices = (adb devices | Select-String -Pattern "^\S+\s+device$").Matches `
        | ForEach-Object { ($_.Value -split "\s+")[0] }
    if ($devices.Count -eq 0) { throw "No adb devices connected." }
    if ($devices.Count -gt 1) {
        throw "Multiple devices connected ($($devices -join ', ')). Pass -DeviceId."
    }
    return $devices[0]
}

function Invoke-Adb {
    # Explicit adb.exe so we don't recurse into ourselves (PowerShell function
    # names are case-insensitive so `Adb` would call `adb` would call `Adb`...).
    & adb.exe -s $script:Device @args
}

function Screenshot {
    param([string]$Label)
    $script:StepIndex++
    $name = "{0:D2}-{1}.png" -f $script:StepIndex, $Label
    $path = Join-Path $script:OutDir $name
    & adb.exe -s $script:Device exec-out screencap -p > $path
    Add-Step "OK" "screenshot:$name"
}

function Add-Step {
    param([string]$Status, [string]$Message)
    $line = "[{0,4}] {1,-40} {2}" -f $Status, $Message, ""
    Write-Host $line
    Add-Content -Path $script:SummaryPath -Value $line
}

function Tap-Text {
    param([string]$Text, [int]$WaitMsBefore = 600)
    if ($Text.Contains([char]39)) {
        Add-Step "FAIL" "tap-text [$Text] - apostrophe in search string is not supported (XPath literal limitation), pick another token"
        return $false
    }
    Start-Sleep -Milliseconds $WaitMsBefore
    Invoke-Adb shell uiautomator dump /sdcard/ui_dump.xml | Out-Null
    $raw = Invoke-Adb shell cat /sdcard/ui_dump.xml | Out-String
    [xml]$dump = $raw
    $node = $dump.SelectNodes("//node[contains(@text,'$Text')]") | Select-Object -First 1
    if (-not $node) {
        Add-Step "FAIL" "tap-text:'$Text' (not found in UI dump)"
        return $false
    }
    if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $cx = [int]([int]$matches[1] + [int]$matches[3]) / 2
        $cy = [int]([int]$matches[2] + [int]$matches[4]) / 2
        Invoke-Adb shell input tap $cx $cy | Out-Null
        Add-Step "OK" "tap-text:'$Text'"
        return $true
    }
    Add-Step "FAIL" "tap-text:'$Text' (bad bounds)"
    return $false
}

function Wait { param([int]$Ms = 1000); Start-Sleep -Milliseconds $Ms }

# ── Setup ────────────────────────────────────────────────────────────────────

$script:Device = Resolve-Device $DeviceId
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:OutDir = Join-Path $RepoRoot "build/test-artifacts/$timestamp"
$null = New-Item -ItemType Directory -Force -Path $script:OutDir
$script:SummaryPath = Join-Path $script:OutDir "summary.txt"
$script:StepIndex = 0

Add-Step "INFO" "device:$script:Device  pkg:$Pkg  out:$script:OutDir"

# ── Phase 1: build + install ─────────────────────────────────────────────────

if (-not $SkipBuild) {
    Add-Step "RUN" "gradle :installDebug"
    Push-Location $RepoRoot
    try {
        & .\gradlew.bat :mobile_clients:android:installDebug *>&1 | `
            Tee-Object -FilePath (Join-Path $script:OutDir "build.log") | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Add-Step "FAIL" "gradle install (see build.log)"
            exit 1
        }
        Add-Step "OK" "installed"
    } finally { Pop-Location }
}

# ── Phase 2: fresh-user state + launch ───────────────────────────────────────

Invoke-Adb shell pm clear $Pkg | Out-Null
Add-Step "OK" "cleared app data"

Invoke-Adb logcat -c | Out-Null
Invoke-Adb shell am start -n $Activity | Out-Null
Add-Step "OK" "launched"
Wait 2500
Screenshot "launch-welcome"

# ── Phase 3: onboarding ──────────────────────────────────────────────────────

if (Tap-Text "Get started") {
    Wait 1200
    Screenshot "onboarding-sheet"

    # Tap-Text uses an XPath literal — avoid search strings with apostrophes.
    # "Start at 150W" uniquely identifies the FTP-fallback link.
    if (Tap-Text "Start at 150W") {
        Wait 600
        Screenshot "onboarding-ftp-150"
    }

    if (Tap-Text "Continue") {
        Wait 1200
        Screenshot "post-onboarding"
    }
} else {
    Add-Step "FAIL" "welcome banner not visible"
}

# ── Phase 4: pair the mock trainer ───────────────────────────────────────────

if (Tap-Text "Settings") {
    Wait 1000
    Screenshot "settings-tab"

    if (Tap-Text "Trainer") {
        Wait 1000
        Screenshot "trainer-sheet"

        if (Tap-Text "Pair trainer") {
            Wait 1500
            Screenshot "trainer-pairing-screen"

            if (Tap-Text "Use mock trainer") {
                Wait 1500
                Screenshot "trainer-mock-connected"
            }
        }
    }
}

# ── Phase 5: start the starter ride ──────────────────────────────────────────

if (Tap-Text "Ride") {
    Wait 1000
    Screenshot "rides-tab-with-trainer"

    if (Tap-Text "First Spin") {
        Wait 1000
        Screenshot "workout-detail"

        if (Tap-Text "Start workout") {
            Wait 2500
            Screenshot "ride-screen"
        }
    }
}

# ── Phase 6: logcat scan ─────────────────────────────────────────────────────

$logPath = Join-Path $script:OutDir "logcat.txt"
& adb.exe -s $script:Device logcat -d > $logPath
$fatal = Select-String -Path $logPath -Pattern "FATAL|AndroidRuntime: FATAL" `
    | Where-Object { $_.Line -notmatch "AndroidRuntime: $" }
if ($fatal) {
    Add-Step "FAIL" "fatal exception in logcat ($($fatal.Count) lines) - see logcat.txt"
    $fatal | Select-Object -First 5 | ForEach-Object { Add-Step "    " $_.Line.Substring(0, [Math]::Min($_.Line.Length, 140)) }
    exit 2
} else {
    Add-Step "OK" "no fatal exceptions"
}

# ── Done ─────────────────────────────────────────────────────────────────────

Add-Step "DONE" "artifacts at $script:OutDir"
Write-Host ""
Write-Host "Smoke test complete. Open the screenshots in $script:OutDir to verify visually."
