# Android Roon Bridge Packager

**Roon's published guidance: distribute an installer that downloads Bridge, rather than distributing Bridge itself.** In the [RoPieee discussion about Roon Bridge distribution](https://community.roonlabs.com/t/introducing-ropieee-a-roonbridge-to-go-image-for-the-raspberry-pi/24868?page=2), Danny Dulai wrote on May 2, 2017:

> please do not distribute it. instead, provide an installer that downloads the latest build and installs it.

This repository and its packaging flow follow that installer model:

- **We distribute the installer.** This repository contains packager source, configuration and synthetic tests. The packager APK contains the installation tool and its dependencies, with no Roon Bridge binaries or archive. Builds and CI do not download Roon software.
- **The user downloads Bridge directly from Roon.** After the user acknowledges the download notice, their Android device fetches the official ARMv8 archive from `download.roonlabs.com`. The project does not host, mirror or proxy that archive. A pinned SHA-256 check rejects an unexpected build.
- **Packaging happens on that same device.** The installer copies the user's downloaded files into a separately fetched vendor-free Android shell. Executable files receive the APK entry names Android requires; data files retain their contents in an asset archive. The packager verifies that the copied Roon files are byte-identical to the archive members. It does not patch or relink Roon's binaries.
- **The result is installed locally.** A signing key generated and kept on the user's device signs the resulting APK, and Android asks the user to confirm installation. The packager has no upload or APK sharing feature. Generated APKs containing Bridge are excluded from this repository and its releases.

Android's installable APK is the local installation mechanism: the user obtains Roon's software from Roon, and the installer prepares it for use on that user's device. This follows the quoted guidance's distinction between distributing an installer and distributing Bridge. The statement does not specifically address Android APK packaging or constitute certification of this project.

An independent Android tool that downloads the user's copy of Roon Bridge directly from Roon Labs, packs it into a separately obtained Android endpoint shell, verifies the copied bytes, signs locally, and asks Android to install it. Only Roon Bridge is offered.

The Android application ID is `io.github.muness.roonpackager`.

## Licensing boundary

The repository and packager APK contain no Roon software. Downloads happen only after the user acknowledges the notice, on their own device, from `https://download.roonlabs.com`. The packager has no upload or APK sharing feature. Vendor archive, app shell and Debian supplements require pinned SHA-256 hashes. Copied vendor files must remain byte-identical, and verification failures prevent installation.

These properties address this project's distribution of Roon binaries. They do **not** establish that repackaging is permitted. [Roon's software terms](https://roon.app/en/termsandconditions), reviewed 2026-10-13, restrict incorporation into other programs and providing copies to others (section 2.1). Their application to this Android packaging flow remains unresolved. Do not describe this project as approved, certified, or free of all licensing concerns. Users need the necessary rights; generated APKs must not be published. See [LICENSE-BOUNDARY.md](docs/LICENSE-BOUNDARY.md).

## Configure the shell

The upstream catalogue points to a private LAN URL and no public shell release was available when this repository was created. That URL and its old hash have been removed. The default catalogue deliberately disables packing until a reviewed vendor-free shell is supplied. No vendor archive is fetched during build or testing.

Build a vendor-free shell from [android-roon-bridge](https://github.com/muness/android-roon-bridge), audit its contents and its dependencies' distribution obligations, then supply its HTTPS URL, independently recorded SHA-256, and version:

```sh
python3 scripts/configure-shell.py --url https://your-host.example/roon-shell.apk --sha256 YOUR_64_HEX_HASH --version YOUR_SHELL_VERSION
```

This edits the catalogue; inspect that diff before rebuilding. A new Roon release under the same download URL will fail the existing pin. Review its source and record a new version/hash rather than enabling trust-on-first-use. The inherited Roon pin is build 1683 and has not been requalified against today's download.

## Build and verify

Use JDK 17, Android SDK platform 35, and Gradle 8.11.1 or newer compatible with Android Gradle Plugin 8.9.2:

```sh
cd android
gradle testDebugUnitTest assembleDebug assembleRelease
cd ..
python3 scripts/check-source-only.py
python3 scripts/check-apk.py android/app/build/outputs/apk/debug/app-debug.apk
```

The release APK is unsigned. The debug APK is for development. Tests use synthetic archive members, including inherited generic single-member fixtures; none are vendor code. Generic archive support remains because Debian supplements use `.deb`; the application policy accepts only the Roon tree endpoint.

On Android, allow sideloading for this app and confirm each installation. The endpoint is installed separately as `io.github.muness.roonbridge`. The packager generates a signing key in its private data directory. **Uninstalling the packager or clearing its data loses that key**; an endpoint signed by the old key then needs uninstalling before an update, losing endpoint settings. A different signing key also prevents replacing an existing endpoint in place.

Inherited diagnostics listen on TCP 48630 while the screen is open. They expose status/logs and cache clearing, plus endpoint verification-mark restoration; they cannot start packaging or installation. The HTTP diagnostics listener is separate from downloads, which require HTTPS. This is experimental software; no current physical-device test or endpoint compatibility claim is made.

## Personal and commercial use

Original work is available for individuals’ personal, noncommercial use under [LICENSE](LICENSE). Commercial integration, manufacturer preinstallation and organizational use require a separate written license from Muness Castle. Third-party licenses remain in effect; see [LICENSING.md](LICENSING.md).
