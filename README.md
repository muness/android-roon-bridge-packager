# Roon Bridge on Android

Run a Roon endpoint on an Android digital audio player or phone using a USB DAC or the device's own DAC, with direct PCM and device-dependent DSD support. You download Roon Bridge from Roon Labs on the device itself; this installer packs your copy into a small vendor-free app and installs it locally. Nothing of Roon's is distributed by this project.

**Status: experimental.** One device has been verified end to end through this app so far (Cayin N7, Android 12, PCM to the internal DAC). DSD, DoP and USB DACs have evidence from the same output libraries in a sibling project but have not yet been confirmed through Roon Bridge here. Android players differ wildly in how they let audio out bit-exactly, especially DSD, so the point of releasing this is to learn what works on *your* device. If you try it, please [send playback results](#send-playback-results), whether it worked or not.

This project is not affiliated with, endorsed by, or approved by Roon Labs. See [Licensing boundary](#licensing-boundary).

## What you need

- An arm64 Android device, Android 8.0 or newer. The internal-DAC path needs Android 7.1 or Android 9 through 14 (API 25, 28–34); the USB path needs a UAC2 DAC on a high-speed USB connection.
- A Roon Core on the same network and a Roon subscription.
- A wired output: the player's headphone or line jack, or a USB DAC.
- Permission on the device to install apps from a file ("install unknown apps") for the packager.
- Two APKs from this project: the **packager** (this repository's releases) and the **endpoint shell** (`io.github.muness.roonbridge`, from [android-roon-bridge](https://github.com/muness/android-roon-bridge)). The packager fetches the shell for you once a shell release is pinned in its catalogue.

> The catalogue pins the audited vendor-free shell and verifies its SHA-256 before packaging. Its native-component licenses and matching source archive are available in the [download manifest](https://hiphi-roon-downloads.open-horizons.workers.dev/manifest.json).

## Install

1. Install the packager APK and open it. It lists one endpoint: Roon Bridge.
2. Tap **Download and install**. Read the notice. It says, accurately, that your device is about to download Roon's software from Roon under Roon's terms, and that this app does not contain it.
3. The packager downloads the official ARMv8 Roon Bridge archive from `download.roonlabs.com`, the shell APK, and four Debian runtime libraries. Every download is checked against a pinned SHA-256; a mismatch stops the run. Installation does not upload your runtime or generated APK. Playback results are sent separately only when you choose to submit them in the endpoint app.
4. It copies Roon's files into the shell on the device, checks each copied file is byte-identical to the archive, signs the result with a key generated on your device, and asks Android to install it. Confirm the install.
5. Open **Roon Bridge** on the device, tap Start, then enable the new zone in Roon. The zone is named after your device unless you change `endpoint_name`.

If Roon releases a new production build of Bridge, step 3 will fail with a hash mismatch until this project re-qualifies the new build and ships an updated catalogue. That is deliberate: the packager never trusts an unexpected download. The current pin is Roon Bridge 2.71 (build 1683), checked against the official download on 2026-10-25.

## Get audio working

After installation, tap **Open Roon Bridge — set up audio**. In Roon Bridge, choose the player's wired output or your USB DAC. The guide offers a matching recipe or a general PCM starting point. Start Bridge, enable its named zone in Roon, and play a track.

Answer what you hear: **Sounds right**, **No sound**, or **Something's wrong**. A successful Android open alone does not complete setup. If a setting fails, the app saves the result and offers another applicable PCM setting. **See available settings** shows the full candidate list, prior results, and reasons a setting cannot be tried. DSD trials require an explicit choice; the app never sweeps vendor format numbers. **Restore working settings** returns to a saved configuration for the output and firmware.

Working PCM does not establish that other rates, DoP, or native DSD work. The stream shown on screen is what arrived from Roon; its format is recorded with the answer. Nothing here has been proven bit-exact by a digital capture.

### Recipes and updates

Each endpoint includes fallback recipes and checks its release-configured service for updates when its service starts. Updates change available suggestions without changing an active trial or saved working settings. Offline startup uses a valid local catalog or the bundled recipes.

Matching distinguishes exact player/Android/firmware evidence from broader vendor suggestions. Reports from NAA retain that provenance. Reviewed native DSD recipes carry explicit device and engine scope plus a maximum multiple; the opener enforces that bound and existing known-device limits. A compatible catalog can add a reviewed device recipe without repackaging the endpoint. New native mechanisms still require an app update.

[Known-device evidence](docs/devices.md) describes the initial observations. A match is a recommendation, not a local listening confirmation.

## Send playback results

Tap **Send playback results** inside Roon Bridge after a listening attempt. The app assembles and submits structured results, including failed attempts before a success. There is no browser form, account, log copying, or file attachment. Pending submissions persist locally and retry when connectivity returns; their status distinguishes queued results from a received report. Sending results never blocks listening.

The report contains player/model and normalized firmware identifiers, app/engine/recipe versions, output type and USB vendor/product numbers where applicable, audio settings, source-stream formats, technical outcomes, and your listening answers. The observed DSD route is included when known. It excludes track metadata, user-entered endpoint names, account information, serials, persistent device identifiers, network addresses, and raw logs. The receiving network infrastructure necessarily handles connection metadata; the deployment disables request/access-body logging.

Local history is bounded to the latest 256 attempts; working snapshots are retained separately. A submission contains that available history in bounded batches. Reports are deduplicated and reviewed by device/firmware/configuration before changing recommendations. A received receipt does not mean that a recipe has been qualified, and report counts are not unique-device counts.

The receiving service and release configuration are documented in [feedback-service/README.md](feedback-service/README.md). Production hosting uses the dedicated Cloudflare service in [HiPhi Cloud PR #158](https://github.com/open-horizon-labs/hiphi-cloud/pull/158). The service is deployed and its remote transaction checks pass; builds without a configured service retain queued results. The installer downloads the audited shell from Cloudflare and verifies its recorded hash.

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
