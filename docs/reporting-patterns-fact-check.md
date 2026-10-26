# In-app playback reporting: pattern accuracy report

Checked 2026-10-26 against primary documentation and source. Research for a proposed design, not a claim that this repository already implements these capabilities. Search-tool requests failed; the sources below were fetched and inspected directly over HTTPS.

## Claims checked

| Claim | Status | Evidence | Recommended interpretation |
|---|---|---|---|
| fwupd supports sending firmware outcome reports from its client, with configurable automatic reporting. | Verified | [Remote configuration](https://github.com/fwupd/fwupd/blob/f7a1b441a4b293e01102e60ec1af05c12f64c617/docs/fwupd-remotes.d.md), [client reporting](https://github.com/fwupd/fwupd/blob/f7a1b441a4b293e01102e60ec1af05c12f64c617/src/fu-cli.c) | Reuse the built-in outcome-submission pattern; its CLI is not our proposed user interface. |
| fwupd reports include update state and old/new versions, and may include a machine identifier. | Verified | [Report serialization](https://github.com/fwupd/fwupd/blob/f7a1b441a4b293e01102e60ec1af05c12f64c617/libfwupd/fwupd-client.c), `fwupd_client_build_report_history` and its device serializer | Reuse outcome/version association, not a claim of identity-free reporting. |
| Sentry’s Android feedback can originate inside the app without an error, using a custom interface, and can be associated with an event. | Verified | [Android user feedback](https://docs.sentry.io/platforms/android/user-feedback/) | The interaction is an established pattern. It does not establish that Sentry provides a hardware-recipe learning system. |
| Installing a feedback SDK guarantees that reports contain no personal data. | Incorrect | [Sentry Android data collected](https://docs.sentry.io/platforms/android/data-management/data-collected/) documents identifiers and potentially sensitive URL/path categories | Define our own allowlisted payload and inspect actual transport. SDK configuration and integrations matter; do not assume defaults satisfy the requirement. |
| Hardware Probe distinguishes detected hardware from hardware that works, and uploads hardware evidence to a shared database. | Verified | [Hardware Probe README](https://github.com/linuxhw/hw-probe/blob/master/README.md), device-status and usage sections | Keep “detected,” “opened,” and “heard working” separate in our model. Its command-line/manual review workflow is not our proposed UX. |
| Hardware Probe’s privacy measures make its reports certainly anonymous. | Incorrect | [Hardware Probe privacy section](https://github.com/linuxhw/hw-probe/blob/master/README.md#privacy) warns that scrubbing can fail and describes salted identifiers | Avoid copying raw-log scraping or persistent hardware hashes into our default submission. |
| Android audio workarounds can depend on more than manufacturer/model. | Verified | [Oboe QuirksManager](https://github.com/google/oboe/blob/main/src/common/QuirksManager.cpp) checks chip characteristics, build changelist, stream direction and input preset for particular workarounds | Include relevant hardware, firmware and stream context. These examples concern Oboe audio behavior, including recording, and do not validate a Roon DSD recipe. |
| These projects establish that crowd reports can safely select arbitrary DAC settings automatically. | Unsupported | None of the inspected sources establishes this | Treat automatic ranking, qualification, and promotion as our proposed policy, requiring adversarial and device tests. |
| A salted serial hash is non-identifying simply because the serial is not sent in clear text. | Misleading/context missing | Hardware Probe explicitly uses hashes to identify unique systems/drives | Omit stable hardware identifiers by default. Per-report IDs deduplicate retries but cannot establish unique independent devices. |

Source freshness: the fwupd references are pinned to the inspected commit. Sentry documentation and the Oboe/Hardware Probe branch references were inspected on the date above; recheck SDK behavior against the selected release before integration. No service pricing, hosting limits, availability guarantee, or Android crypto-provider compatibility is asserted.

## Design inference from the verified patterns

Combine built-in outcome submission, structured device compatibility evidence, and narrowly scoped audio quirks. A small receiving API and evidence store can serve this without requiring a telemetry SDK. Publish reviewed recommendations as versioned catalog data so playback and initial matching remain local and usable offline. This is a recommendation derived from the sources, not a capability they supply together.

The useful loop is: listener verdict → captured attempt context → in-app submission → validated cohort evidence → reviewed matching/ranking rules → startup catalog refresh → better first choice or next attempt.

## Proposed payload boundary

Maximize diagnostic usefulness within typed, non-content fields:

- Manufacturer, model, product/device family, chipset where available, Android SDK, and a normalized firmware/build identifier. Report missing facts as unknown. A rare combination can still distinguish a device; do not promise anonymity.
- Endpoint, resolver/library, Roon and catalog versions; recipe revision and effective audio settings; exact match rationale and candidate order.
- Internal/USB route; USB numeric vendor/product IDs and audio capability descriptors with serial/string identifiers removed; channel/rate/encoding, DSD transport/packing, buffer policy.
- Requested versus received stream format, open/grant/refusal outcome, readback when available, stream duration bucket, underrun/overrun counts, structured fault code, hearing verdict, interruption and restore outcome.
- Parsed audio-policy facts relevant to routing. No complete policy dump by default. Firmware strings and driver messages must pass normalization/length checks; diagnostic strings are not inherently non-personal.
- Random report ID and attempt IDs for idempotent retries and within-session relationships. No persistent installation ID by default. Store only coarse times/durations needed for analysis.

Exclude account/email/name, Android ID, serials and their hashes, MAC/IP addresses in the report, SSID, endpoint/zone names, song/artist/album/file/URL, arbitrary logs, screenshots, audio recordings, location, and free-text notes from the default report. Structured reason choices should cover the ordinary flow. Any later free-text or support attachment feature is a distinct, explicitly disclosed submission path.

The network edge necessarily receives connection metadata. Configure access logging, error tracing and retention so request IPs and bodies are not copied into the evidence database or long-lived analytics. This remains an operational verification item, not a promise that an HTTPS server never observes an address.

## High-risk unresolved claims

- That any specific route works, preserves the source rate, or is bit-exact on a given player: requires actual endpoint observations, human listening, and a capture for bit-exactness.
- That a vendor-wide rule is qualified by several same-model reports: unsupported without broader compatibility evidence.
- That reports represent independent devices: not established by report IDs; retries can be deduplicated but repeat submissions and forged outcomes remain possible.
- That a submitted package contains no identifying information: verify the serializer, networking layers, proxy logs, storage and error handling using adversarial fixtures and captured requests. Do not use an unconditional “anonymous” label.

## Claims requiring expert or human review

Audio route qualification and audible behavior require physical-device testing. Privacy and operational retention promises require review of the actual deployed service and SDK configuration. No legal-compliance conclusion is made here.

## Source gaps

The sources do not supply a validated confidence threshold for promoting DAC recipes, unique-device attestation without identifiers, or a ready-made engine for this Roon feedback loop. Public reports cannot prove their own authenticity. These are design constraints, not missing marketing claims to fill in.

## Corrections applied to the proposal

Remove browser instructions, manual issue filing, copy/paste, and file export from the normal reporting path. Make in-app submission and the receiving service part of the first usable version. Preserve success and negative evidence per exact attempt. Keep vendor matches as scoped suggestions until qualified. Distinguish reported outcomes from unique devices and human hearing from machine-open success. Refresh recommendations without replacing a locally working setup.
