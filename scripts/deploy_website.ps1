# Deploy the website directory to the forge's Pages hosting.
#
# Pushes the contents of 'website' to the `pages` branch without switching
# branches. indoorbike.app is served from CodeFloe Pages; Codeberg Pages served
# it until the move, which is why the remote is a parameter and not baked in.
#
# The paths this publishes are load-bearing for shipped APKs: every install ever
# released fetches https://indoorbike.app/registry.json, and the editor and share
# pages are linked from QR codes in the wild. Keep the URL layout stable.
#
# Usage:
#   .\scripts\deploy_website.ps1
#   .\scripts\deploy_website.ps1 -Remote codeberg

param(
    [string]$Remote = "codefloe",
    [string]$Branch = "pages"
)

Write-Host "Deploying website/ to $Remote/$Branch ..." -ForegroundColor Cyan
git subtree push --prefix website $Remote $Branch
if ($LASTEXITCODE -eq 0) {
    Write-Host "Deployment successful." -ForegroundColor Green
} else {
    Write-Host "Deployment failed. Ensure all website/ changes are committed first." -ForegroundColor Red
}
