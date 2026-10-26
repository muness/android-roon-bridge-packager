# Contributing

The most valuable contribution is a [device report](../../issues/new?template=device-report.yml): what happened on a device, with the endpoint's `/verified`, `/status` and `/report` output. Successes matter as much as failures, and "it stopped working after an update" matters most.

## Proposing a recipe

A recipe is a set of endpoint settings that makes an output path work on a device (see [docs/devices.md](docs/devices.md)). To propose one for the known list, open a device report that includes:

- the exact `Build.MANUFACTURER` and `Build.MODEL` from `/report`, the Android release, SDK and build;
- the settings, as `POST /set` values or the `--- overrides ---` section of `/report`;
- the `/verified` table showing the rows granted and heard working;
- which jack or DAC, and whether you compared against another known-good source.

A recipe is listed as known after two independent reports agree or the maintainer has reproduced it on hardware. One report gets it listed as a candidate. A report that contradicts a known recipe is not noise; it usually means a firmware or Android difference, and the fingerprint tells us which.

## Code and docs

- Never add Roon binaries, archives, generated APKs, endpoint shells, signing keys or credentials. `python3 scripts/check-source-only.py` scans the index and all reachable history and must pass.
- Builds and tests must not download vendor software. Tests use synthetic fixtures.
- The catalogue holds exactly one endpoint, Roon Bridge, with pinned SHA-256 hashes for every input. No trust-on-first-use.
- Install and download actions stay user-initiated with an acknowledgement each run.
- Before a pull request: `cd android && gradle testDebugUnitTest assembleDebug assembleRelease`, then `python3 scripts/check-apk.py` on both APKs.
- Do not add provenance narrative or audit history to user-facing docs. Do not describe the project as approved, certified or endorsed by Roon Labs.

Original work in this repository is under the [Personal Noncommercial License](LICENSE). By contributing you agree your contribution is offered under the same terms. Third-party code keeps its own license and notices; see [NOTICE.md](NOTICE.md).
