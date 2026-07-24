# Contributing to GitVantage

Thanks for your interest in improving GitVantage! Contributions of all kinds —
bug reports, fixes, features, docs — are welcome.

## License of contributions

GitVantage is licensed under the **GNU General Public License v3.0-or-later**
(see [LICENSE](LICENSE)). By submitting a contribution you agree that it is
licensed under those same terms (inbound = outbound).

## Developer Certificate of Origin (DCO)

Instead of a CLA, this project uses the [Developer Certificate of Origin](https://developercertificate.org/).
It's a lightweight statement that you wrote the patch (or otherwise have the
right to submit it) under the project's license.

To certify it, **sign off** every commit — add a `Signed-off-by` line that
matches your real name and email:

```
Signed-off-by: Your Name <you@example.com>
```

Git can add this automatically:

```bash
git commit -s
```

The full text you are certifying:

```
Developer Certificate of Origin
Version 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I have
    the right to submit it under the open source license indicated in
    the file; or
(b) The contribution is based upon previous work that, to the best of my
    knowledge, is covered under an appropriate open source license and I
    have the right under that license to submit that work with
    modifications, whether created in whole or in part by me, under the
    same open source license (unless I am permitted to submit under a
    different license), as indicated in the file; or
(c) The contribution was provided directly to me by some other person
    who certified (a), (b) or (c) and I have not modified it.
(d) I understand and agree that this project and the contribution are
    public and that a record of the contribution (including all personal
    information I submit with it, including my sign-off) is maintained
    indefinitely and may be redistributed consistent with this project or
    the open source license(s) involved.
```

## New source files

Add the SPDX header to the top of every new Kotlin file:

```kotlin
// SPDX-FileCopyrightText: <year> <your name>
// SPDX-License-Identifier: GPL-3.0-or-later
```

If you add or bump a dependency, update
[THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md) and make sure the new
license is GPL-3.0-compatible.

## Building & running

```bash
./gradlew run          # run it
./gradlew build        # compile + test
```

## A note on the name

The GitVantage **name and branding** are not part of the GPL grant. You're free
to fork the code under the GPL; please use a different name for a redistributed
fork so users aren't confused about its origin.
