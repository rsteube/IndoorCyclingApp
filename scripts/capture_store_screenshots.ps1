<#
.SYNOPSIS
    Captures Google Play store screenshots from a running emulator.

.DESCRIPTION
    Play wants 16:9 or 9:16 screenshots, which no modern phone produces: the
    Fairphone 4 this project develops against is 2.167:1 and would be
    rejected. So the store captures come from emulators built to exact
    geometry rather than from a real handset.

    Create the three AVDs once (see docs/RELEASE.md), boot one, and run this
    against it. Output goes straight into the fastlane metadata tree.

    The app is seeded first. Levels, badges and streaks are computed
    on-device from WorkoutSession rows, so a listing that shows a real
    progression needs a real history behind it, not a staged screen.
    scripts/store-screenshots/seed-history.sql supplies ten weeks of it.

    IMPORTANT: this drives the DEBUG build, because the mock trainer is the
    only way to run a ride without BLE hardware and an emulator has none.
    The debug build labels the mock trainer as such, which no real rider ever
    sees, so capture runs against a locally patched tree. See
    docs/RELEASE.md, "Store screenshots".

.PARAMETER Serial
    adb serial of the running emulator, e.g. emulator-5554.

.PARAMETER Kind
    Which Play screenshot slot to fill: phone, sevenInch or tenInch.

.PARAMETER SkipInstall
    Reuse the install already on the device.

.EXAMPLE
    .\scripts\capture_store_screenshots.ps1 -Serial emulator-5554 -Kind phone
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Serial,
    [Parameter(Mandatory = $true)][ValidateSet('phone', 'sevenInch', 'tenInch')][string] $Kind,
    [switch] $SkipInstall
)

# adb writes routine chatter to stderr, and `uiautomator dump` fails outright
# with "null root node" whenever the screen has not settled. Under
# PowerShell 5.1 a native command's stderr becomes a NativeCommandError and
# an ErrorActionPreference of Stop turns that into a dead script. Neither is
# an actual failure here, so errors stay non-terminating and the waiting is
# done explicitly, by retrying.
$ErrorActionPreference = 'Continue'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Pkg      = 'app.indoorbike.debug'
$Activity = "$Pkg/app.indoorbike.MainActivity"
$Apk      = Join-Path $RepoRoot 'mobile_clients\android\build\outputs\apk\debug\android-debug.apk'
$SeedSql  = Join-Path $PSScriptRoot 'store-screenshots\seed-history.sql'
$OutDir   = Join-Path $RepoRoot "mobile_clients\android\fastlane\metadata\android\en-US\images\${Kind}Screenshots"

function Adb { & adb.exe -s $Serial @args 2>$null }

function Dump {
    # Returns the UI hierarchy, or '' if the screen never settles.
    for ($i = 0; $i -lt 10; $i++) {
        # Delete first. A failed dump leaves the PREVIOUS ui.xml in place and
        # the stale copy still parses, so without this a miss silently reads
        # the last screen instead of the current one.
        & adb.exe -s $Serial shell rm -f /sdcard/ui.xml 2>$null | Out-Null
        & adb.exe -s $Serial shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
        $xml = (& adb.exe -s $Serial shell cat /sdcard/ui.xml 2>$null | Out-String)
        # uiautomator XML-escapes attribute text, so a label like "End & save"
        # arrives as "End &amp; save" and a literal match misses it.
        if ($xml -match '<hierarchy') { return ($xml -replace '&amp;', '&') }
        Start-Sleep -Milliseconds 1500
    }
    Write-Host "  (no UI hierarchy after 10 tries)" -ForegroundColor Yellow
    return ''
}


