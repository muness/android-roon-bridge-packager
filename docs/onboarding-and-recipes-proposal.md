# Get Roon Bridge working, then explore its limits

Design proposal, 2026-10-26. Planning only; the flows and storage described below are not implemented.

## Outcome and audience

The user’s direction is **music first, but it has to work**. First setup succeeds when music sent through Roon reaches the selected wired output and the person confirms that it sounds right. Installing an APK, finding a recipe, starting Bridge, or opening an Android audio track is progress, not completion.

Assume someone who knows Roon but does not know Android audio ports, vendor format numbers, or DSD byte layouts. Use Impeccable’s Operate approach: native controls, one next action, concise status, and troubleshooting beside the failure. This is an onboarding behavior proposal, not a replacement visual identity. No new PRODUCT.md or DESIGN.md is inferred from the code.

## What exists

- The packager installs a locally packed endpoint. Its main screen exposes implementation details, cache controls, and diagnostics before first value.
- Roon’s screen adds Start/Stop to the shared endpoint screen. The shared screen has status, first-hearing questions, crash recovery, settings, and logs, but no guided progression from failed playback to the next recipe.
- Device recipes currently come from compiled output-library profiles. Existing device documentation distinguishes Roon evidence from sibling NAA evidence.
- The shared module separately records Android grants, human hearing answers, faults, and history. Preserve these distinctions.
- PCM hearing buttons are Working / Stutters / Not working. DSD uses Working / Noise / Silence; a Noise answer currently changes byte layout, while silence leaves the route alone and tells the user to change HTTP settings.
- Some DSD opens have crashed the audio server. A bounded candidate list and explicit attempts are required; a blind product of every setting is not a meaningful “try everything” workflow.

Source locations: packager `MainActivity.java` and `docs/devices.md`; bridge `RoonMainActivity.java`; shared module `EndpointActivity.java`, `FirstHearing.java`, `DsdHearing.java`, `HearingController.java`, `VerifiedOutputs.java`, `DiagnosticsReport.java`, and discovery `ProbeGuard.java`. These are code/document inspections, not rendered UI or physical-device validation.

## First setup

| Stage | What the user sees | Primary action | Completion evidence |
|---|---|---|---|
| Install | “Install Roon Bridge on this player.” Brief requirements and required download acknowledgement; technical details expandable. | Download and install | Android reports the endpoint installed. |
| Handoff | “Roon Bridge is installed. Next, check your audio output.” | Open Roon Bridge | Endpoint opens; resume here after installation or interruption. |
| Choose output | “Where will you listen?” Internal headphone/line output or the identified USB DAC. Show what is connected and the detected player model. | Use this output | A specific output is selected; USB permission handled in context. |
| Find settings | “Settings found for your [model]” or “No tested settings for this model yet.” Explain the match and evidence in one line. | Try these settings | A compatible recipe is staged for this output; finding it is not labelled success. |
| Connect Roon | Show the endpoint name to enable in Roon, service status, and “Play a track in Roon.” | Start Bridge / I can’t find it | A Roon stream arrives. Missing stream follows connection troubleshooting, not DAC recipe cycling. |
| Listen | “Does the music sound right?” Show actual stream format and selected output. | Sounds right | The human confirms audible playback for this exact attempt. |
| Keep or recover | Success: “Music is working through [output].” Failure: “That setting didn’t work. Your result is saved.” | Keep these settings / Try next option | Working settings saved, or a new explicit attempt begins. |

Avoid a ceremonial welcome tour or a percentage that pretends the time to working audio is predictable. Use named stages and resumable state. Experienced users can leave the guide; the main screen keeps a “Finish audio setup” action until success, without reopening a modal every launch.

The endpoint owns output selection and tests because it can observe playback. The packager owns download acknowledgement, installation, and the handoff. It should not hold a second independent copy of setup state.

## Matching: known device, vendor suggestion, unknown device

Resolution order:

1. This installation’s saved, heard-working settings for the selected output and compatible firmware context.
2. An exact device recipe with applicable Android/firmware constraints and endpoint capabilities.
3. A curated vendor/family recipe whose applicability is explicitly declared. A shared manufacturer name alone does not justify applying another model’s recipe.
4. Device-discovered applicable routes and a conservative PCM starting configuration.
5. Additional reviewed experimental candidates, available under “More options,” each with why it may apply and what remains unknown.

Show distinct descriptions: “Working on this player,” “Reported working on this model,” “Reported through NAA; not yet tested through Roon,” and “Suggested for this device family.” Never turn a downloaded report into this device’s local hearing confirmation.

A firmware change preserves history but marks old results as needing confirmation. A new USB DAC starts its own output history. A missing or disconnected output pauses an attempt rather than recording a recipe failure.

Catalog status is secondary: “Using saved recipes · checked [time]” and a manual refresh action. Offline first setup uses the bundled recipes; returning users keep their working settings. A catalog arriving mid-test is queued for later so the option order and recipe meaning do not change under the listener.

## Trying the available options

