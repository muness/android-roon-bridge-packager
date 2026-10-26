# Security

Report a vulnerability privately through [GitHub security advisories](../../security/advisories/new) rather than a public issue.

In scope: anything that lets the packager install something other than what its pinned catalogue describes (hash or signature bypass, downgrade, trust-on-first-use), anything that starts a download, packaging run or install without the user's action, anything that exposes the on-device signing key or a packed APK, and the diagnostics listener on TCP 48630.

The packager fetches only over HTTPS from the official Roon download host, the pinned shell URL and `deb.debian.org`, verifies every input by SHA-256 before use, verifies copied vendor files byte for byte, and has no upload path. The diagnostics listener is read-only apart from cache clearing and verification-mark restore, and runs only while the packager's screen is open.
