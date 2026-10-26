# Roon Bridge on Android

Run a Roon endpoint on an Android digital audio player or phone, bit-exact to a USB DAC or the device's own DAC, including DSD. You download Roon Bridge from Roon Labs on the device itself; this installer packs your copy into a small vendor-free app and installs it locally. Nothing of Roon's is distributed by this project.

**Status: experimental.** One device has been verified end to end through this app so far (Cayin N7, Android 12, PCM to the internal DAC). DSD, DoP and USB DACs have evidence from the same output libraries in a sibling project but have not yet been confirmed through Roon Bridge here. Android players differ wildly in how they let audio out bit-exactly, especially DSD, so the point of releasing this is to learn what works on *your* device. If you try it, please [report what happened](#report-what-happened), whether it worked or not.

This project is not affiliated with, endorsed by, or approved by Roon Labs. See [Licensing boundary](#licensing-boundary).

## What you need

- An arm64 Android device, Android 8.0 or newer. The internal-DAC path needs Android 7.1 or Android 9 through 14 (API 25, 28–34); the USB path needs a UAC2 DAC on a high-speed USB connection.
- A Roon Core on the same network and a Roon subscription.
- A wired output: the player's headphone or line jack, or a USB DAC.
- Permission on the device to install apps from a file ("install unknown apps") for the packager.
- Two APKs from this project: the **packager** (this repository's releases) and the **endpoint shell** (`io.github.muness.roonbridge`, from [android-roon-bridge](https://github.com/muness/android-roon-bridge)). The packager fetches the shell for you once a shell release is pinned in its catalogue.

> Until a shell release is published and pinned, the packager's catalogue points at a placeholder and the download step stops at hash verification. The [Configure the shell](#configure-the-shell) section explains the pin.

## Install

1. Install the packager APK and open it. It lists one endpoint: Roon Bridge.
2. Tap **Download and install**. Read the notice. It says, accurately, that your device is about to download Roon's software from Roon under Roon's terms, and that this app does not contain it.
3. The packager downloads the official ARMv8 Roon Bridge archive from `download.roonlabs.com`, the shell APK, and four Debian runtime libraries. Every download is checked against a pinned SHA-256; a mismatch stops the run. Nothing is uploaded anywhere.
4. It copies Roon's files into the shell on the device, checks each copied file is byte-identical to the archive, signs the result with a key generated on your device, and asks Android to install it. Confirm the install.
5. Open **Roon Bridge** on the device, tap Start, then enable the new zone in Roon. The zone is named after your device unless you change `endpoint_name`.

If Roon releases a new production build of Bridge, step 3 will fail with a hash mismatch until this project re-qualifies the new build and ships an updated catalogue. That is deliberate: the packager never trusts an unexpected download. The current pin is Roon Bridge 2.71 (build 1683), checked against the official download on 2026-10-25.

## Try it, and see what worked

The endpoint keeps a table per output (per internal DAC, or per USB DAC by vendor/product) of which formats it has actually been **granted** by Android (a real open succeeded) and which you have **heard** working. The first time a stream starts at a format nobody has answered for, the screen asks three seconds in whether it sounds right: Working / Stutters / Not working for PCM, Working / Noise / Silence for DSD. Answer honestly; "noise" on DSD makes the endpoint try the next byte layout.

Everything is also readable over HTTP on the device's LAN address, port 48620, after you tap **Allow** for your computer on the device:

```bash
curl http://PLAYER_IP:48620/verified
```

`/report` has the device, versions, sink and log; `/status` has the current DSD route and any crash offer; `/settings` lists every setting with its current value and what it accepts.

### Known devices and recipes

If your device is on the [known devices list](docs/devices.md), the endpoint applies that recipe automatically (today that is an exact match on manufacturer and model for the Cayin N7 and iBasso DX260). Otherwise it uses Qualcomm's reference DSD configuration and the usual direct PCM path, which is the most common arrangement and the first thing worth trying.

If a known recipe for a *similar* device exists, or the default does not work, you can set a recipe by hand with `POST /set` once you have enabled **Allow remote writes** on the device:

```bash
curl -X POST http://PLAYER_IP:48620/set -d 'dsd_port=vendor' -d 'dsd_native_format=13' -d 'dsd_packing=u32be'
```

[docs/devices.md](docs/devices.md) lists the settings each known recipe maps to and what each setting controls. Do not sweep DSD routes blindly: a DSD open on the wrong port has taken down a player's audio server and needed a reboot. Try one change at a time and listen.

## Report what happened

Please open a [device report](../../issues/new?template=device-report.yml) whether it worked or failed. The form asks for your device, output path, what you tried and the result, and the output of `/report`, `/verified` and `/status`. Redact the USB serial number if you see one. A report from a device nobody has tried is the single most useful thing you can give this project; a report that something *stopped* working after an Android or Roon update is the second.

What counts as "working" here is: Android granted the format on a direct path with no mixer, the rate you sent is the rate the DAC received, and it sounds right to you. Nobody has yet captured the digital output to prove bit-exactness; if you can, say so in the report.

## Why this is an installer, not a Roon distribution

Roon's published guidance on distributing Roon Bridge, from Danny Dulai in the [RoPieee thread](https://community.roonlabs.com/t/introducing-ropieee-a-roonbridge-to-go-image-for-the-raspberry-pi/24868?page=2) on May 2, 2017:

> please do not distribute it. instead, provide an installer that downloads the latest build and installs it.

This project follows that model:

- **We distribute the installer.** This repository contains packager source, configuration and synthetic tests. The packager APK contains the installation tool and its dependencies, with no Roon Bridge binaries or archive. Builds and CI do not download Roon software.
- **The user downloads Bridge directly from Roon.** After the user acknowledges the download notice, their device fetches the official ARMv8 archive from `download.roonlabs.com`. The project does not host, mirror or proxy that archive. A pinned SHA-256 check rejects an unexpected build.
- **Packaging happens on that same device.** The installer copies the user's downloaded files into a separately fetched vendor-free Android shell. Executable files receive the APK entry names Android requires; data files retain their contents in an asset archive. The packager verifies that the copied Roon files are byte-identical to the archive members. It does not patch or relink Roon's binaries.
- **The result is installed locally.** A signing key generated and kept on the user's device signs the resulting APK, and Android asks the user to confirm installation. The packager has no upload or APK sharing feature. Generated APKs containing Bridge are excluded from this repository and its releases.

The quoted guidance does not specifically address Android APK packaging and is not a certification of this project.

## Licensing boundary

The repository and packager APK contain no Roon software. Downloads happen only after the user acknowledges the notice, on their own device, from `https://download.roonlabs.com`. The packager has no upload or APK sharing feature. Vendor archive, app shell and Debian supplements require pinned SHA-256 hashes. Copied vendor files must remain byte-identical, and verification failures prevent installation.

These properties address this project's distribution of Roon binaries. They do **not** establish that repackaging is permitted. [Roon's software terms](https://roon.app/en/termsandconditions), reviewed 2026-10-13, restrict incorporation into other programs and providing copies to others (section 2.1). Their application to this Android packaging flow remains unresolved. Do not describe this project as approved, certified, or free of all licensing concerns. Users need the necessary rights; generated APKs must not be published. See [docs/LICENSE-BOUNDARY.md](docs/LICENSE-BOUNDARY.md).

## For developers

The Android application ID is `io.github.muness.roonpackager`. Generic packing internals remain for regression tests and Debian supplements; the application policy accepts only the Roon tree endpoint.

### Configure the shell

The default catalogue deliberately disables packing until a reviewed vendor-free shell is supplied. No vendor archive is fetched during build or testing.

Build a vendor-free shell from [android-roon-bridge](https://github.com/muness/android-roon-bridge), audit its contents and its dependencies' distribution obligations, then supply its HTTPS URL, independently recorded SHA-256, and version:

```bash
python3 scripts/configure-shell.py --url https://your-host.example/roon-shell.apk --sha256 YOUR_64_HEX_HASH --version YOUR_SHELL_VERSION
```

This edits the catalogue; inspect that diff before rebuilding. A new Roon release under the same download URL will fail the existing pin. Review its contents and record a new version and hash rather than enabling trust-on-first-use.

### Build and verify

Use JDK 17, Android SDK platform 35, and Gradle 8.11.1 or newer compatible with Android Gradle Plugin 8.9.2:

```bash
cd android && gradle testDebugUnitTest assembleDebug assembleRelease && cd ..
```

```bash
python3 scripts/check-source-only.py
```

```bash
python3 scripts/check-apk.py android/app/build/outputs/apk/debug/app-debug.apk
```

The release APK is unsigned and must be signed before it is distributed. The debug APK is for development. Tests use synthetic archive members; none are vendor code.

On Android, allow sideloading for this app and confirm each installation. The packager generates a signing key in its private data directory. **Uninstalling the packager or clearing its data loses that key**; an endpoint signed by the old key then needs uninstalling before an update, losing endpoint settings. A different signing key also prevents replacing an existing endpoint in place.

The packager's own diagnostics listen on TCP 48630 while the screen is open. They expose status, logs and cache clearing, plus endpoint verification-mark restoration; they cannot start packaging or installation. Mark restoration needs a shell that serves `/verified/export` and `/verified/import`; the current shell does not yet, so that action reports a failure until it does. The HTTP diagnostics listener is separate from downloads, which require HTTPS.

## Personal and commercial use

Original work is available for individuals' personal, noncommercial use under [LICENSE](LICENSE). Commercial integration, manufacturer preinstallation and organizational use require a separate written license from Muness Castle. Third-party licenses remain in effect; see [LICENSING.md](LICENSING.md) and [NOTICE.md](NOTICE.md).