“All options” means all applicable candidates the installed engine can express, with every candidate accounted for as untried, working, failed, skipped, incompatible, or blocked with a reason. It cannot promise to discover every undocumented vendor integer or make unsupported hardware work.

Show a compact progress line such as “Option 2 of 4 applicable settings.” Offer “See all options” with the reason each is included or excluded, its source, and any previous result. Expand technical settings on demand. Keep a known-working configuration available as “Restore working settings.”

Each candidate is a complete effective configuration, not an isolated preference layered accidentally over the previous attempt. Compute defaults and overrides once, stage the full snapshot, stop the affected playback, apply it, and restart only as needed. Do not change a live stream’s DSD layout as a side effect of recording an answer. Save the answer first, then offer “Try next layout.” This deliberately changes today’s immediate Noise behavior and needs a compatibility plan for HTTP clients.

The next action depends on the observation:

| Observation | Record | Next action |
|---|---|---|
| No Roon stream | Waiting / connection issue | Check Bridge state and Roon’s selected zone; do not spend a recipe candidate. |
| Android refuses to open | Open refused, with reason | Offer next applicable route/encoding. |
| Music sounds right | Heard working for exact route and format | Keep settings; first setup complete. |
| Silence | Heard silence | Check selected output, connection, and device volume/mute first; then offer another applicable route. Do not assert silence proves one particular cause. |
| Noise/distortion | Heard noise | Stop playback, then offer the next applicable layout/encoding. For DSD, explain lowering volume before the next explicit trial. |
| Stutters/dropouts | Heard stutters | Explore relevant buffering/rate candidates; do not equate stutters with a byte-layout failure. |
| Output disconnected | Interrupted | Reconnect or choose output; no inferred negative hearing result. |
| App interrupted or killed | Interrupted unless a crash is established | Resume safely; never silently repeat the unfinished attempt. |
| Known crash/hang | Technical fault, with attempt context | Restore last working setup and exclude automatic retry. Explain if a device reboot may be needed. |
| User skips / cannot judge | Skipped / inconclusive | Preserve as distinct from failure; move on explicitly. |

The primary answers are “Sounds right,” “No sound,” and “Something’s wrong.” The last opens “Noise/distortion,” “Stutters/dropouts,” and “Something else,” with a stop action immediately available. “Not sure” leaves the outcome inconclusive. Use the same semantics on screen and notifications; only ask for the current attempt and reject stale answers.

After first success, “Test more formats” opens PCM rates/depths, DoP, and native DSD supported by the candidate engine. It shows what to play from Roon and the actual received format; a requested DSD test that arrives as PCM is not a completed DSD test. Do not assume the endpoint can command Roon playback or that the user owns test tracks. Owned/generated test material can be a later addition; distinguish any local sink test from end-to-end Roon evidence.

When candidates are exhausted, say “None of the available settings worked for this output” and show the attempt summary. Offer in-app report submission, another connected output, and expert settings. If a system-mixer fallback is actually available, it can be an explicitly labelled listening option; its success must not establish direct-output or DSD support.

## Durable attempts and useful reporting

Use an immutable attempt record plus a derived current summary. Existing format rows remain useful compatibility views; they are not enough to distinguish two recipes that open the same format.

Each attempt records:

- Unique attempt/session IDs, times, and lifecycle state; write the pending attempt before touching audio hardware.
- Player manufacturer/model, Android and firmware identity, endpoint/library versions, and Roon version when known.
- Selected output identity and route; distinguish internal and USB and do not expose USB serials in shared reports.
- Catalog version, recipe ID/revision, match reason, and the complete effective configuration (including local overrides).
- Requested test format, actual received stream format, granted/open result, observed device format when available, and technical fault separately.
- Human verdict, when/how it was given, and optional note; no answer is not failure.
- What changed from the previous attempt, restore point, and next-candidate decision.

Pin both the working configuration and its evidence. Working PCM at one rate is not evidence that all rates, native DSD, or DoP work. A result through NAA is not relabelled as Roon evidence. Do not claim bit-exactness without a capture.

“Send playback results” belongs beside the setup outcome and in a Results screen. The app automatically assembles a typed report containing attempts, relevant status and versions, then submits it directly to our receiving service. The user supplies the listening verdict, not logs or device facts. No browser, HTTP instructions, GitHub account, copied text, or file attachment is part of this flow. Show a brief explanation of the collected categories, with optional inspection; one Send action queues the report. Show Queued offline / Sending / Received / Could not send, with automatic retry and cancellation of pending reports. Submission must never block music. Optional future automatic sharing requires a clear opt-in; recipe refresh itself does not imply upload consent.

The receiving service validates and deduplicates structured observations, groups them by output/device/firmware/engine/recipe/format, and makes conflicts and promising candidates visible to maintainers. Reviewed rules improve automatic matching and candidate ordering through the startup catalog. This is a required part of the first usable reporting version. Reports do not directly rewrite released recipes. See `recipe-feedback-solution-space.md` for the complete submission-to-selection loop and `reporting-patterns-fact-check.md` for verified precedents.

