# Playback evidence reference service

This local reference implementation receives explicit playback submissions and
publishes reviewed recipe catalogs using Python 3 and SQLite. Its synthetic tests
and fixtures exercise the shared wire contract without vendor binaries. Production
hosting uses the dedicated Cloudflare Worker and D1 database in
[HiPhi Cloud PR #158](https://github.com/open-horizon-labs/hiphi-cloud/pull/158). Deployment is in progress; public availability has not yet been verified. Listeners need no
account or browser. Playback and saved settings work without the service.

`POST /v1/reports` accepts the exact v1 object in `fixtures/report.json`. All fields
are required unless listed below. Unknown keys, raw logs, notes, names of endpoints,
track metadata, USB serials, URLs, arbitrary settings, duplicate attempt IDs/orders,
noninteger numeric fields, and more than 32 attempts or 128 KiB are rejected.
Optional fields are attempt `firmware`, `usbVendorId`, `usbProductId` and format
`sampleFormat`. Device model strings are bounded ASCII identifiers; firmware and
version strings are bounded tokens. Report settings additionally allow typed
`test_mode` and `assume_high_speed`; those are filtered from catalog settings,
and enabled values cannot qualify a recommended recipe. Clients must assemble fields from their typed
source, never substitute unfiltered diagnostic messages. No text pattern alone
can establish that a caller supplied an honest model identifier.

The response is `{receiptId, reportId, status:"received"}`. Reposting the same
report ID and canonical content returns the original receipt. Reusing an ID with
changed content returns 409. Corrected results use another attempt/report; accepted
bodies are immutable. Random report/receipt IDs and content digests remain as
retry tombstones after body expiry, preventing delayed retries from resurrecting
evidence. They are report identities, never stable installation identities.
A receipt confirms storage, never qualification or local
audibility. The local reference SQLite database is capped at 256 MiB on every connection. At capacity,
new uploads return retryable HTTP 503; published catalogs remain readable and
are never pruned to make room. Request bodies are retained for up to 90 days; review decisions and published
catalogs retain their technical recipes and evidence references after expiry.

## Local operation

```sh
python3 feedback_service.py --db local/evidence.sqlite3 init
python3 feedback_service.py --db local/evidence.sqlite3 serve
python3 -m unittest discover -s tests -v
```

The default listener is `127.0.0.1:8765`; `/healthz` returns a process health status.
There is no remotely accessible administrator endpoint. The CLI operates on the
local database and should only be available to maintainers. Fixture submission
with a local HTTP client is for development; the listener product flow submits
inside the Android application.

## Review and publication

```sh
python3 feedback_service.py --db local/evidence.sqlite3 list
python3 feedback_service.py --db local/evidence.sqlite3 show RECEIPT_UUID
python3 feedback_service.py --db local/evidence.sqlite3 evidence fixtures/recipe.json
```

Copy `fixtures/review.template.json` to a private working file and insert real
receipt/attempt references. Adjust the recipe's precise applicability, settings,
qualification and reason after inspection. Then run:

```sh
python3 feedback_service.py --db local/evidence.sqlite3 review local/review.json
python3 feedback_service.py --db local/evidence.sqlite3 publish
python3 feedback_service.py --db local/evidence.sqlite3 export local/public
```

Review requires cited attempts to match the complete settings and declared cohort.
Recommended recipes require opened, actual-stream, listener-confirmed Roon evidence
when their endpoint is Roon, an exact manufacturer/model/SDK/engine predicate and
explicit acknowledgment of known conflicts. A matching firmware predicate narrows
the claim further. Omitted firmware means firmware was not established, rather
than proof across every firmware. Family suggestions remain `suggested`; broad
wildcards cannot be labelled `recommended`. An internal human decision is still
responsible for appropriate format restrictions, firmware scope and physical
qualification; this service does not prove audio quality or safe native routes.

A recommended native-DSD recipe also requires `dsd-reviewed-v1`, an explicit
port/packing and the relevant format number (`dsd_format` for passthrough,
`dsd_native_format` otherwise), plus `dsdMaxMultiple` of 64, 128, 256 or 512.
The bound must not exceed a cited heard-working received native-DSD format,
derived as ALSA rate × sample word bits ÷ 44100. A heard DSD64 stream cannot
qualify DSD128. This limits candidate trials; it does not mark every lower rate
as locally heard or bypass compiled device restrictions.

The evidence command shows report counts and separate negative observations.
Exact repeats of an attempt ID and its canonical technical snapshot are grouped
into one observation, retaining every receipt reference. Changed snapshots keep
both versions and any resulting conflict. `observationSnapshotCount` describes
these distinct snapshots; it is not a count of independent attempts or devices.
Reports cannot establish independent devices. There is no automatic promotion,
vote threshold or anonymous count-based confidence score. New routes and expanded
cohorts need review. Compiled client exclusions remain authoritative even after
publication. NAA evidence cannot cite a Roon-scoped recipe. A reported open alone
does not establish listening success.

Review stores a decision without publishing. A newly received negative after
review blocks publication until a maintainer reviews the conflict. `publish` creates the next immutable
sequence from the latest reviewed rule for each recipe ID. Settings for an existing
ID/revision are immutable. Withdraw or exclude a rule with a new review; publishing
again creates a higher sequence while retaining old snapshots. Rollback uses a
new review/publication, never a lower sequence. Changing settings requires a higher
recipe revision. Scope/status changes remain in the append-only decision history.

Clients fetch `/catalog/manifest.json` and the exact `/catalog/SEQUENCE.json` it
names, checking SHA-256, schema, resolver and engine features. HTTPS authenticates
the selected service host; the digest detects corruption and does not independently
authenticate the publisher. This v1 deliberately uses HTTPS host/account trust,
not a claimed signed-release mechanism. The client keeps compatible cached/bundled
rules, freezes active attempts and preserves locally heard working settings.

## Production operations

The production implementation lives in private HiPhi Cloud:

- `edge/wrangler.playback.jsonc` declares the separate `hiphi-cloud-playback`
  Worker and `PLAYBACK_DB` database binding.
- `edge/src/playback/` owns strict validation, immutable receipts, reviewed
  publication and public report/catalog routes.
- `edge/scripts/playback-admin.mjs` is the private operator CLI. There is no
  administrator HTTP route on the public Worker.
- `docs/operations/playback-feedback.md` describes provisioning, deployment,
  provider retention and rollback through the existing cloud workflow.

The operator CLI reads its Cloudflare token from the environment, refuses other
accounts/databases and resolves only an existing dedicated database. From HiPhi
Cloud's `edge` directory, using Node 24 and installed dependencies:

```sh
node scripts/playback-admin.mjs check
node scripts/playback-admin.mjs evidence /private/recipe.json
node scripts/playback-admin.mjs review /private/review.json
node scripts/playback-admin.mjs publish
node scripts/playback-admin.mjs purge
```

Only the explicit private `evidence` command prints technical attempt bodies.
Ordinary commands print receipts, identifiers or aggregate status; failures omit
upstream response bodies, bound SQL values and credentials. Every administrative
write first runs `verify-batch` against disposable probe rows, requiring both a
successful two-statement batch and complete rollback of an invalid batch. Failed
or unsupported REST transaction behavior blocks review, publication and purge.
No environment assertion can substitute for that live probe.

The Cloudflare store bounds retained report/review/catalog payloads at 256 MiB and
lifetime receipt tombstones at 100,000. This is a logical application budget,
not a claim about physical D1 database size. Live evidence expires after 90 days;
Cloudflare recovery storage has its own provider retention. Expiry does not claim
physical secure deletion from provider backups. Published catalogs are retained,
and receipt identities remain immutable so delayed retries cannot resurrect
expired evidence. The hourly Worker schedule performs expiry without listener
accounts or browser actions.

Local tests establish contract behavior. Public deployment, provider controls and
physical listening qualification require their own recorded checks.
