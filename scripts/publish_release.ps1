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
# Auth: the token needs the 'write:repository' scope (Settings -> Applications
# -> Generate Token). Without it the publish fails with "user should have a
# permission to write to a repo"; the release is refused and nothing is
# half-published. The token is never printed.
#
# It is looked for in this order:
#
#   1. -Token
#   2. forge.properties at the repo root, key `codefloeToken` (gitignored)
#   3. the CODEFLOE_TOKEN environment variable, User scope
#   4. the CODEFLOE_TOKEN environment variable, this process
#
# The file is checked before the environment on purpose. A variable set with
# setx or the System Properties dialog does not reach a process that is already
# running, so any shell - or editor, or agent - started before the change keeps
# reading the old value. That failure is indistinguishable from a bad token
# from the outside, and it has cost a release twice. keystore.properties has
# never had the problem because it is a file; this is the same trick for the
# same reason.

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

# Read one key out of a java.util.Properties-style file, the same shape
# keystore.properties uses. Returns "" when the file or the key is absent.
function Get-PropertyValue([string]$Path, [string]$Key) {
    if (-not (Test-Path $Path)) { return "" }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#") -or $trimmed.StartsWith("!")) { continue }
        $split = $trimmed.IndexOf("=")
        if ($split -lt 1) { continue }
        if ($trimmed.Substring(0, $split).Trim() -eq $Key) {
            return $trimmed.Substring($split + 1).Trim()
        }
    }
    return ""
}

if (-not $Token) {
    $propsPath = Join-Path (Split-Path -Parent $PSScriptRoot) "forge.properties"
    $Token = Get-PropertyValue $propsPath "codefloeToken"
    $tokenFrom = "forge.properties"
}
if (-not $Token) {
    # User scope rather than $env:, so a token set after this shell started is
    # still found. This is the case that kept looking like a bad token.
    $Token = [Environment]::GetEnvironmentVariable("CODEFLOE_TOKEN", "User")
    $tokenFrom = "CODEFLOE_TOKEN (User scope)"
}
if (-not $Token) {
    $Token = $env:CODEFLOE_TOKEN
    $tokenFrom = "CODEFLOE_TOKEN (process)"
}

if (-not $Tag) {
    Write-Error "No -Tag. Pass the already-pushed tag, e.g. -Tag v1.8."
    exit 1
}

$version   = $Tag.TrimStart("v")
$assetName = "indoorbike-$version.apk"
if (-not $Apk)       { $Apk       = "mobile_clients/android/release/$assetName" }
if (-not $NotesFile) { $NotesFile = "scripts/release-notes-$version.md" }

if (-not $Token) {
    Write-Error @"
No CodeFloe token. Put it in forge.properties at the repo root (gitignored):

    codefloeToken=<token with the write:repository scope>

or pass -Token. See the header of this script for the full search order.
"@
    exit 1
}

# Fail here rather than after building a release payload, and say which of the
# four sources the token came from - the useful fact when the wrong one is
# being picked up.
try {
    $probe = Invoke-RestMethod -Uri "$ApiBase/api/v1/repos/$Repo" -Headers @{ Authorization = "token $Token" }
    if (-not $probe.permissions.push) {
        Write-Error "The token from $tokenFrom can read $Repo but not write to it. It needs the 'write:repository' scope."
        exit 1
    }
    Write-Host "Token from $tokenFrom (write access confirmed)." -ForegroundColor DarkGray
} catch {
    Write-Error "Could not check the token from ${tokenFrom}: $($_.Exception.Message)"
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
