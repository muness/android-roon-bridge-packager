# Audio-stack license correction — 2026-10-13

The owner confirmed that no original project work had been shared or distributed and that ChatGPT development sessions had mistakenly inserted MIT project declarations. Those declarations are corrected to Personal Noncommercial License 1.0 for Muness-owned original work. Upstream licenses remain intact. No repository history was rewritten.

| Repository | Correction commit | Location |
| --- | --- | --- |
| android-direct-dac | `71f2e1a` | main |
| android-usb-dac | `04b1dba` | main |
| android-dac-discovery | `502678a` | main |
| android-alsa-host | `2cc523d` | main |
| android-naa | `605bce0` | main |
| android-alsa-endpoints | `3943896` | main |
| android-roon-bridge | `fa63be0` | main |
| android-endpoint-manager | `08f1481` | main |
| android-roon-packager | `2a3402e` | Local only |

HiPhi Endpoints (`unprocessed-endpoints`) carries the same correction specifically in `output/`, alongside refreshed vendor pins, on branch `codex/v1-naa-small-ring`. Other protocol engines and third-party components retain their respective licenses. Unrelated working changes were left untouched.

Two older android-alsa-host revisions used by standalone DAC libraries received license-only corrections on `license/personal-use-direct` and `license/personal-use-usb`, preserving their original implementation. Consumer pins now reference corrected revisions. Git submodule pointer changes do not import vendor binaries.

Validation: source-only checks passed in all nine Android repositories; whitespace checks passed; executable C/C++/Java content is unchanged after removing comments; upstream source headers remain byte-for-byte intact; existing repository remote heads were checked after pushing. No runtime tests were repeated because these changes only affect licenses, documentation, source comments and license-only dependency revisions. The new packager remains local and unpublished.
