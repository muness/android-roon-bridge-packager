# Playback report service reference

This Python 3 and SQLite service lets maintainers test playback reports, review
results, and publish audio settings. Tests use synthetic fixtures without Roon
binaries. The Android app already has a hosted service address; listeners send
results inside Roon Bridge without running this service, creating an account, or
opening a browser. Reports cover attempts with built-in outputs and USB DACs.
Playback and saved settings work offline.

## Report format

`POST /v1/reports` accepts the v1 object in `fixtures/report.json`, with at most
32 attempts and 128 KiB per report. All fields are required except attempt
`firmware`, `usbVendorId`, `usbProductId` and format `sampleFormat`.

The service rejects unknown keys, raw logs, notes, endpoint names, track metadata,
USB serial numbers, URLs, arbitrary settings, duplicate attempt IDs or orders,
and noninteger numeric fields. Device identifiers use bounded ASCII strings;
firmware and version fields use bounded tokens. Clients must build reports from
typed fields rather than diagnostic messages. Checking an identifier's format
does not verify the reported device's identity.

Reports also accept typed `test_mode` and `assume_high_speed` settings. The service
removes these from catalog settings; an attempt with either enabled cannot qualify
a recommended recipe.

The response is `{receiptId, reportId, status:"received"}`. Sending the same report
ID and canonical content again returns the original receipt. Reusing that ID with
changed content returns HTTP 409. Accepted reports cannot change; corrections need
a new attempt and report. A receipt confirms storage only, not successful playback
or approval of the settings.

The local SQLite database has a 256 MiB limit, set on every connection. When full,
new uploads return retryable HTTP 503. Published catalogs remain readable; the
service does not delete them to make room.

## Local operation

```sh
cd feedback-service
python3 feedback_service.py --db local/evidence.sqlite3 init
python3 feedback_service.py --db local/evidence.sqlite3 serve
python3 -m unittest discover -s tests -v
```

The service listens on `127.0.0.1:8765` by default. `/healthz` reports process
health. Keep the CLI available only to maintainers: it reads and changes the local
database, and there is no remote administrator endpoint. Use a local HTTP client
to submit fixtures during development. Listeners submit through the Android app.

## Review and publication

```sh
python3 feedback_service.py --db local/evidence.sqlite3 list
python3 feedback_service.py --db local/evidence.sqlite3 show RECEIPT_UUID
python3 feedback_service.py --db local/evidence.sqlite3 evidence fixtures/recipe.json
```

Copy `fixtures/review.template.json` to a private working file. Cite the receipts
and attempts you reviewed, then set the recipe's device scope, settings,
qualification, and reason. Run:

```sh
python3 feedback_service.py --db local/evidence.sqlite3 review local/review.json
python3 feedback_service.py --db local/evidence.sqlite3 publish
python3 feedback_service.py --db local/evidence.sqlite3 export local/public
```

Every cited attempt must match the recipe's complete settings and device scope.
A `recommended` recipe must name an exact manufacturer, model, Android SDK, and
audio engine version. For a Roon recipe, the cited Roon stream must have opened,
reported its actual format, and received a working listening answer. The reviewer
must acknowledge known conflicts.

Add a firmware match to limit the recipe to that firmware. Leaving it out means
firmware was not established; it does not confirm support across all firmware.
Device-family rules remain `suggested`; broad wildcards cannot be `recommended`.
The maintainer must review format limits, firmware scope, and physical playback
results before recommending settings. Reports alone do not verify audio quality
or native output safety.

A recommended native-DSD recipe also requires `dsd-reviewed-v1`, an explicit
port/packing and the relevant format number (`dsd_format` for passthrough,
`dsd_native_format` otherwise), plus `dsdMaxMultiple` of 64, 128, 256 or 512.
The maximum must not exceed a cited native-DSD format that the listener heard
working, calculated as ALSA rate × sample word bits ÷ 44100. A working DSD64 stream
cannot qualify DSD128. This maximum limits which settings the app can try; each
lower rate still needs a listening check, and compiled device restrictions still
apply.

The `evidence` command shows report counts and lists negative results separately.
It groups repeats with the same attempt ID and canonical technical content into
one observation, keeping every receipt reference. If the content changes, it keeps
both versions and any conflict. `observationSnapshotCount` counts these distinct
snapshots, not independent attempts or devices. Reports cannot establish how many
independent devices contributed.

A maintainer must approve new output routes and broader device coverage. The
service never promotes recipes through votes or a confidence score. Client
exclusions still apply after publication. NAA evidence cannot support a Roon-scoped
recipe, and opening an output does not establish that the listener heard music.

`review` saves a decision without publishing it. A new negative result blocks
publication until a maintainer reviews the conflict. `publish` creates the next
immutable catalog sequence using the latest reviewed rule for each recipe ID.

To change settings, increase the recipe revision. To withdraw or exclude a rule,
submit a new review and publish again. To roll back, publish another review with
the desired rules. Sequence numbers always increase, old snapshots remain, and
the decision history keeps every scope and status change. Settings cannot change
within an existing recipe ID and revision.

Clients fetch `/catalog/manifest.json` and the `/catalog/SEQUENCE.json` it names.
They check SHA-256, schema, resolver version, and audio engine features. HTTPS
authenticates the configured host; SHA-256 detects corruption. Catalogs have no
separate publisher signature, so v1 trusts the service host and its account.
Clients keep compatible cached and bundled rules, hold settings fixed during an
active attempt, and preserve settings the listener heard working.

## Retention

The hosted service retains live report evidence for 90 days; the local reference
service retains report bodies for up to 90 days. Recovery copies have their own
retention window. Review decisions and published catalogs keep their technical
recipes and evidence references after reports expire.

Random report IDs, receipt IDs, and content digests also remain after body expiry
so delayed retries cannot recreate expired evidence. These identify reports, not
installations.
