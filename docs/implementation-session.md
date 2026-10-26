# Playback setup and shared recipe learning — execution session

## Aim

Users get Roon music audibly working on their chosen wired output, recover from unsuccessful settings inside the app, and contribute technical results that improve setup for related devices. The mechanism hypothesis is guided, attributable trials plus in-app submission and reviewed distribution of scoped recommendations. Current setup exposes settings/HTTP diagnostics; desired setup needs no browser/account/log copying. Success signal: one setup session captures failures and heard success, submits with retry, is reviewed into a catalog, and a second endpoint uses that catalog without overwriting working settings. Human hearing and actual hardware qualification remain human signals. Guardrails: source-only licensing; no raw personal content; distinct grants/hearing/faults; no blind DSD sweeps; offline working setup preserved. Misunderstanding signal: a feed or report export ships without the complete loop.

## Problem Space

Three independent Sol passes cover user activation/recovery, system reliability/distribution, and evidence/operations. Existing shared endpoint module owns most runtime behavior. Packager owns installation; bridge owns Roon-specific launch; direct-dac owns native route boundaries. Hardware truth is weaker than source-code truth. No deployed receiving-service origin is yet established; implement and exercise a concrete local service plus deployment configuration, then resolve hosting from existing infrastructure before claiming operational availability.

## Problem Weave

**Independence:** three independent Sol workers; Luna unavailable in exposed model list. Frames returned before cross-worker interface coordination.
**Shared core:** audible success, reproducible recovery, in-app evidence, and scoped learning must work together.

| ID | Parent | Depth | Layer | Strategy / tactic | N / PA | Owner / review trigger | Disposition |
|---|---|---|---|---|---|---|---|
| U1 | root | 1 | User | Choose identifiable output / native guided selection | Wrong output invalidates trials; app can enumerate | UI / output changes | selected |
| U2 | root | 1 | User | Establish audible playback / listening loop | Open does not prove sound; listener can judge | UI / each format/config change | selected |
| U2.1 | U2 | 2 | User | Recover / bounded applicable alternatives | Default may fail; recipes narrow candidates | Runtime / exhaustion or fault | selected |
| U2.2 | U2 | 2 | User | Preserve success / saved complete settings | Exploration must be reversible; persistence exists | Runtime / engine or firmware changes | selected |
| U3 | root | 1 | User | Contribute / one-action in-app submission | Learning needs outcomes; allowlisted metadata is useful | Feedback / failed receipt | selected |
| U4 | root | 1 | User | Benefit later / startup catalog | Collection alone has no payoff; local matcher can use data | Catalog / stale/incompatible update | selected |
| S1 | root | 1 | System | Available suggestions / atomic async catalog | Network may fail; catalogs are bounded | Catalog / startup failure | selected |
| S1.1 | S1 | 2 | System | Compatibility / schema and engine gates | Partial application changes meaning; typed schema | Catalog / schema changes | selected |
| S1.2 | S1 | 2 | System | Offline reliability / bundled last-good fallback | Availability cannot depend on fetch | Catalog / malformed or interrupted fetch | selected |
| S2 | root | 1 | System | Applicable selection / scoped matches and local priority | Broad reports lack local proof; scope can discriminate | Catalog / contrary evidence | selected |
| S3 | root | 1 | System | Reproducibility / immutable attempt snapshots | Rate alone omits configuration | Parent / stale answers or restart | selected |
| E1 | root | 1 | Evidence | Attributable evidence / allowlist and idempotent receipt | Ambiguity defeats learning; app can tag attempts | Backend / first real reports | selected |
| E2 | root | 1 | Evidence | Reversible advice / scoped reviewed publications | Crowd volume is not qualification | Backend / conflict or withdrawal | selected |
| E2.1 | E2 | 2 | Evidence | Bounded claims / duplicate limits and honest counts | No stable identity means no unique-device proof | Backend / abuse | selected |
| E3 | root | 1 | Operations | Close loop / second-client catalog check | Storage alone insufficient | Parent / integration run | selected |

