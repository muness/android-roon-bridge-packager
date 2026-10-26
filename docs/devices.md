# Known devices and recipes

What has been tried, on which device, with what result. "Through Roon Bridge" means via this project's endpoint shell. "Via NAA" means the same output libraries driven by the sibling HQPlayer NAA endpoint app or the device-lab app; it is good evidence the output path works, but not proof that Roon Bridge drives it the same way. Nothing here has been proven bit-exact by capturing the digital output.

Use **Send playback results** inside the endpoint to contribute structured results. The table below records the initial evidence; current app catalogs can add reviewed, scoped recipes.

## Internal DAC

| Device | Android / SDK | PCM (direct) | DoP | Native DSD | Recipe | Evidence |
|---|---|---|---|---|---|---|
| Cayin N7 | 12 / 31 | Working, rates preserved (44.1 and 192 seen), zero overruns | untested | DSD64–DSD512 heard clean | `offload` · `0x1a000001` · `u8` (built in) | PCM through Roon Bridge; DSD via NAA |
| iBasso DX260 | 11 / 30 | untested through Roon Bridge | untested | DSD64–DSD256 heard clean; DSD512 silent; DSD1024 crashes the HAL | `vendor` · `13` · `u32be` (built in) | via NAA |
| Shanling M6 Pro | 7.1 / 25 | Packed 24-bit clean at 192 kHz; 32-bit gives static; S24_LE stutters at 192 kHz | untested | no qualified recipe | `force_encoding=24` | via NAA |
| FiiO M11S | — | planned, PCM only | — | — | none | not yet tried |

## USB DAC

| DAC | Host | PCM | DoP | Native DSD | Notes | Evidence |
|---|---|---|---|---|---|---|
| Cayin RU9 | any arm64 host, high-speed USB | yes | DoP64–256 | up to DSD512 on `alt=3 RAW_DATA` | DSD1024 exceeds endpoint bandwidth | via NAA |

## What a recipe is

A recipe is the handful of endpoint settings that make one device's output path work. The setup guide ranks recipes by device, Android/firmware, and engine compatibility, with bundled PCM recovery when no reviewed match exists. Native DSD on an unqualified model is blocked until a reviewed exact-cohort recipe is available. Choose and try recipes on the device through **See available settings**.

Advanced settings are available in the app.

### DSD settings

| Setting | Values | What it does |
|---|---|---|
| `dsd_port` | `auto`, `offload`, `passthrough`, `vendor` | Which output the DSD stream opens on. `offload` is the Qualcomm `compressed_offload` mix port; `vendor` is a direct track with a vendor-numbered format and no mix port; `passthrough` is `dsd_compress_passthrough` and is never chosen automatically. |
| `dsd_native_format` | `auto` or a 32-bit number | The `audio_format_t` for the offload and vendor routes. `0x1a000001` is Qualcomm's `AUDIO_FORMAT_DSD_NATIVE`; iBasso uses `13`. |
| `dsd_format` | 32-bit number, default `0x1a000000` | The format for the passthrough port. |
| `dsd_packing` | `auto`, `u8`, `u32be`, `u32le`, `u8_lsb`, `u32be_lsb` | DSD byte layout. Wrong layout sounds like noise; record Noise in the guide, then explicitly choose the next applicable layout. The answer does not switch a live stream. |

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

Use the app's applicable settings list. Lower the player and amplifier volume
before a DSD trial, stop if you hear noise, and answer the listening prompt.
Save working PCM settings first so you can restore them if another format fails.
