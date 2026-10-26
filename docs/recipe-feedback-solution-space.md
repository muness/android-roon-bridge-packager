# In-app playback evidence and recipe distribution — solution space

Revised proposal for discussion, 2026-10-26. This replaces the earlier download-only proposal. The complete product loop is: try music → record what happened → submit useful nonpersonal evidence inside the app → improve device/model/vendor recommendations → deliver those improvements to installed endpoints.

No application code, database or publishing configuration has been changed.

## Solution Space Analysis

**Problem:** Let listeners submit playback evidence without leaving the app, use that evidence to improve recipe selection for relevant device cohorts, and deliver improvements to already packaged endpoints on startup.

**Key constraint:** The evidence must retain enough technical context to explain success or failure without collecting personal content; anonymous reports must not silently turn uncertain or dangerous settings into authoritative recommendations.

**Working story:** A small ingestion service stores structured observations. A reviewed aggregation/publishing step produces a versioned recipe catalog. Endpoints match locally, preserve their working configuration, and submit attempts through an in-app queue. The catalog and report schema share stable recipe/configuration identifiers, but a local listening answer, a crowd report and a maintainer recommendation remain different facts.

**Success signal:** A person who gets music working can submit the successful attempt and relevant prior failures with one in-app action. A later endpoint on the same supported cohort receives a better initial candidate; a vendor-related but untested model sees a properly labelled suggestion. Upload or download outages do not block playback. Failed or conflicting recommendations can be withdrawn without losing a user's working setup.

**Decision criteria:** In-app completion, evidence quality and privacy, operational burden, resistance to bad/duplicate reports, explainable cohort selection, old-client compatibility, offline playback, and reversible publication.

**Critical assumptions:** Initial volume fits one small managed datastore/service; reports can be expressed as bounded typed records; new risky routes need deliberate qualification; automatic selection should improve fresh/unconfigured setups while preserving existing heard-working configurations.

## Code evidence and ownership

- `android-direct-dac` owns `DsdDeviceProfile`, `DsdManufacturerRecipe`, `ProfiledDsdRoutes` and `InternalDsdPlan`. Manufacturer recipes explicitly say that a vendor match is not playback qualification. Profiles include qualified SDK and maximum DSD multiple.
- `android-dac-discovery/discovery` owns observed capability discovery. `DiscoveredDsdRoutes` already uses locally granted routes and heard packing before compiled fallback. Policy declarations are not sufficient evidence of playback.
- `android-dac-discovery/endpoint-app` owns startup, settings, UI, diagnostics, verification, hearing prompts and crash recovery. `VerifiedOutputs` distinguishes granted, heard, fault and history, but some keys omit full recipe/route context. Add durable attempts rather than treating a rate/format key as a complete experiment identity.
- `DsdRouteHolder` currently fingerprints three settings. New resolved recipe/engine state needs an explicit identity without allowing refresh to reset a trial or alter active playback.
- `android-roon-bridge` owns Roon-specific launch/client integration. Its contributor guidance requires shared endpoint changes in the discovery repository first. The packager installs shells and should not own endpoint reporting or runtime matching.
- Direct-DAC guidance records actual audio-server crashes from blind sweeps. More reports must not be used as a reason to run unrestricted combinations automatically.

## Candidates Considered

| Option | Level | Approach | Main trade-off |
|---|---|---|---|
| A | Local optimum | Small HTTPS ingestion service, structured relational datastore, aggregation/review tool, static versioned catalog publication. | One service to operate; direct control of privacy/schema and decoupled, offline-capable playback. |
| B | Integration | Managed telemetry platform receives structured in-app events; scheduled export generates catalog changes. | Fast ingestion/queue tooling, but platform event/session conventions and default enrichment need careful suppression; playback evidence still needs a domain-specific aggregation and publication layer. |
| C | Redesign | Online recommendation service receives device context and returns ranked recipes from live evidence on each startup. | Fast contextual updates, but matching availability and reproducibility depend more heavily on service behavior; still needs local fallback, recipe history, review controls and privacy discipline. |
| D | Reframe | Evidence registry and deterministic compiler are the core product: append-only observations, qualification rules and conflicts generate catalog releases. A minimal inbox accepts in-app reports. | Strong provenance/reproducibility and explicit conflicts; more modeling and rule work before first useful submission. |

