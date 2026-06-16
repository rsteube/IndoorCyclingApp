Indoor Bike 1.2.1

Fixed
- **ERG mode on the Zwift Hub (and other strict FTMS trainers)**: these trainers reject target-power commands with "Control Not Permitted" unless control has genuinely been granted first. The app now takes control (FTMS Request Control) together with each target-power write until the trainer confirms the grant, and automatically re-requests control if a command is ever rejected, instead of giving up. If control still can't be obtained, the error now explains the usual cause: another app (e.g. Zwift) is connected to the trainer and holding control, so close it and re-pair.

Install
- Add this repo to [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 4`.
