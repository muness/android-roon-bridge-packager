# Building the installer

Use JDK 17, Android SDK platform/build-tools 35, and Gradle 8.11.1 with the project's pinned Android Gradle Plugin.

```sh
cd android
gradle testDebugUnitTest assembleDebug assembleRelease
cd ..
python3 scripts/check-source-only.py
python3 scripts/check-apk.py android/app/build/outputs/apk/debug/app-debug.apk
python3 scripts/check-apk.py android/app/build/outputs/apk/release/app-release-unsigned.apk
```

The release output is unsigned; sign it before installation or distribution. Keep the same signing certificate for subsequent updates. Builds and automated tests use synthetic fixtures and do not download Roon software.

The application ID is `io.github.muness.roonpackager`. The catalogue accepts one Roon endpoint and pins the vendor archive, Android shell, and runtime supplements by SHA-256. Packaging preserves the copied vendor files byte for byte. Do not disable verification to accommodate an upstream update.

To update a reviewed shell pin:

```sh
python3 scripts/configure-shell.py --url HTTPS_URL --sha256 VERIFIED_SHA256 --version VERSION
```

Audit the actual downloaded APK with `scripts/check-shell.py`, including its playback-service metadata, before publishing a new catalogue. Native components keep their own licensing and corresponding-source requirements.

The Python service in `feedback-service` is a local reference implementation of the report/catalog contract. It is not needed to build or use the installer.

## Maintainer download preflight

Run the offline catalogue check and synthetic archive tests before a release:

```sh
python3 scripts/preflight-downloads.py
python3 -m unittest discover -s scripts -p 'test_preflight_downloads.py'
```

To qualify actual downloads, choose a cache outside the repository and run:

```sh
python3 scripts/preflight-downloads.py --cache-dir /path/outside/repository/cache --check-supplements
```

This checks every Debian input's URL, SHA-256, package architecture, declared
ELF members and SONAMEs. The glibc supplement must match the shell's glibc
version. Supplements use content-addressed official Debian Snapshot URLs.

Vendor qualification requires a separate explicit maintainer action:

```sh
python3 scripts/preflight-downloads.py --cache-dir /path/outside/repository/cache --qualify-vendor
```

This reads the official archive's production VERSION files and ARM64 runtime
header without executing or changing vendor code. The archive's version must
match the catalogue exactly; a Server or Remote release does not establish a
Bridge version. Never run vendor qualification during builds or CI, and never
commit or publish the downloaded archive or a packed endpoint.
