# Known devices and recipes

These tables list playback tests for built-in and USB DACs. The Evidence column identifies the app used: Roon Bridge or HQPlayer NAA. NAA results describe the shared audio code; they are not Roon playback tests. Bit-perfect output has not been checked with a digital capture.

In Roon Bridge, open **More → Review and send playback results**, then tap **Send playback results**. The app downloads new recipes after review.

## Internal DAC

| Device | Android / SDK | PCM (direct) | DoP | Native DSD | Recipe | Evidence |
|---|---|---|---|---|---|---|
| Cayin N7 | 12 / 31 | Working, rates preserved (44.1 and 192 seen), zero overruns | untested | DSD64–DSD512 played cleanly | `offload` · `0x1a000001` · `u8` (built in) | PCM through Roon Bridge; DSD via NAA |
| iBasso DX260 | 11 / 30 | untested through Roon Bridge | untested | DSD64–DSD256 played cleanly; DSD512 silent; DSD1024 crashes the HAL | `vendor` · `13` · `u32be` (built in) | via NAA |
| Shanling M6 Pro | 7.1 / 25 | Packed 24-bit clean at 192 kHz; 32-bit gives static; S24_LE stutters at 192 kHz | untested | no tested recipe | NAA: packed 24-bit PCM | via NAA |
| FiiO M11S | 10 / 29 | 16-bit / 44.1 kHz heard working; some tracks later fell silent, still under investigation | untested | untested | automatic PCM format | via Roon Bridge |

## USB DAC

| DAC | Host | PCM | DoP | Native DSD | Notes | Evidence |
|---|---|---|---|---|---|---|
| Cayin RU9 | ARM64 Android, high-speed USB | yes | DoP64–256 | up to DSD512 on `alt=3 RAW_DATA` | DSD1024 exceeds endpoint bandwidth | via NAA |

## What a recipe is

A recipe is a set of audio settings for a device or DAC. The app suggests recipes based on your model, Android version, firmware and playback engine. Choose one under **More → Audio settings**, play a track and record what you hear.

If no reviewed recipe matches, start with the built-in PCM settings. For the built-in DAC, native DSD requires a reviewed recipe that matches your device and software. USB DSD support depends on the formats the DAC exposes and the USB connection bandwidth. You can also adjust settings in the app.

### DSD settings

| Setting | Values | What it does |
|---|---|---|
| `dsd_port` | `auto`, `offload`, `passthrough`, `vendor` | Which output the DSD stream opens on. `offload` is the Qualcomm `compressed_offload` mix port; `vendor` is a direct track with a vendor-numbered format and no mix port; `passthrough` is `dsd_compress_passthrough` and is never chosen automatically. |
| `dsd_native_format` | `auto` or a 32-bit number | The `audio_format_t` for the offload and vendor routes. `0x1a000001` is Qualcomm's `AUDIO_FORMAT_DSD_NATIVE`; iBasso uses `13`. |
| `dsd_format` | 32-bit number, default `0x1a000000` | The format for the passthrough port. |
| `dsd_packing` | `auto`, `u8`, `u32be`, `u32le`, `u8_lsb`, `u32be_lsb` | DSD byte layout. If you hear noise, choose **No, something’s wrong**, then **Noise / distortion — stop playback**. Choose another layout after stopping playback. |

Roon Bridge follows the PCM format sent by Roon. It ignores old rate and bit-depth overrides and excludes recipes that require them. The Shanling result above records an NAA test; its forced-bit-depth setting is not offered in Roon Bridge.

### PCM and path settings

| Setting | Values | What it does |
|---|---|---|
| `sink` | `auto`, `usb`, `internal`, `direct`, `smoke` | Output path. `auto` is USB if a usable DAC is attached, else internal. `smoke` is the Android mixer and is never bit-exact. |
| `prefer_internal` | `on`, `off` | Try the internal DAC before USB. |
| `direct_route` | `system`, `probed` | Let Android pick the output, or select the detected wired port. |
| `direct_buffer` | `stream`, `fixed` | Two ALSA periods, or a flat 400 ms buffer. |
| `direct_guard` | `on`, `off` | PCM starvation guard. |
| `verified_only` | `on`, `off` | Only offer Roon the rates and formats this device has been granted and heard working. Takes effect at the next endpoint start. |
| `dop_ceiling` | `verified`, `off` | With `verified_only`, cap advertised rates at the highest DoP carrier heard decoded. |
| `assume_high_speed` | `on`, `off` | Treat an undetermined USB bus speed as high speed. |
| `feedback` | `on`, `off` | Send playback-clock feedback from the output to the bridge. |

## Trying DSD

Choose a DSD setting from the app's available settings. Lower the player and amplifier volume
before a DSD trial, stop if you hear noise, and answer the listening prompt.
Save working PCM settings first so you can restore them if another format fails.
