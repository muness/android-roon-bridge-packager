# Roon Bridge for Android

Play music from Roon through your Android device's **built-in DAC or a USB DAC**. This app installs Roon Bridge and walks you through audio setup.

**[Download the APK](https://github.com/muness/android-roon-bridge-packager/releases/latest/download/roon-bridge-packager.apk)** · [All releases](https://github.com/muness/android-roon-bridge-packager/releases)

## What you need

- An ARM64 Android phone, tablet or music player running Android 8 or newer.
- A Roon server on the same network and a Roon subscription.
- Headphones or speakers connected to the device's headphone/line output or to a USB DAC.

## Install and play

1. Download the APK, open it on your Android device, and allow installation when Android asks.
2. Open **Roon Bridge Packager** and tap **Download and install**. Read the download notice, then confirm installation.
3. Tap **Open Roon Bridge**. Choose the built-in DAC or connect a USB DAC and allow USB access when Android asks.
4. Bridge starts automatically when you open the app. In Roon, open **Settings → Audio**, enable the output, select it, and play a track.
5. On first setup, tap **Yes, sounds right** or **No, something’s wrong**. If something is wrong, choose what you hear, such as **No sound**. After that, use **Sound problem?** if you need help. Audio settings, reports and help are under **More**; the current audio format is under **More → Details**.

## USB DACs and built-in DACs

Connect a USB DAC to your Android device, choose **Use a USB DAC**, and select it in the app. Connect your headphones or amplifier to the DAC.

For the built-in DAC, connect headphones or an amplifier to the player's headphone or line output. The app suggests settings for your device and lets you try alternatives.

PCM, DoP and native DSD support varies by DAC and Android device. The [device table](docs/devices.md) lists tested formats and results.

## Updates

Install the new APK over the existing packager, then tap **Update installation**.

Keep the packager installed and keep its app data. It stores the signing key Android needs to update your copy of Roon Bridge. If you delete the key, you'll need to reinstall Bridge and set it up again.

Bridge checks for new audio recipes at startup. A recipe is a set of audio settings for a device or DAC. Updates preserve your saved working settings. Saved and built-in recipes also work offline.

## Share playback results

After trying a setting, open **More → Review and send playback results** in Roon Bridge, then tap **Send playback results**. Tell us what worked and what failed so we can improve the suggested settings for your device and similar models. Sending is optional and needs no account. The app retries when you're back online.

Reports contain your device model, software versions, audio settings, stream formats and listening answers. They exclude music metadata, account details, device names you've chosen, USB serial numbers, persistent device IDs, network addresses and raw logs. The hosting provider receives your IP address when you connect.

## Troubleshooting

- **No sound:** check the output, volume and mute settings in Roon, on the player and on your amplifier. Tap **Sound problem?** in the app to check your setup or try another setting.
- **Roon shows Fixed volume:** select **DSP Volume** in Roon’s Device Setup if you want volume control in Roon. The player’s volume buttons control Android media volume while Bridge is open; the DAC decides whether that changes its output level.
- **Noise or distortion:** stop playback and lower the volume. Record what you heard, then restore working settings or try another setting. A trial marked for noise stays stopped when you reopen the app.
- **Download failed:** install the latest packager APK and try again. The installer rejects files whose checksums don't match.
- **Installation blocked:** allow Roon Bridge Packager to install apps when Android opens its permission screen.

[Report installation problems](https://github.com/muness/android-roon-bridge-packager/issues/new/choose). Send playback results from the app.

## Downloads and licenses

The installer downloads Roon Bridge directly from Roon Labs after you accept the notice. It builds and installs your copy on your device. Do not share the resulting Roon-containing APK. Roon's terms apply; this project is independent of Roon Labs. See [Roon software and distribution](docs/LICENSE-BOUNDARY.md).

Original project code is licensed for personal, noncommercial use under [LICENSE](LICENSE). Third-party components keep their own licenses; see [NOTICE.md](NOTICE.md). [Component downloads](https://hiphi-roon-downloads.open-horizons.workers.dev/manifest.json) include license notices and corresponding source code.

[Build instructions](docs/development.md) · [Report format reference](feedback-service/README.md)