Sufficiency groups: ROOT all-required U1/U2/U3/U4/S1/S2/S3/E1/E2/E3 (distinct implementation constraints retained); UG2 all-required U2.1/U2.2; SG1 all-required S1.1/S1.2; EG2 E2.1 covers bounded initial promotion, with authenticated provenance deferred. Steps came from user worker U*, catalog worker S*, evidence worker E*; necessity/parallel assumptions inferred from source and contract, not hardware proof.

Overlap U3/E1 retained as interaction vs evidence integrity; U4/S1/E3 complement delivery vs benefit. Dependencies S3→E1→E2→S2→U4. Oppositions: maximum diagnostics/privacy (typed allowlist); automation/local truth (auto recommendation, explicit hearing); easy contribution/trust (no account, review before new scope); fresh knowledge/stability (refresh suggestions, pin accepted config). These are visible trade-offs, not merged goals. Invalidation: usable fields cannot distinguish failure cohorts; required audio mechanisms absent; collected observations do not improve next choice. Unsupported hardware and physical listening remain explicit gaps.

## Solution Selection

Select thin ingestion API + relational evidence store + reviewed immutable static catalog, with native setup/trials/queue in shared endpoint. Generic telemetry and live personalized recommendation services deferred; browser/manual report workflow rejected. HTTPS-host trust selected for v1; digests provide integrity, not independent publisher authentication. Full signing protocol deferred until operational key custody is established. All selected step parents and sibling groups above are included.

### Wiring Follow-up

Outcome/capability/signal/guardrail records remain local candidates mapped to ROOT, U2, U3, E2 and E3; external record/issue creation deferred because implementation is authorized directly and no canonical record parent is established. This does not block executing the selected tactics.

## Execute

Preflight: aim, constraints, ownership, scope, success signals and dissent loaded. Scope is the complete software loop and deployment-ready configuration, with physical audio claims explicitly requiring hardware. Dissent in recipe-feedback-solution-space.md carries into implementation. Workers own UI, catalog, backend; parent owns attempt persistence/report transport/hook integration, packager handoff, build setup and end-to-end verification.

Required adversarial checks: no browser/account reporting; forbidden payload fields rejected; retries after commit create one receipt; report IDs never count as independent devices; scoped failures cannot be overridden by broad success; downloads cannot mark local heard; different config attempts retain opposite outcomes; offline submit does not block playback; new catalog does not replace working settings; unknown schema/engine rejected; server logs omit body and persistent identity; forged reports cannot auto-qualify dangerous routes; stale notification cannot answer another attempt; interrupted attempt is not auto-replayed; settings restored completely. Each must fail the tempting shortcut described in the solution-space risk table. Physical sound/capture is accepted only as human verification, never simulated as proven.

Execution remains active until implementation and required checks are complete; update this section with evidence and remaining deployment/hardware boundary.

### Implementation and review evidence

