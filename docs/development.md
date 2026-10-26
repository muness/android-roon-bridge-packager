# Building the installer

Use JDK 17, Android SDK platform and build-tools 35, and Gradle 8.11.1. Keep the
project's pinned Android Gradle Plugin version.

```sh
cd android
gradle testDebugUnitTest assembleDebug assembleRelease
cd ..
python3 scripts/check-source-only.py
python3 scripts/check-apk.py android/app/build/outputs/apk/debug/app-debug.apk
python3 scripts/check-apk.py android/app/build/outputs/apk/release/app-release-unsigned.apk
```

The release APK is unsigned. Sign it before installing or distributing it, and
keep the same signing certificate for updates. To set a release version, pass
`-PappVersion=1.0.0` to Gradle.

Builds and automated tests use synthetic fixtures without downloading Roon
software. Check playback separately on an Android device: test the built-in output
and a connected USB DAC as separate outputs.

The application ID is `io.github.muness.roonpackager`. Its catalog accepts one
Roon endpoint and checks the Roon archive, Android shell, and runtime supplements
against pinned SHA-256 hashes. Packaging preserves copied Roon files byte for byte.
For an upstream update, review the new files and update their pins; keep
verification enabled.

## Update the Android shell

Download and audit the proposed shell before changing the catalog. Run
`scripts/check-shell.py` against the actual APK, including its playback-service
metadata. Record its independently verified SHA-256, then update the pin:

```sh
python3 scripts/configure-shell.py --url HTTPS_URL --sha256 VERIFIED_SHA256 --version VERSION
```

Publish the matching native-component notices and corresponding sources with the
shell. Each component keeps its own license requirements.

Maintainers can use the Python service in [feedback-service](../feedback-service/README.md)
to test the report and catalog protocol locally. Building and using the installer
does not require that service.

## Maintainer download preflight

Check the catalog and run synthetic archive tests before a release. These commands
work offline:

```sh
python3 scripts/preflight-downloads.py
python3 -m unittest discover -s scripts -p 'test_preflight_downloads.py'
```

To verify actual supplement downloads, choose a cache outside the repository:

```sh
python3 scripts/preflight-downloads.py --cache-dir /path/outside/repository/cache --check-supplements
```

This checks each Debian input's URL, SHA-256, package architecture, declared ELF
files, and SONAMEs. The glibc supplement must match the shell's glibc version.
Supplement URLs identify exact files in the official Debian Snapshot archive.

To verify a new Roon Bridge archive, run this separate maintainer check:

```sh
python3 scripts/preflight-downloads.py --cache-dir /path/outside/repository/cache --qualify-vendor
```

This reads the official archive's production VERSION files and ARM64 runtime
header without running or changing Roon code. The archive version must match the
catalog exactly; Roon Server and Remote have separate release versions. Keep this
download check out of builds and CI. Keep downloaded Roon archives and generated
APKs containing Roon software out of Git and project releases.
