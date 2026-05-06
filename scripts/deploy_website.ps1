# Deploy the website directory to Codeberg Pages
# This pushes the contents of the 'website' folder to the 'pages' branch without switching branches.

Write-Host "Deploying website directory to Codeberg Pages..." -ForegroundColor Cyan
git subtree push --prefix website origin pages
if ($LASTEXITCODE -eq 0) {
    Write-Host "Deployment successful!" -ForegroundColor Green
} else {
    Write-Host "Deployment failed. Ensure you have committed all changes to the 'website' directory before running this script." -ForegroundColor Red
}
