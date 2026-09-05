Indoor Bike 1.8.2

Zwift Click controllers work.

Fixed
- **A Zwift Click's buttons now work.** Zwift's controllers come in two protocol families and the app only ever spoke the older one. A Click from the encrypted generation pairs, reports itself connected, acknowledges every byte the app writes to it and acts on none of them, so every button press went nowhere and nothing anywhere reported a failure. The app now performs the key exchange those units expect: an ephemeral P-256 handshake, a session key derived with HKDF-SHA256, and AES-CCM on every frame after it. Controllers that speak the older plaintext protocol are unaffected, and which one you are holding is settled by what it answers rather than guessed from its name. Zwift Ride is covered by the same work; Zwift Play, whose handshake differs again, still is not.

- **A controller that cannot work no longer says it is working.** Settings read "Connected · in-ride ± control" for anything that paired, whether or not its buttons could ever do anything. It now reads "Connected but not answering" once the handshake has run out of attempts, in the same red the heart rate monitor uses when it connects and then sends nothing. Not being able to tell those two apart is why this went unnoticed for so long.

- **Waking a controller no longer changes your target.** A sleeping Click is woken by pressing a button, and the first message of the new session reports that button as still held. That was read as a press, so reconnecting nudged your wattage in whichever direction you happened to reach for. The message that opens a session is now taken as a starting position rather than a change.

- **No more phantom presses the moment a controller connects.** The controller starts sending on its data channel a few milliseconds before its handshake reply arrives, and those first messages were being read as button state before there was a session to decrypt them with. Encrypted bytes look like a random button state about one message in 256, which showed up as a + and a - landing in the same instant. Nothing is interpreted now until the app knows which protocol the controller speaks.

Notes
- Nothing to redo if you already have a Click paired. Update, wake the controller, and the buttons work.
- 1.7's notes said a silent Click was fixed. It was not. That release corrected the order of the handshake and the subscriptions, which was a real bug, but it was not what kept the Click quiet, and no Click started working because of it. The claim was wrong and this release supersedes it.
- With no controller paired, or with one the app cannot speak to, the in-app ± buttons work exactly as before.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.
- **Development has moved to [CodeFloe](https://codefloe.com/SnappTechnology/IndoorCyclingApp).** Codeberg remains a release mirror for now, so nothing breaks today. When you get a chance, install ObtainX and point it at the CodeFloe URL: Obtainium cannot track CodeFloe, so this is a change of app as well as a change of address.

Signed with the project's release key; `versionCode 13`.
