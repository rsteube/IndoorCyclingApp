# Indoor Bike App Website & Editor

This directory contains the promotional website and workout editor for the Indoor Bike App.

## Deployment to Codeberg Pages

The website is hosted using Codeberg Pages. The content of this directory is pushed to the `pages` branch of the Codeberg repository.

**To deploy an update:**
1. Make your changes and commit them to the `main` branch.
2. Run the deployment script from the project root:
   ```powershell
   .\scripts\deploy_website.ps1
   ```
   Or run the git command manually:
   ```powershell
   git subtree push --prefix website origin pages
   ```

The `.domains` file inside this directory ensures the site is served from `indoorbike.app`.