## Small-screen behavior

Keep the output name, current status, and next action visible without reading logs. Stack answer buttons vertically on small players; provide at least 48dp touch targets, text labels for every state, readable scaling, and TalkBack order matching the task. Preserve focus and edits when status refreshes. Show details on expansion rather than nesting independently scrolling logs inside the main flow.

Ask for USB, notifications, or background-running permissions when needed and explain the immediate benefit. Denying notifications leaves the in-app listening question usable. Rotation, leaving for Roon, app restart, and delayed notification answers must preserve or safely invalidate the current attempt.

## Implementation boundaries and sequence

1. In `android-dac-discovery`, define attempt identity, complete configuration snapshots, result states, and migration of old marks as legacy evidence with unknown recipe context. Add pure matching and candidate-selection logic with constrained fixtures.
2. Add the catalog reader/cache and bundled snapshot, then surface match reasons. The shared endpoint must read recipes independently of the packager, including already-installed packed endpoints after a compatible shell update.
3. Add resumable onboarding and Results UI in the shared module; keep Roon connection instructions and Roon stream evidence in the bridge integration. Adapt hearing actions so result recording and advancing to a candidate are separate operations.
4. Add packager installation handoff and in-app submission with durable queue, receiving API, evidence store, maintainer review, and catalog publication. Run the shared module tests first, then consumer tests and repository-required build checks when implementation begins.

Acceptance scenarios: exact match succeeds; family recipe fails then alternative succeeds; unknown player works through PCM; no stream does not fail the DAC; output unplugged during test; process dies mid-attempt; notification answers an older attempt; offline startup; catalog refresh mid-test; firmware changes; same format succeeds on one recipe and fails on another; known-crashing candidate remains blocked; user restores a working configuration; all candidates exhausted produces a useful report.

Human checks still required: a new user can reach audible Roon playback, understand the next step after silence, recover from a failed setting, and submit a useful report entirely inside the app on a small physical DAP. Audio quality, actual route behavior, and time-to-first-working-music require hardware/listening; unit tests cannot establish them.

## Dissent: proposed architecture and onboarding

**Decision under review:** a shared guided attempt engine, with recipes delivered as reviewed static snapshots. **Stakes:** changing audio routes can break previously working playback, while weak evidence can mislead later users. **Confidence before dissent:** medium.

**Steel-man:** recipe data changes more often than engine behavior; reviewed snapshots can update recommendations without repackaging. A guided sequence turns existing grants and hearing answers into a usable path to music.

**Contrary evidence:** the existing route resolver incorporates local discoveries; replacing it with a simple device-name lookup would lose useful behavior. Existing DSD hearing code changes layouts immediately and speaks too conclusively about silence. Existing format-level marks do not reliably identify the complete configuration under test. These are concrete reasons a downloadable table plus a wizard is insufficient.

**Pre-mortem:**

1. Functional failure: a new catalog changes defaults underneath a saved recipe; the user’s formerly working setup fails. Preserve a complete effective configuration and engine compatibility, not only a recipe ID.
2. Adoption failure: users receive a recipe but cannot find the Roon zone, or see silence with no actionable recovery. Separate connection troubleshooting from sink trials and make the next action explicit.
3. Opportunity cost: a sprawling backend absorbs the work while the existing UI still requires HTTP editing. Build the smallest complete receiving service, structured evidence store, review workflow, and static catalog publication alongside resumable in-app attempts. Ingestion is required; a live personalized recommendation service is optional.

| Assumption | Evidence | Risk if wrong | Check before commitment |
|---|---|---|---|
| A recipe is declarative settings the engine already understands | Current built-in profiles and RemoteSettings exist | New device support actually requires native code | Try expressing each known profile and its exclusions without an executable extension; unsupported behavior requires an engine release. |
| One successful configuration can be restored | Existing settings and local marks persist | Hidden defaults or library changes alter playback | Restore after another candidate and catalog revision; compare complete resolved configuration and re-confirm on device. |
| A vendor recipe narrows a useful search | Known profiles currently match specific models | Vendor-wide matching sends an unsuitable route to a different HAL | Same-vendor/different-model fixture must not become an exact match; require declared family applicability. |
| Stored results can support better recommendations | Granted/heard/fault separation already exists | Two recipes’ outcomes are merged | Same output and format with different recipe snapshots must retain opposite outcomes independently. |

**Reconstructed story:** static distribution remains plausible, but its value depends on accurate attempt context and clear application rules. The weakest assumption is that the installed engine can express the needed recipes. Confidence in “just download a device table” decreased; confidence in preserving the existing resolver and adding a reviewed candidate source increased.

**Recommendation: ADJUST.** First prove attempt identity, configuration restoration, and candidate compatibility. Fetch catalog updates automatically; adopt changed settings only at a deliberate boundary, and never silently replace a heard-working configuration. Keep human listening confirmation central. **Confidence after dissent:** medium, pending fixture and physical-device checks. This document records a proposal; an accepted ADR should follow when the implementation contract is selected.