A treats reporting and distribution as a modest application backend. B tests whether generic event infrastructure eliminates enough work to justify its collection conventions and domain adapters. C assumes live server-side personalization materially improves outcomes. D changes the core unit from a manually maintained recipe to evidence and qualification rules.

## Recommendation

**Selected:** A — a small evidence ingestion backend plus a statically published recipe catalog, with explicit evidence-to-recommendation rules from the beginning.

**Level:** Local optimum that can evolve into D as evidence volume and conflicting firmware reports grow.

Store submitted evidence in a relational datastore so attempts, recipes, exact configurations, cohort predicates, review decisions and catalog versions can be queried together. Use schema migrations and a small server API; do not make endpoints write directly to database tables. Choose the particular managed database/runtime after comparing the user's existing infrastructure and deployment preferences. Those choices do not need to change the client protocol.

Keep seed recipes, schema definitions, resolver fixtures and release/promotion rules in Git. Store report records and their review state in the backend. Publish a complete, reproducible catalog as immutable snapshot bytes with a small latest manifest. A snapshot records the publication decision and evidence cohort that led to each recommendation. Public catalog content is a minimized aggregate, never a dump of submitted diagnostic payloads.

A maintainer can inspect conflicts and approve a new recipe/cohort through an internal tool. **That is a maintainer workflow only. The listener submits entirely inside the endpoint app; no browser, GitHub account, copied diagnostics or HTTP commands appear in onboarding.**

**Accepted trade-offs:** There is a backend to operate and a deletion/retention policy to implement. Anonymous reports offer limited independence guarantees. Data-only improvements cannot add output mechanisms absent from an old shell. Newly discovered hazardous configurations remain review-gated. A catalog-capable/report-capable shell update is required once for existing installations.

## In-app submission and durable delivery

After a hearing answer, keep the outcome immediately on the device. Offer **“Share these results to improve setup for similar players”** with a concise explanation of the technical data included. A visible in-app submission state distinguishes saved locally, queued, sent and needs attention. Sharing must not be necessary to keep the working setup or finish onboarding.

For the initial implementation, an explicit in-app submit action authorizes the selected session's structured attempt history and capability observations. A separate optional setting could authorize future automatic result sharing, but it must not be silently inferred from the first submission. Consent, payload scope and whether future attempts are included are recorded locally.

- Serialize an allowlisted report on-device. Show an optional in-app detail preview, with no requirement to copy or inspect it.
- Queue an immutable payload under a random **report/submission ID**, not a stable installation ID. Persist its content digest and schema version before sending.
- Retry transient transport errors with bounded exponential backoff and jitter. Retain the queue through process death and connectivity changes. Never tie retry success to playback/service start.
- The server enforces uniqueness on the submission ID and checks its content digest. Repeating identical bytes returns the original acknowledgment. Reusing an ID for changed bytes is a conflict; it cannot silently replace evidence.
- A lost response after server commit is therefore safe to retry. Nonretryable schema/size errors become a readable in-app status and retain local results; an app update or corrected serializer can create a new submission with explicit supersession.
- A report can contain all relevant attempts from that session, including failures before the eventual success. “Working” must not discard the evidence explaining why other options failed.
- Bound payload size, retry lifetime, queue size and server rate. Do not silently drop reports that the UI claims were sent. Locally show expiry/failure, and allow retry or removal.

**No account and no embedded shared secret:** Submission is a public, narrowly scoped write endpoint. A secret distributed in every APK would not establish trust in the sender. Apply schema/size limits, duplicate detection, abuse controls and quarantine before recommendation promotion. Rate limits can use short-lived network information at the edge, but that does not make reports authenticated or independent. If actual abuse exceeds these controls, reassess an anonymous challenge rather than adding account creation to first setup by default.

Network requests necessarily expose a source address to the server/network provider. The product promise should be “no personal content or persistent user/device identifiers in the report,” not “the service can never see an IP address.” Configure infrastructure logging/retention accordingly; payload privacy alone is insufficient.