function Find-Bounds {
    param([string]$Text, [string]$Xml, [switch]$Last)
    # uiautomator emits one self-closing <node .../> per view. Split on '<'
    # so each candidate line holds exactly one node's attributes.
    #
    # Match text= or content-desc=: the ride controls (skip, pause, end) are
    # icon buttons carrying only a content description, so a text-only
    # matcher silently misses every one of them.
    # -Last returns the bottom-most match in the dump rather than the first.
    # uiautomator emits windows in z-order, so when a confirmation dialog
    # repeats the label of the button that opened it ("End & save" does
    # exactly this on tablets) the dialog's copy is the later one, and the
    # first match is the button underneath, now behind a scrim and untappable.
    $hit = $null
    foreach ($line in ($Xml -split '<')) {
        $hasLabel = ($line -match [regex]::Escape("text=`"$Text`"")) -or
                    ($line -match [regex]::Escape("content-desc=`"$Text`""))
        if ($hasLabel -and $line -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $hit = @{
                X = [int](([int]$matches[1] + [int]$matches[3]) / 2)
                Y = [int](([int]$matches[2] + [int]$matches[4]) / 2)
            }
            if (-not $Last) { return $hit }
        }
    }
    return $hit
}

function Tap-Text {
    param([string]$Text, [int]$Retries = 6, [int]$DelayMs = 1200, [switch]$Last)
    for ($i = 0; $i -lt $Retries; $i++) {
        $hit = Find-Bounds -Text $Text -Xml (Dump) -Last:$Last
        if ($hit) {
            Adb shell input tap $hit.X $hit.Y | Out-Null
            Write-Host ("  tap  {0}" -f $Text)
            return $true
        }
        Start-Sleep -Milliseconds $DelayMs
    }
    Write-Host ("  MISS {0}" -f $Text) -ForegroundColor Yellow
    return $false
}

function Tap-Any {
    # Labels drift between screens and form factors; take the first that hits.
    param([string[]]$Texts)
    foreach ($t in $Texts) { if (Tap-Text -Text $t -Retries 2) { return $true } }
    return $false
}


function Go-Home {
    # A bottom sheet (trainer pairing, workout picker) covers the navigation
    # bar, so tapping "Ride" at its usual coordinates lands on the sheet and
    # nothing moves. uiautomator still reports the covered nav item, so the
    # tap looks like it succeeded. Alternate BACK with the nav tap until the
    # home screen's own heading is actually on screen.
    for ($i = 0; $i -lt 6; $i++) {
        $xml = Dump
        if ($xml -match 'What are you riding') { return $true }

        Adb shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Seconds 2
        $xml = Dump
        if ($xml -match 'What are you riding') { return $true }

        $hit = Find-Bounds -Text 'Ride' -Xml $xml
        if ($hit) { Adb shell input tap $hit.X $hit.Y | Out-Null }
        Start-Sleep -Seconds 2
    }
    Write-Host "  (never reached home screen)" -ForegroundColor Yellow
    return $false
}

function Shot {
    param([string]$Name)
    $path = Join-Path $OutDir "$Name.png"
    # Never redirect binary with `>` here. PowerShell 5.1 pushes it through
    # the text pipeline and stamps a UTF-8 BOM on the front, which produces a
    # file that looks like a PNG, has a plausible size, and is not a PNG.
    # Writing on the device and pulling keeps the bytes intact.
    Adb shell screencap -p /sdcard/shot.png | Out-Null
    Adb pull /sdcard/shot.png $path | Out-Null
    Adb shell rm -f /sdcard/shot.png | Out-Null

    # Play rejects screenshots carrying an alpha channel, and screencap always
    # writes RGBA. Re-encode as 24-bit RGB before it ever reaches the metadata
    # tree, so the tree only ever holds uploadable files.
    Add-Type -AssemblyName System.Drawing
    $src = [System.Drawing.Image]::FromFile($path)
    if ($src.PixelFormat -ne [System.Drawing.Imaging.PixelFormat]::Format24bppRgb) {
        $flat = New-Object System.Drawing.Bitmap $src.Width, $src.Height, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $gfx = [System.Drawing.Graphics]::FromImage($flat)
        $gfx.Clear([System.Drawing.Color]::Black)
        $gfx.DrawImage($src, 0, 0, $src.Width, $src.Height)
        $gfx.Dispose(); $src.Dispose()
        # Save elsewhere first: the source file is still locked by the decoder.
        $flat.Save("$path.tmp", [System.Drawing.Imaging.ImageFormat]::Png)
        $flat.Dispose()
        Move-Item "$path.tmp" $path -Force
    } else { $src.Dispose() }

    $bytes = (Get-Item $path).Length
    $head = [System.IO.File]::ReadAllBytes($path)[0..3]
    if ($head[0] -ne 0x89 -or $head[1] -ne 0x50) {
        throw "$Name.png is not a PNG (starts $($head -join ','))"
    }
    Write-Host ("  SHOT {0,-22} {1} KB" -f $Name, [int]($bytes / 1KB)) -ForegroundColor Green
}

# ── Install ──────────────────────────────────────────────────────────────────

if (-not (Test-Path $Apk)) { throw "No debug APK at $Apk. Run :mobile_clients:android:assembleDebug." }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }

Write-Host "Capturing $Kind from $Serial" -ForegroundColor Cyan
Adb shell wm size
Adb shell wm density

if (-not $SkipInstall) {
    Write-Host "installing..."
    Adb install -r -t $Apk | Out-Null
}

Adb shell pm clear $Pkg | Out-Null

# Grant every runtime permission before the first launch. Starting a ride
# brings up a foreground service, and its POST_NOTIFICATIONS dialog lands on
# top of the ride HUD: the screenshot catches a system dialog and every
# subsequent tap hits it instead of the app. Granting up front means no
# dialog ever appears.
foreach ($p in @(
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.BLUETOOTH_SCAN',
    'android.permission.BLUETOOTH_CONNECT',
    'android.permission.CAMERA')) {
    Adb shell pm grant $Pkg $p | Out-Null
}

# The ride screen runs fullscreen, and Android answers the first fullscreen
# transition with its own "Viewing full screen, swipe down to exit" overlay.
# It sits on top of the HUD, swallows the taps meant for the ride controls,
# and lands in the screenshot. Pre-confirming means it never appears.
Adb shell settings put secure immersive_mode_confirmations confirmed | Out-Null

Adb shell am start -n $Activity | Out-Null

Start-Sleep -Seconds 10

# ── Seed the history ─────────────────────────────────────────────────────────
# Seeding has to come first. It needs the app stopped so the DB handle is
# free, and stopping the app drops the mock trainer connection, which is
# in-memory by design: BLE pairings are device-local and deliberately not
# persisted. Pair after the last force-stop, never before.

Write-Host "seeding history..."
Adb shell am force-stop $Pkg | Out-Null
Adb push $SeedSql /data/local/tmp/seed.sql | Out-Null
Adb shell chmod 644 /data/local/tmp/seed.sql | Out-Null
Adb shell "run-as $Pkg sh -c 'sqlite3 databases/history.db < /data/local/tmp/seed.sql'" | Out-Null
$count = (Adb shell "run-as $Pkg sqlite3 databases/history.db 'SELECT COUNT(*) FROM WorkoutSession;'").Trim()
Write-Host "  seeded $count sessions"

Adb shell am start -n $Activity | Out-Null
Start-Sleep -Seconds 10

# ── Pair the mock trainer ────────────────────────────────────────────────────
# Every ride screen below needs a live trainer, and an emulator has no BLE.
# Without this the workout detail screen offers "Pair a trainer to ride"
# instead of "Start workout".

Write-Host "pairing mock trainer..."
[void](Tap-Text 'Settings')
Start-Sleep -Seconds 3
[void](Tap-Text 'Trainer')
Start-Sleep -Seconds 3
[void](Tap-Text 'Pair trainer')
Start-Sleep -Seconds 3
[void](Tap-Text 'Use mock trainer')
Start-Sleep -Seconds 4

# ── 1. Home ──────────────────────────────────────────────────────────────────

[void](Go-Home)
Start-Sleep -Seconds 3
Shot '1_home'

# ── 2. Workout library ───────────────────────────────────────────────────────

[void](Tap-Text 'Workout')
Start-Sleep -Seconds 4
Shot '2_library'

# ── 4. Ride history ──────────────────────────────────────────────────────────
# Captured before the staged ride exists. Riding first puts a one-minute,
# 5 kJ entry at the top of the list, sitting next to forty-five minute rides
# and making the whole screen look like a toy.

[void](Go-Home)
Start-Sleep -Seconds 3
[void](Tap-Text 'History')
Start-Sleep -Seconds 5
Shot '4_history'

# ── 3. ERG ride HUD ──────────────────────────────────────────────────────────
# Step 1 is a five-minute Z1 warm-up. Skip into the Z4 threshold block so the
# HUD shows a number worth showing, then let the mock ramp onto target.

[void](Go-Home)
Start-Sleep -Seconds 3
[void](Tap-Text 'Workout')
Start-Sleep -Seconds 4
[void](Tap-Text 'Threshold Intervals 3x5')
Start-Sleep -Seconds 3
[void](Tap-Text 'Start workout')
Start-Sleep -Seconds 10
# Step 1 is a five-minute Z1 warm-up at 110 W. Skip into the Z4 threshold
# block so the HUD shows a number worth showing, then give the mock time to
# ramp onto target and the heart rate time to settle.
[void](Tap-Text 'Skip')
Start-Sleep -Seconds 14
Shot '3_erg-hud'

# ── 4. Post-ride summary ─────────────────────────────────────────────────────

# End is only offered on a paused ride, and it asks before it saves.
# Phone and tablet label this differently. The phone's ride controls share a
# row with the back button and the ride name, so the most verbose control is
# abbreviated to "End"; a tablet has the width to spell out "End & save".
# Try the long form first so the tablet matches on its own label.
[void](Tap-Text 'Pause')
Start-Sleep -Seconds 4
[void](Tap-Any @('End & save', 'End'))
Start-Sleep -Seconds 4
[void](Tap-Text 'End & save' -Last)
Start-Sleep -Seconds 10


# ── 5. Route ride HUD ────────────────────────────────────────────────────────
# The road, not the list of roads: the map, the elevation profile and the live
# gradient are what make simulation worth a screenshot.

[void](Go-Home)
Start-Sleep -Seconds 3
[void](Tap-Text 'Route')
Start-Sleep -Seconds 5
[void](Tap-Text 'bike_test')
Start-Sleep -Seconds 4
[void](Tap-Any @('Start ride', 'Start route', 'Start'))
Start-Sleep -Seconds 12
Shot '5_route-hud'

# ── 6. Progress ──────────────────────────────────────────────────────────────
# Go-Home first. A ride screen has no navigation bar to tap, and the route
# picker's sheet covers the one on the home screen.

[void](Go-Home)
Start-Sleep -Seconds 3
[void](Tap-Text 'Progress')
Start-Sleep -Seconds 4
Shot '6_progress'

Write-Host ""
Write-Host "Wrote screenshots to $OutDir" -ForegroundColor Cyan
Get-ChildItem $OutDir -Filter *.png | ForEach-Object { Write-Host ("  {0}" -f $_.Name) }
