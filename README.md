# Roon Bridge for Android

Turn an Android music player or phone into a Roon output, using its built-in DAC or a connected USB DAC. This app installs Roon Bridge on your device and helps you choose working audio settings.

**[Download the APK](https://github.com/muness/android-roon-bridge-packager/releases/latest/download/roon-bridge-packager.apk)** · [All releases](https://github.com/muness/android-roon-bridge-packager/releases)

## What you need

- An ARM64 Android device running Android 8 or newer.
- A Roon server on the same network and a Roon subscription.
- Wired headphones, a line output, or a compatible USB DAC.

Audio format support depends on your player and DAC. See [known devices](docs/devices.md).

## Install and play

1. Download the APK above, open it on your Android device, and allow installation when Android asks.
2. Open **Roon Bridge Packager** and tap **Download and install**. Accept the download notice and confirm installation of Roon Bridge.
3. Tap **Open Roon Bridge — set up audio**. Choose your player's output or USB DAC and try the suggested settings.
4. Start Bridge. In Roon, open **Settings → Audio**, enable the new output, select it, and play a track.
5. Answer **Sounds right**, **No sound**, or **Something's wrong** in the app. If needed, try another option under **See available settings**. **Restore working settings** returns to settings that worked on this output.

The installer downloads everything it needs. You do not need to install a separate shell, enter a server address, or use a browser to configure audio.

## Updates

Install a newer packager APK over the existing app, then tap **Update installation** to update Roon Bridge.

Keep the packager installed and do not clear its data: it holds the signing key used for your locally installed Roon Bridge. Losing that key can require reinstalling Bridge and setting it up again.

Audio recipes update when Bridge starts. Saved working settings stay in place, and playback can use saved or bundled recipes while offline.

## Share playback results

After trying a setting, tap **Send playback results** inside Roon Bridge. Both successful and unsuccessful attempts help improve settings for similar players and DACs. Submission is optional, needs no account, and retries if you are offline.

Reports include the device model, software versions, audio settings, stream formats and your listening answers. They do not include music metadata, account details, endpoint names, USB serial numbers, persistent device identifiers, network addresses or raw logs. Network infrastructure still handles normal connection information.

## Troubleshooting

- **No sound:** check the selected output, player/amplifier volume and mute, then try another suggested setting in the app.
- **Noise or distortion:** stop playback, lower the volume, and report what you heard before trying another setting.
- **Download failed:** use the newest packager APK and try again. Downloads are checked against known hashes; unexpected files are rejected.
- **Installation blocked:** allow Roon Bridge Packager to install apps when Android opens its permission screen.

For installation problems, [open an issue](https://github.com/muness/android-roon-bridge-packager/issues/new/choose). Submit playback results in the app.

## About the download

The installer contains no Roon software. Your device downloads Roon Bridge directly from Roon Labs after you accept the notice, then packages and installs your copy locally. Do not distribute the resulting Roon-containing APK. This project is not affiliated with or endorsed by Roon Labs; their software terms apply. See [licensing details](docs/LICENSE-BOUNDARY.md).

Original project work is available for personal, noncommercial use under [LICENSE](LICENSE). Third-party licenses remain in effect; see [NOTICE.md](NOTICE.md). Native-component notices and corresponding sources are provided with the [downloaded components](https://hiphi-roon-downloads.open-horizons.workers.dev/manifest.json).

[Build instructions](docs/development.md) · [Report format reference](feedback-service/README.md)