## Maximum useful nonpersonal evidence

Collect technical context by an explicit typed allowlist. Every allowed field needs a playback-debugging or matching purpose. Avoid “attach all logs” as the way to achieve breadth.

| Include | Why it changes decisions |
|---|---|
| Manufacturer, model and hardware/product family identifiers after checking they are model-level fields | Device/model/vendor cohort matching |
| Android SDK, Android version and normalized firmware/build release identifiers; relevant ABI and audio-policy feature summary | Firmware and engine compatibility, without conflating different operating environments |
| Endpoint type (Roon/NAA/lab), endpoint version, audio engine/library versions and supported recipe features | Evidence transfers only when its mechanism is actually shared |
| Internal output route category or USB vendor/product IDs and relevant descriptor capabilities, with serial strings omitted | Separates output mechanisms and DAC models |
| Recipe ID/revision, catalog sequence, complete normalized resolved audio settings including defaults, configuration digest and resolver version | Reproduces what was actually tried, even after defaults change |
| Requested format/rate/channels/DSD multiple/packing and returned grant/readback/refusal code | Distinguishes an attempted open from the path actually granted |
| Listener outcome (working/stutters/noise/silence/not sure), elapsed playback time and answer chronology | Human evidence remains distinct from machine observations |
| Bounded counters such as underruns/restarts, state transitions and structured diagnostic error codes | Helps explain failure without collecting music or unrelated activity |
| Trial interruption/crash suspicion, audio-server-loss indication, breadcrumb context and recovery action | Prevents repeated bad candidates while retaining uncertainty about cause |
| Session-relative attempt order, elapsed timings and report schema version | Reconstructs the path to success without precise personal activity timestamps |

Exclude track names, artists, album metadata, file paths/URIs, audio recordings, user-chosen endpoint names, account identifiers, IP/MAC addresses, SSIDs, USB serials, Android advertising/device IDs, precise location, arbitrary free-text logs and unfiltered system properties. Do not assume a platform field is nonpersonal because it is named “build” or “device”: check values and permit only defined model/build fields. Error messages should be converted to structured categories or scrubbed through a tested allowlist before inclusion.

If a full firmware string is essential for a known compatibility split, define and validate that field explicitly. If it cannot be made acceptably nonpersonal, omit it and retain a lower-confidence cohort rather than quietly adding raw diagnostics. Device-specific unknowns may limit diagnosis; expose that limitation in maintainer tools.

No stable installation identity is proposed. Consequently, “20 submissions” is not “20 independent devices.” Idempotency prevents retry duplicates, not repeated or fabricated submissions from one person. Report counts must be labelled as report counts, and promotion must not rely on a fake unique-device denominator.

## Evidence model and recommendation lifecycle

Minimum entities:

1. **Report/submission:** random ID, content digest, schema, receive time, consent scope, validation/abuse/review state and retention deadline.
2. **Attempt observation:** report-local attempt ID, cohort dimensions, full effective configuration, engine context, requested and granted format, listening answer, fault/interruption context and preceding attempt link. Machine and human fields stay separate.
3. **Recipe revision:** immutable settings, supported output family/formats, required resolver features, constraints and provenance. Only typed audio settings are allowed; remote permissions, executable code and arbitrary URLs are excluded.
4. **Cohort evidence summary:** exact predicate, relevant successes/failures/unknowns, client/firmware scope, evidence IDs, observed conflicts and review confidence. Negative evidence is first-class.
5. **Publication decision:** approved recipe/cohort association, qualification limits, rule/reviewer, reasons, previous decision and catalog sequence.
6. **Local accepted setup:** complete resolved settings, defaults, engine/dependency semantics, output/OS context, recipe revision and locally heard formats. Catalog updates cannot replace this silently.

Cohort specificity should narrow claims: exact device + compatible firmware/SDK + output + engine mechanism first; model-level then manufacturer-level suggestions afterward. A new OS build can use earlier evidence as a candidate, not inherit a local “heard working” mark. Roon and NAA evidence remain labelled; shared sink evidence can rank a candidate but cannot claim Roon playback has been heard.

