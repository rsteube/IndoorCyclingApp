<#
.SYNOPSIS
    Regenerates the Google Play store icon and feature graphic.

.DESCRIPTION
    Play wants a 512x512 icon and a 1024x500 feature graphic as flat bitmaps,
    but the project's artwork is vector. Rather than keep hand-exported PNGs
    that quietly drift from the design system, both are rendered here from
    HTML sources in scripts/store-graphics/ using headless Chrome or Edge,
    which every Windows machine already has.

    The icon source mirrors the adaptive launcher icon from
    slate-design-system/logo/, cropped to the 76dp the OEM mask actually
    shows so the store icon reads at the same weight as the one on a device.

    Output goes straight into the fastlane metadata tree, where `fastlane
    listing` picks it up. Screenshots are NOT generated here: those come off
    a real device.

.EXAMPLE
    .\scripts\render_store_graphics.ps1
#>
[CmdletBinding()]
param(
    [string] $Browser
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$srcDir   = Join-Path $PSScriptRoot 'store-graphics'
$outDir   = Join-Path $repoRoot 'mobile_clients\android\fastlane\metadata\android\en-US\images'

if (-not $Browser) {
    $candidates = @(
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
    )
    $Browser = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Browser) {
    throw "No Chrome or Edge found. Pass -Browser <path to chrome.exe or msedge.exe>."
}
Write-Host "Renderer: $Browser"

if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

$targets = @(
    @{ Html = 'icon.html';            Png = 'icon.png';           Width = 512;  Height = 512 },
    @{ Html = 'feature-graphic.html'; Png = 'featureGraphic.png'; Width = 1024; Height = 500 }
)

foreach ($t in $targets) {
    $html = Join-Path $srcDir $t.Html
    $png  = Join-Path $outDir $t.Png
    if (-not (Test-Path $html)) { throw "Missing source: $html" }

    # --allow-file-access-from-files lets the feature graphic reach the
    # Manrope TTF in website/fonts/ through a relative file:// URL.
    & $Browser --headless --disable-gpu --hide-scrollbars `
        --allow-file-access-from-files `
        "--screenshot=$png" "--window-size=$($t.Width),$($t.Height)" $html | Out-Null

    if (-not (Test-Path $png)) { throw "Render produced nothing for $($t.Html)" }
    Write-Host ("  {0,-20} {1}x{2}" -f $t.Png, $t.Width, $t.Height)
}

Write-Host ""
Write-Host "Wrote store graphics to $outDir"
Write-Host "Upload them with: cd mobile_clients/android; bundle exec fastlane listing"
