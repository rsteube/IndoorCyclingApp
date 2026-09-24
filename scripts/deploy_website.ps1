# Deploy the website directory to the forge's Pages hosting.
#
# Pushes the contents of 'website' to the `pages` branch without switching
# branches. indoorbike.app is served from CodeFloe Pages, which publishes the
# branch as it is (no build step). The domain itself is set up in the
# repository's Pages settings and in DNS; see website/README.md.
#
# The paths this publishes are load-bearing for shipped APKs: every install ever
# released fetches https://indoorbike.app/registry.json, and the editor and share
# pages are linked from QR codes in the wild. Keep the URL layout stable.
#
# Usage:
#   .\scripts\deploy_website.ps1
#
# The remote is still a parameter so a one-off elsewhere needs no code change,
# but `origin` (CodeFloe) is the only one the site is served from.

param(
    [string]$Remote = "origin",
    [string]$Branch = "pages"
)

Write-Host "Deploying website/ to $Remote/$Branch ..." -ForegroundColor Cyan
git subtree push --prefix website $Remote $Branch
if ($LASTEXITCODE -eq 0) {
    Write-Host "Deployment successful." -ForegroundColor Green
    # A push is not the site changing: in September 2026 the domain still
    # pointed at the old host for days and nothing said so. Say where to look.
    Write-Host "Published copy: https://snapptechnology.codefloe.page/indoorcyclingapp/"
    Write-Host "Check the live domain too: https://indoorbike.app/"
} else {
    Write-Host "Deployment failed. Ensure all website/ changes are committed first." -ForegroundColor Red
}