On receiving reports:

1. Validate and normalize; reject malformed fields and quarantine suspicious payloads.
2. Link exact attempt configuration to its recipe or an explicitly new candidate configuration. Preserve conflicts and corrections instead of last-write-wins.
3. Aggregate by the narrowest defensible cohort. A broad vendor aggregate must not drown out a known failure on one model/firmware.
4. Produce a proposed ranking/qualification change with evidence and its counterexamples. Review new routes, new cohort expansion and dangerous parameter changes before publication.
5. Publish a new catalog sequence with the approved decision. Initial automatic ranking improvements can operate within already qualified candidates and bounds; anonymous report volume alone cannot qualify a previously unsafe route.
6. Monitor subsequent contrary reports. Split a cohort, reduce ranking or withdraw a recommendation with a new higher-sequence publication. Restore earlier recipe content through a new publication, preserving history.

For a fresh setup, the endpoint may automatically **select the best applicable qualified candidate** as its starting configuration and guide the user to play music. It still needs a hearing answer to establish local success. For an already working setup, a better catalog ranking is an available alternative, not permission to change playback. Manufacturer candidates are available for deliberate trials when an exact recipe fails or is absent; their uncertainty stays visible.

Silence is not diagnostic proof of a wrong port or format. Output selection, volume/mute and source playback can explain it. The onboarding recovery path should check those before cycling routes. Likewise, an interrupted process is not automatically a confirmed HAL crash.

## Startup download, compatibility and rollback

- Per logical endpoint startup, begin with the accepted local setup and a last-good compatible catalog, or bundled snapshot. Check the latest manifest asynchronously; activity recreation must not trigger repeated checks.
- Fetch and validate new snapshot bytes separately, then atomically commit them. Retain last-good bytes through timeouts, invalid payloads and process death.
- Schema, required engine/resolver capabilities and recipe setting ranges are validated before exposure. Older shells retain compatible cached/bundled behavior; they do not partially apply unknown required settings.
- Freeze the recipe/catalog/configuration used by an active trial. Downloads change suggestions only, not active settings, local verification or advertised formats.
- Proposed publication authentication is a signed manifest/snapshot identity with verification material in the shell. A digest alone does not authenticate a publisher. Exact API-25-compatible crypto and key rotation need verification before protocol freeze. HTTPS-only is a simpler alternative with an explicit trust-in-host/account trade-off, not equivalent authentication.
- A withdrawal is a visible recommendation change and reason. Preserve the user's full accepted configuration and offer an applicable recovery path; do not hot-swap audio. Existing client hard restrictions and local exclusions continue to apply.
- Submission acknowledgment means “received,” not “used to recommend this recipe.” A lightweight optional acknowledgment status can convey accepted/rejected validation; it need not expose an account or live moderation workflow to the listener.

## Interpretive Variety Check

A and B differ in who supplies ingestion infrastructure. C tests whether online personalized matching is valuable enough to justify coupling recommendation availability to a service. D assumes inconsistent evidence, rather than delivery, is the binding problem. All four now include in-app ingestion; manually filed reports do not solve this user request.

If A collects useful reports but matching does not improve, inspect cohort definitions, missing technical context and promotion rules before changing storage technology. If maintainers cannot reconcile conflicts at expected volume, move toward D's stronger compiler/registry approach. If only a small slice of the catalog is relevant but catalogs become too large, C can be reconsidered while retaining local cached decisions.

## Risk Retirement Plan

“Retired by evidence” below is a planned disposition; the checks have not been run.

