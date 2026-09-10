# Authors

Indoor Bike is copyright Snapp Technology Ltd, and every source file says so.
This file answers the different question a copyright header cannot: who wrote
it.

The two are not the same, and the distinction is worth keeping. A copyright
notice records who owns the work now. Authorship records who made it, and that
does not transfer.

## Created by

**Juhani Lehtimäki** wrote the app: the ride engine and its physics, the
Bluetooth stack, the workout and route formats, the Companion API, the website,
and the Slate design system the two share.

The project was his personal work before it transferred to Snapp Technology
Ltd. The notices in the source were updated at the point of transfer rather
than at the point each file was written, so a header reading "Snapp Technology
Ltd" on a file dated well before the transfer is correct about ownership and
silent about origin. This file is the part that is not silent.

## Contributors

- **ghose** - entries in the community repository registry.

The git history is the authoritative and far more detailed record. For the
current list:

```
git shortlog -sne
```

## Why the signing certificate says something else

Release APKs are signed by a certificate whose DN is `CN=Juhani Lehtimaki`.
That is not an attribution and it has not been left behind by accident: Android
requires every update to an installed app to carry the same signing key it was
first installed with, so the key cannot be replaced without stranding every
existing install on the version it already has.

`docs/RELEASE.md` and `mobile_clients/android/fastlane/Fastfile` both name that
DN because they check builds against the real key. They describe a fact about
the certificate, not a claim about ownership, and they should keep matching the
key rather than the copyright notice.

## Licence

The whole work is under the GNU General Public License v3.0. See
[LICENSE](LICENSE).

Third-party code and fonts are credited separately in [NOTICE.md](NOTICE.md).
