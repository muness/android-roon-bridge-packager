# Contributor guidance

This is an Android Roon-only packager derived from android-endpoint-manager. Preserve NOTICE.md upstream attribution; corrected original-work terms are in LICENSE.

- Never add Roon binaries, archives, generated APKs, endpoint shells, signing keys or credentials to Git or releases. Run `python3 scripts/check-source-only.py` before commits; it scans index contents and all reachable history.
- The application catalogue must contain exactly the Roon tree endpoint. Production policy requires official HTTPS Roon downloads and pinned SHA-256 hashes for all inputs. Never enable pin-on-first-use or bypass byte/signature verification.
- Do not claim licensing permission, certification, endorsement, or absence of all legal concerns. Read docs/LICENSE-BOUNDARY.md. Direct downloads do not settle incorporation restrictions.
- Builds and tests must not download vendor runtimes. Use synthetic fixtures. Generic packing internals remain for regression tests and Debian supplements; the app policy must reject other endpoints.
- Default shell is intentionally unconfigured. Configure only a reviewed vendor-free shell and independently recorded hash. Never replace the placeholder with a guessed release or private LAN URL.
- Keep install/download actions user initiated. Do not expose them over diagnostics or publish packed endpoints. User acknowledgement must precede each packaging run.
- Run `gradle testDebugUnitTest assembleDebug assembleRelease` in android and `python3 scripts/check-apk.py PATH_TO_PACKAGER_APK` before reporting a build verified. Report physical-device verification separately.

## Licensing policy

Muness-owned original work is offered under the Personal Noncommercial License in LICENSE. Preserve all third-party notices and their independent grants. Do not add blanket MIT declarations to new original work. The owner confirmed no original-work distribution occurred; prior project-level MIT labels were mistaken. Preserve third-party grants and update dependency pins when carrying the correction into consumers. Commercial or organizational use of original work needs a separate written license. Do not apply this policy to third-party GPL/LGPL/MIT/BSD/Apache code or override required recipient rights.