| Risk / assumption | Planned disposition | Tempting patch this must fail | Required evidence or rationale | Stop/pivot if |
|---|---|---|---|---|
| Reporting still requires leaving the app | Retired by evidence | Open GitHub/browser or copy an HTTP report | First-run test completes listening answer, submission, offline queue and sent state entirely in-app | Any reporting step requires external account/browser/tool |
| Maximum diagnostics leak personal content | Retired by evidence | Upload `/report`, logcat or arbitrary strings wholesale | Fixtures inject song names, serials, URLs, SSIDs and endpoint names into every diagnostic input; serialized payload contains only allowed technical fields | Raw/unclassified data can enter report payload |
| Retry duplicates inflate evidence | Retired by evidence | Append each HTTP POST as another observation | Server commits then drops response; repeated retries yield one report and one set of attempts; ID reused with new digest rejected | Retry changes aggregate counts |
| Anonymous submissions appear independently corroborated | Accepted with rationale plus evidence check | Count report IDs as devices; embed shared APK secret | Explicit no-independence claim; forged/repeated reports cannot alone promote a new hazardous route; exact known cohort failures survive report floods | Promotion relies on unverified unique-device counts |
| Broad vendor success masks model/firmware failure | Retired by evidence | Rank solely by global/vendor success count | Many vendor successes plus one exact-cohort hazardous failure: exact restriction wins and broader hypothesis remains labelled | Broad aggregate overrides scoped negative evidence |
| Catalog successes become local verification | Retired by evidence | Import downloaded results into local verification table | Fresh device with reported-clean recipe remains locally untried until a real open and human answer | Download marks local success or enables verified-only formats |
| Trial failures are overwritten | Retired by evidence | Store only last answer per rate/encoding | Same format with two route/packing configurations retains both outcomes and the path to success | Correction destroys previous attempt/configuration context |
| Upload failure blocks setup | Retired by evidence | Await report POST before completing onboarding | Offline and server-error tests preserve working playback, local answers and queue state | Music or setup completion waits on service |
| Catalog refresh changes working audio | Retired by evidence | Apply latest recipe to preferences on startup | Accepted A, newly ranked B, active playback and restart all retain A until deliberate selection | Fetch mutates active/accepted settings |
| New schema breaks old packaged endpoints | Retired by evidence | Ignore unknown required fields and apply the rest | Old-engine fixtures retain working cache and reject incompatible recipes/reports clearly | Unsupported partial configuration is applied |
| Infrastructure logs undo payload privacy | Retired by evidence | Validate JSON but retain IP/body in default access logs | Deployment configuration and synthetic requests show bounded logs without report body/persistent identifiers; retention reviewed | Host/provider defaults cannot meet stated collection promise |
| Receiving evidence automatically qualifies dangerous routes | Retired by evidence | Auto-publish a new route after N successes | Anonymous fabricated successes cannot expand ABI/DSD constraints or bypass review/local exclusions | Report volume enables unsupported probing |
| Hearing and fault cause cannot be inferred | Accepted with rationale | N/A — domain/human limitation | Hearing requires listener; interrupted/suspected fault remains separate from confirmed cause; hardware validation before qualification | UI labels grant or silence as definitive success/cause |
| Service or signing operation exceeds current resources | Triggered | N/A — operational trigger | Choose provider against existing infrastructure; verify API floor, key custody, backup/restore and retention before release | No sustainable owner/process; simplify deployment without returning to manual listener reporting |
| Evidence conflicts overwhelm manual review | Triggered | N/A — scale trigger | Track unresolved cohort conflicts and review latency | Files/manual queries stop supporting defensible decisions; evolve toward D |

## Execution Handoff

No Problem Weave/S&T IDs were supplied. The dissent below adjusts the recommendation; schema and protocol remain proposals pending implementation checks.

- Preserve: in-app submission; useful negative and positive observations; no personal content/persistent identifiers in payload; local hearing truth; exact cohort limits; local accepted setup; explicit risky trials and source-only packaging.
- Verify: the adversarial checks above, plus physical known/unknown-player runs reaching audible music and submitting the preceding failures. Unit tests cannot establish actual audio or hearing.
- Ownership: recipe/resolver seam in `android-direct-dac`; observations in `android-dac-discovery/discovery`; local attempts, serializer, queue, submission UI, cache and setup flow in `android-dac-discovery/endpoint-app`; backend ingestion/schema/aggregate/publish in a small dedicated service/data repository; Roon integration in `android-roon-bridge`; packaging and installation help in `android-roon-packager`.
- First vertical slice: one hearing session → immutable allowlisted report → durable in-app queue → idempotent server receipt → internal evidence view → reviewed cohort recommendation → published catalog → another endpoint ranks the candidate. Demonstrate the complete loop before expanding report fields or adding automated promotion.
- Invalidated if: allowed data cannot distinguish important firmware/configuration differences; service abuse undermines recommendation quality; or better recipes cannot produce music because the needed engine mechanism is absent.
- Human verification: audible output, recovery after real device failures, defensible qualification of new routes/cohorts and acceptable nonpersonal field scope.

