# Create a Codeberg (Forgejo) release for a tag and attach the signed APK.
#
# ObtainX tracks *release assets*, so a pushed tag alone isn't enough - this
# creates the Release object and uploads the APK to it.
#
# Auth: pass -Token, or set $env:CODEBERG_TOKEN. The token needs the
# 'write:repository' scope (Codeberg: Settings -> Applications -> Generate Token).
# The token is never written to disk or printed.
#
# Usage:
#   .\scripts\codeberg_release.ps1 -Token <token>
#   $env:CODEBERG_TOKEN = "<token>"; .\scripts\codeberg_release.ps1
#
param(
    [string]$Repo      = "juhanilehtimaki/IndoorCyclingApp",
    [string]$Tag       = "v1.1",
    [string]$Apk       = "mobile_clients/android/release/android-release.apk",
    [string]$NotesFile = "scripts/release-notes-1.1.md",
    [string]$Token     = $env:CODEBERG_TOKEN
)

$ErrorActionPreference = "Stop"

if (-not $Token) {
    Write-Error "No token. Pass -Token <token> or set `$env:CODEBERG_TOKEN (scope: write:repository)."
    exit 1
}
if (-not (Test-Path $Apk)) { Write-Error "APK not found: $Apk"; exit 1 }

$api      = "https://codeberg.org/api/v1/repos/$Repo"
$version  = $Tag.TrimStart("v")
$assetName = "indoorbike-$version.apk"
# Read as a plain .NET string. `Get-Content -Raw` decorates its output with
# PowerShell note properties, which Windows PowerShell 5.1's ConvertTo-Json then
# serialises as an object ({"value":...}) instead of a JSON string.
$body     = if ($NotesFile -and (Test-Path $NotesFile)) { [System.IO.File]::ReadAllText((Resolve-Path $NotesFile)) } else { "" }

# 1. Create the release for the (already-pushed) tag.
$payloadFile = New-TemporaryFile
$payloadJson = @{ tag_name = $Tag; name = "Indoor Bike $version"; body = $body; draft = $false; prerelease = $false } |
    ConvertTo-Json -Compress
# Write UTF-8 WITHOUT a BOM. Windows PowerShell 5.1's `Set-Content -Encoding utf8`
# emits a BOM, which Codeberg's JSON parser rejects ("invalid character 'ï'").
[System.IO.File]::WriteAllText($payloadFile, $payloadJson, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Creating release $Tag on $Repo ..." -ForegroundColor Cyan
$created = curl.exe -sS -X POST "$api/releases" `
    -H "Authorization: token $Token" `
    -H "Content-Type: application/json" `
    -d "@$payloadFile"
Remove-Item $payloadFile -Force

$rel = $created | ConvertFrom-Json
if (-not $rel.id) { Write-Error "Release creation failed: $created"; exit 1 }
Write-Host "Created release id=$($rel.id)" -ForegroundColor Green

# 2. Upload the APK as a release asset.
Write-Host "Uploading $assetName ..." -ForegroundColor Cyan
$uploaded = curl.exe -sS -X POST "$api/releases/$($rel.id)/assets?name=$assetName" `
    -H "Authorization: token $Token" `
    -F "attachment=@$Apk;type=application/vnd.android.package-archive"

$asset = $uploaded | ConvertFrom-Json
if (-not $asset.browser_download_url) { Write-Error "Asset upload failed: $uploaded"; exit 1 }

Write-Host "Done." -ForegroundColor Green
Write-Host "Release : https://codeberg.org/$Repo/releases/tag/$Tag"
Write-Host "APK     : $($asset.browser_download_url)"
