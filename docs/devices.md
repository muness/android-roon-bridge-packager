# Known devices and recipes

These tables list playback tests for built-in and USB DACs. The Evidence column identifies the app used: Roon Bridge or HQPlayer NAA. NAA results describe the shared audio code; they are not Roon playback tests. Bit-perfect output has not been checked with a digital capture.

In Roon Bridge, open **Review and send playback results**, then tap **Send playback results**. The app downloads new recipes after review.

## Internal DAC

| Device | Android / SDK | PCM (direct) | DoP | Native DSD | Recipe | Evidence |
|---|---|---|---|---|---|---|
| Cayin N7 | 12 / 31 | Working, rates preserved (44.1 and 192 seen), zero overruns | untested | DSD64–DSD512 played cleanly | `offload` · `0x1a000001` · `u8` (built in) | PCM through Roon Bridge; DSD via NAA |
| iBasso DX260 | 11 / 30 | untested through Roon Bridge | untested | DSD64–DSD256 played cleanly; DSD512 silent; DSD1024 crashes the HAL | `vendor` · `13` · `u32be` (built in) | via NAA |
| Shanling M6 Pro | 7.1 / 25 | Packed 24-bit clean at 192 kHz; 32-bit gives static; S24_LE stutters at 192 kHz | untested | no tested recipe | `force_encoding=24` | via NAA |
| FiiO M11S | — | not tested | — | — | none | not yet tried |

## USB DAC

| DAC | Host | PCM | DoP | Native DSD | Notes | Evidence |
|---|---|---|---|---|---|---|
| Cayin RU9 | ARM64 Android, high-speed USB | yes | DoP64–256 | up to DSD512 on `alt=3 RAW_DATA` | DSD1024 exceeds endpoint bandwidth | via NAA |

## What a recipe is

A recipe is a set of audio settings for a device or DAC. The app suggests recipes based on your model, Android version, firmware and playback engine. Choose one under **See all available settings**, play a track and record what you hear.

If no reviewed recipe matches, start with the built-in PCM settings. For the built-in DAC, native DSD requires a reviewed recipe that matches your device and software. USB DSD support depends on the formats the DAC exposes and the USB connection bandwidth. You can also adjust settings in the app.

### DSD settings

| Setting | Values | What it does |
|---|---|---|
| `dsd_port` | `auto`, `offload`, `passthrough`, `vendor` | Which output the DSD stream opens on. `offload` is the Qualcomm `compressed_offload` mix port; `vendor` is a direct track with a vendor-numbered format and no mix port; `passthrough` is `dsd_compress_passthrough` and is never chosen automatically. |
| `dsd_native_format` | `auto` or a 32-bit number | The `audio_format_t` for the offload and vendor routes. `0x1a000001` is Qualcomm's `AUDIO_FORMAT_DSD_NATIVE`; iBasso uses `13`. |
| `dsd_format` | 32-bit number, default `0x1a000000` | The format for the passthrough port. |
| `dsd_packing` | `auto`, `u8`, `u32be`, `u32le`, `u8_lsb`, `u32be_lsb` | DSD byte layout. If you hear noise, choose **Something’s wrong**, then **Noise / distortion — stop playback**. Choose another layout after stopping playback. |

### PCM and path settings

| Setting | Values | What it does |
|---|---|---|
| `sink` | `auto`, `usb`, `internal`, `direct`, `smoke` | Output path. `auto` is USB if a usable DAC is attached, else internal. `smoke` is the Android mixer and is never bit-exact. |
| `prefer_internal` | `on`, `off` | Try the internal DAC before USB. |
| `force_encoding` | `auto`, `16`, `24`, `32` | Constrain the internal sink's bit depth (24 = packed). Does not change what Roon is offered. |
| `force_rate` | `auto`, `44100` … `768000` | Constrain the internal sink's rate. |
| `direct_route` | `system`, `probed` | Let Android pick the output, or pin the probed wired port. |
| `direct_buffer` | `stream`, `fixed` | Two ALSA periods, or a flat 400 ms buffer. |
| `direct_guard` | `on`, `off` | PCM starvation guard. |
| `verified_only` | `on`, `off` | Only offer Roon the rates and formats this device has been granted and heard working. Takes effect at the next endpoint start. |
| `dop_ceiling` | `verified`, `off` | With `verified_only`, cap advertised rates at the highest DoP carrier heard decoded. |
| `assume_high_speed` | `on`, `off` | Treat an undetermined USB bus speed as high speed. |
| `feedback` | `on`, `off` | USB protocol feedback records. |

## Trying DSD

Choose a DSD setting from the app's available settings. Lower the player and amplifier volume
before a DSD trial, stop if you hear noise, and answer the listening prompt.
Save working PCM settings first so you can restore them if another format fails.