## Dissent on the complete feedback loop

**Steel-man:** a small receiving service plus static publication closes the feedback loop while leaving playback independent of server availability. **Confidence before review:** medium.

**Contrary evidence:** the inspected reporting tools can collect identifiers and broad diagnostics; a generic telemetry SDK does not automatically meet the requested data boundary. The current endpoint stores some results by format rather than full configuration; submitting those rows alone can create misleading evidence. Public unauthenticated reports cannot establish independent-device counts.

**Pre-mortem:** functional failure is a popular vendor recipe masking an exact-model failure; adoption failure is any browser/account/log attachment required after hearing music; opportunity cost is building live personalization while the simple submit-to-catalog loop remains incomplete.

**Hidden assumptions and tests:** useful technical fields can distinguish cohorts (test known firmware-dependent outcomes using only allowlisted fields); reports can improve selection without trusting vote counts (flood fixtures must not promote unsafe routes); static publication is sufficiently timely (measure review-to-publication delay and conflicting reports before adding live decisions). Hardware listening remains required.

**Reconstructed story:** in-app ingestion is mandatory, while live recommendation serving is optional. The weakest assumption is evidence quality without stable identities. Keep submission counts honest, retain conflicting observations, and require reviewed scope expansion. The first deliverable must show one submitted session improving the candidate choice on a second simulated endpoint, followed by physical-device validation.

**Decision: ADJUST.** Proceed with the small service proposal, explicit data allowlist, scoped evidence and adversarial promotion checks. Do not freeze a confidence formula or public schema yet. **Confidence after review:** medium. This proposal is the decision note; no deployment or API commitment has been made.

## Source references

Repository findings were read locally. No hardware trials were performed. External service comparisons are architectural candidates; provider-specific guarantees/pricing are not asserted here.

See [verified comparable patterns and claim corrections](reporting-patterns-fact-check.md) and [onboarding and recovery proposal](onboarding-and-recipes-proposal.md).

- [Bridge contributor ownership](/workspaces/muness-src/muness/android-roon-bridge/AGENTS.md)
- [Existing device evidence and settings](/workspaces/muness-src/muness/android-roon-packager/docs/devices.md)
- [Device profiles](/workspaces/muness-src/muness/android-roon-bridge/third_party/android-direct-dac/direct-dac/src/main/java/io/github/muness/androidnaa/DsdDeviceProfile.java)
- [Manufacturer suggestions](/workspaces/muness-src/muness/android-roon-bridge/third_party/android-direct-dac/direct-dac/src/main/java/io/github/muness/androidnaa/DsdManufacturerRecipe.java)
- [Probe boundaries](/workspaces/muness-src/muness/android-roon-bridge/third_party/android-direct-dac/AGENTS.md)
- [Observed-route resolution](/workspaces/muness-src/muness/android-roon-bridge/third_party/android-dac-discovery/discovery/src/main/java/io/github/muness/androidnaa/DiscoveredDsdRoutes.java)
- [Verification and hearing data](/workspaces/muness-src/muness/android-roon-bridge/third_party/android-dac-discovery/endpoint-app/src/main/java/io/github/muness/androidnaa/VerifiedOutputs.java)
- [Current route cache key](/workspaces/muness-src/muness/android-roon-bridge/third_party/android-dac-discovery/endpoint-app/src/main/java/io/github/muness/androidnaa/DsdRouteHolder.java)
- [Crash breadcrumb semantics](/workspaces/muness-src/muness/android-roon-bridge/third_party/android-dac-discovery/endpoint-app/src/main/java/io/github/muness/androidnaa/StreamBreadcrumb.java)