The shared endpoint implements native output selection, scoped recipe choices,
immutable listening attempts, complete working-setting restoration, an optional
in-app report queue and startup catalog refresh. Roon-specific instructions live
in the bridge; installation and the open-app handoff live in the packager.
The Python reference accepts typed reports into SQLite. Production deployment is in progress for the
dedicated Cloudflare Worker and D1 database in [HiPhi Cloud PR #158](https://github.com/open-horizon-labs/hiphi-cloud/pull/158), preserving
the same reviewed immutable catalog contract. No browser or manual report transfer is part of the user flow.

| Risk | Check that rejects the tempting shortcut | Current evidence |
| --- | --- | --- |
| Audio open mistaken for heard success | Separate grant, fault and hearing values; catalog cannot write hearing | Journal/report tests; service grant-vs-hearing test |
| Retries inflate support counts | Retry same ID; repeat history under new report ID; retain changed snapshots | Service idempotency, observation deduplication and conflict tests |
| Broad or forged reports qualify unsafe DSD | Require explicit model/SDK/engine/route and heard DSD evidence; cap native opener | Catalog, service native qualification and EndpointDsdQualification tests |
| Chosen packing silently ignored | Explicit selected route takes precedence over discovered route; actual chosen route saved for restore/report | Sink-factory path inspection; report projection tests |
| Failure evidence disappears | Separate attempt identities and outcomes; later negative invalidates review | Journal and service conflict tests |
| Stale or incompatible update breaks setup | Hash/schema/features/sequence checks; bounded atomic cache; bundled fallback | Catalog store tests, including real service publication fixture |
| Feedback leaks unrelated data | Typed projection and backend rejection of extra fields/freeform values | Report and backend allowlist tests |
| Old answer marks a new stream | Per-stream token, atomic prompt snapshot and token-matched journal answer | Hearing-controller/UI lock and ordering review; immutable journal answer tests |
| Crash repeats a failed trial | Interrupted attempt recovered without automatic trial replay; restore saved full config | Journal recovery tests; service startup-path review |
| Incomplete shell yields a broken install | Require every declared native library before creating output | Synthetic packer regression and shell-audit tests |
| Fixture data reaches production | Separate temporary integration DB; production must remain empty | Reference integration tests; retired VM database and backup verified empty before removal |
| Storage/log growth exhausts service | Logical payload and receipt-count budgets, bounded requests, request logs disabled | Cloudflare budget tests and reference storage-full HTTP503 tests |

The Android screen review uses an API26 x86 emulator and a deliberately native-free
preview APK; it establishes layout and interaction evidence only. JVM tests and
source inspection do not establish audible playback, hardware format delivery or
bit-exactness. Those remain explicit physical-device listening/capture checks.

Production hosting is Cloudflare through the existing private HiPhi Cloud
repository and authenticated deployment workflow, using a separate Worker and D1
database. Administrative operations use Cloudflare's authenticated API rather
than a public admin route. Private review/publication uses Wrangler's official remote D1 binding and first
proves batch success and rollback on dedicated scratch rows. Live deployment at commit 44d2d7d passed in HiPhi Cloud run 40373339138,
including the genuine remote D1 commit/rollback probe. The origin is
https://hiphi-cloud-playback.open-horizons.workers.dev. Public health returned 200,
the unpublished catalog returned 404, and the absent admin route returned 404.
The existing application deployment was skipped; no synthetic reports were
inserted into production.

Verified local checks: 428 shared endpoint, 146 discovery, 43 bridge and 51 packager
JVM tests pass, with zero failures, errors or skips in their current XML results.
The packager debug and release builds pass, and both APKs pass the source-only
content audit. The shell-audit suite has 11 synthetic tests; the Python
reference has 40 tests. The Cloudflare Worker/D1 port has 53 passing workerd tests.
These are separate suites, not counts of physical listening trials.

The temporary VM207 installation was reversed on 2026-10-26. After stopping its
service and maintenance units, both the production database and its sole backup
passed integrity checks with zero reports, reviews and publications. The three
units, persistent timer stamp, code/state directories and dedicated account/group
were removed. Verification found all units absent, no port 8765 listener, and no
unrelated files owned by that account. No other homelab service, tunnel, DNS record
or firewall policy changed. Obsolete VM/container deployment files were removed.

The repositories remain private. A dedicated Cloudflare download Worker now
serves only reviewed immutable objects from the private `hiphi-roon-distribution`
R2 bucket. Source availability is checked before serving the shell. Anonymous
full-body downloads verified both final objects; the parent independently
redownloaded the shell and reran its 15-library/manifest audit before pinning it.

Final shell: bridge `6c94f74`, shared endpoint `3c9f092`, clean detached build,
version `0.2-poc (6c94f74)`, SHA-256
`57dffc0b0875eda23708596c263fd2562736f568707550c717f07af21b3f6420`.
Matching source archive SHA-256:
`6fb19d6def87910daea14f4536c7827cdf84c6c4c03e3d64b9a95c46ee66dd19`.
The APK embeds 25 license/notice assets. The native source bundle binds the exact
APK and all 15 native entries; it excludes the private Android endpoint module.
A local ARM64 QEMU probe verified native loading and 16,384 bytes through the
synthetic ALSA card. This does not establish Android or physical DAC playback.

The final recovery review also established that unreadable history suppresses
sticky/null-intent automatic restart while explicit Start remains available.
The complete 428-test shared suite passed again after that guard change.
The installer catalogue is now enabled with the independently verified public
shell URL/hash. Its own dependency licenses are embedded as text assets.
No Roon runtime was downloaded during builds or tests, and no packed Roon APK
was published. Physical-device listening and capture remain human verification.
