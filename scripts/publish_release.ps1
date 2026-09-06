# Create a release on a Forgejo forge for a tag and attach the signed APK.
#
# ObtainX tracks *release assets*, so a pushed tag alone isn't enough - this
# creates the Release object and uploads the APK to it.
#
# CodeFloe is the only place the app is released. The forge is still a
# parameter, but only so a one-off elsewhere needs -ApiBase + -Repo + -Token
# rather than a code change:
#
#   .\scripts\publish_release.ps1 -Tag v1.8
#
# -Apk and -NotesFile default to the conventional paths for the tag, so the
# usual case needs nothing but the tag.
#
# Auth: the token comes from $env:CODEFLOE_TOKEN, or pass -Token. It needs the
# 'write:repository' scope (Settings -> Applications -> Generate Token), and a
# token without it fails with "user should have a permission to write to a
# repo" - the release is refused, nothing is half-published. The token is never
# written to disk or printed.

param(
    [ValidateSet("codefloe")]
    [string]$Forge     = "codefloe",
    [string]$Tag       = "",
    [string]$Apk       = "",
    [string]$NotesFile = "",
    [string]$Repo      = "",
    [string]$ApiBase   = "",
    [string]$Token     = ""
)

$ErrorActionPreference = "Stop"

# Defaults. Anything passed explicitly wins, so a one-off publish somewhere
# else needs -ApiBase + -Repo + -Token and no code change.
if (-not $ApiBase) { $ApiBase = "https://codefloe.com" }
if (-not $Repo)    { $Repo    = "SnappTechnology/IndoorCyclingApp" }
if (-not $Token)   { $Token   = $env:CODEFLOE_TOKEN }

if (-not $Tag) {
    Write-Error "No -Tag. Pass the already-pushed tag, e.g. -Tag v1.8."
    exit 1
}

$version   = $Tag.TrimStart("v")
$assetName = "indoorbike-$version.apk"
if (-not $Apk)       { $Apk       = "mobile_clients/android/release/$assetName" }
if (-not $NotesFile) { $NotesFile = "scripts/release-notes-$version.md" }

if (-not $Token) {
    Write-Error "No token for $Forge. Pass -Token, or set `$env:$($Forge.ToUpper())_TOKEN (scope: write:repository)."
    exit 1
}
if (-not (Test-Path $Apk)) { Write-Error "APK not found: $Apk"; exit 1 }

$api = "$ApiBase/api/v1/repos/$Repo"
# Read as a plain .NET string. `Get-Content -Raw` decorates its output with
# PowerShell note properties, which Windows PowerShell 5.1's ConvertTo-Json then
# serialises as an object ({"value":...}) instead of a JSON string.
$body = if ($NotesFile -and (Test-Path $NotesFile)) { [System.IO.File]::ReadAllText((Resolve-Path $NotesFile)) } else { "" }

# 1. Create the release for the (already-pushed) tag.
$payloadFile = New-TemporaryFile
$payloadJson = @{ tag_name = $Tag; name = "Indoor Bike $version"; body = $body; draft = $false; prerelease = $false } |
    ConvertTo-Json -Compress
# Write UTF-8 WITHOUT a BOM. Windows PowerShell 5.1's `Set-Content -Encoding utf8`
# emits a BOM, which Forgejo's JSON parser rejects ("invalid character 'ï'").
[System.IO.File]::WriteAllText($payloadFile, $payloadJson, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Creating release $Tag on $Repo ($Forge) ..." -ForegroundColor Cyan
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
Write-Host "Release : $ApiBase/$Repo/releases/tag/$Tag"
Write-Host "APK     : $($asset.browser_download_url)"
