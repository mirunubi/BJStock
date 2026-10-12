# 162 — Intraday Isolated Probe Implementation (Phase 12-B2-A)

- Status: CURRENT — Phase 12 validation / evidence record.
- Baseline: edcc29c (docs/161 approved). Probe code committed in `c3da161`; WS-CONNECT-02 telemetry committed in `c9a5634` (§11). §7 keeps the original build-provenance rule.
- Gate: 12-B2
- Scope: debug-only isolated probe. VIRTUAL / KIS quotation / H0STCNT0 / venue J (KRX) / 005930 + 000660 / candidate FGS `specialUse`.
- This document records implementation facts. Gate results come from Human gates. §0 – §10 were written before the probe commit and the B2 physical run; their "uncommitted", "NOT PASS" and "evidence pending" wording is historical. Current status:
  - **B2 Human Gate: CLOSED — PASS — UNATTENDED WITH LIMITATIONS.**
  - Approved scope: a TOP-started `specialUse` FGS with already-established socket continuity, under the recorded test conditions.
  - Still NOT proven / NOT approved:
    - deep Doze;
    - unplugged, battery-only operation;
    - starting the FGS from the background;
    - opening or reconnecting a socket with the screen off;
    - recovering from a silent upstream outage. Reconnecting after an established socket is lost is proven only for an Android-visible Wi-Fi loss with the screen ON (Stage B-2, §13);
    - the other limitations recorded by the B2 gate. The cause of the initial WebSocket connect failures is UNKNOWN (§11).
  - **Stage B-1 / BG-RECONNECT: PASS_UNATTENDED_WITH_LIMITATIONS** (2026-10-12, Human accepted; §12). Proven: a new WebSocket connection opened after Home while BJStock was NOT TOP, with a TOP-started FGS alive and the screen ON. Screen-off reconnect, deep Doze reconnect and background FGS start remain UNPROVEN.
  - **Stage B-2 / BG-RECONNECT: PASS_UNATTENDED_RECONNECT_WITH_LIMITATION** (2026-10-12, Human accepted; §13). Proven: an established WebSocket lost to an Android-visible Wi-Fi interruption was detected as failed, retried, reconnected after Wi-Fi restoration, re-subscribed and resumed records while BJStock stayed NOT TOP, before the foreground return. Silent upstream outage recovery, screen-off reconnect and background FGS start remain UNPROVEN; deep Doze continuity and reconnect are DEFERRED.
  - Option A: **POSSIBLY FEASIBLE — NOT EVALUATED** by B2 (no orders; strict-after rule not derived).
  - Android-local: **POSSIBLY FEASIBLE** — not proven beyond the B2 approved scope.

## 0. Review and correction status

| Step | Result |
|---|---|
| Independent implementation review (Claude Code) | NO SAFETY BLOCKER; delta correction required before any live device/provider run |
| Delta correction F-1 … F-9 | Implemented (uncommitted at review time; committed in `c3da161`) |
| F-10 (46 vs 47 fields) | Measurement rule only, see §5; parser unchanged |
| Independent delta re-review (Claude Code) | PASS WITH FINDINGS — BLOCKER 0, HIGH 0, MEDIUM 0, LOW 2, INFO 1 |
| B2A-D-F01 (start-failure message preserved) | **RESOLVED** (confirmed by the independent final LOW delta verification), see §4a |
| B2A-D-F02 (terminal policy denial quiesces all probe network work) | **RESOLVED** (confirmed by the independent final LOW delta verification), see §4a |
| B2A-D-F03 (rapid restart during service teardown) | **INFO — accepted/deferred**, see §4a |
| Final LOW delta verification (independent) | **PASS** — completed. BLOCKER 0, HIGH 0, MEDIUM 0, LOW 0; remaining findings INFO only |
| Phase 12-B2-A implementation review | **COMPLETE**. At review time 12-B2 was **NOT PASS** (physical / provider evidence pending). Superseded by the B2 Human Gate: **CLOSED — PASS — UNATTENDED WITH LIMITATIONS** (see header) |

## 1. Isolation

| Item | Location |
|---|---|
| Probe code | `app/src/debug/java/com/mirunubi/bjstock/probe/intraday/` |
| Debug manifest | `app/src/debug/AndroidManifest.xml` |
| Debug network security config | `app/src/debug/res/xml/probe_network_security_config.xml` |
| Probe unit tests | `app/src/testDebug/java/com/mirunubi/bjstock/probe/intraday/` |

- No change to `app/src/main`, production navigation, Room schema, migrations, scheduler, Gradle, or any paper-trading / strategy / factor / analytics code.
- The only app classes the probe imports: `KisCredentials`, `KisCredentialStore`, `KisEnvironment`, `KisTokenStore` (enforced by `ProbeIsolationStaticTest`).
- Access: no launcher entry. The screen is reached by explicit component start, e.g. `adb shell am start -n com.mirunubi.bjstock/.probe.intraday.IntradayProbeActivity`, during the later live slice only.

## 2. Safety gates

- **VIRTUAL gate:** accepts only `KisEnvironment.VIRTUAL` with the exact official VIRTUAL endpoints. Missing VIRTUAL credentials stop the session with "VIRTUAL quotation credentials are not configured." The probe never reads the selected environment and has no fallback.
- **REST positive allowlist:** `https://openapivts.koreainvestment.com:29443` only.
  - `POST /oauth2/tokenP` and `POST /oauth2/Approval`: no query, no tr_id.
  - `GET /uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice`, tr_id `FHKST03010200`, exactly the five official query parameters, venue `J`, symbol 005930 or 000660.
  - Everything else is denied, including any path containing `/trading/`. Redirects are disabled.
- **WebSocket positive allowlist:**
  - Endpoint `ws://ops.koreainvestment.com:31000/tryitout` only, with the WebSocket upgrade header required.
  - Subscriptions: `H0STCNT0` with tr_type `1`, symbols 005930 and 000660 only.
  - Denied: production port 21000, NXT and integrated TRs, other hosts, paths and queries.
- **Secrets:**
  - `ProbeSecret.toString()` is redacted; forbidden JSON keys are redacted recursively.
  - A per-session `SecretScrubber` filters every evidence line.
  - There is no logging interceptor and no Logcat output.
  - The subscription message (which contains the approval key) is sent on the socket only.
  - Errors record class names and probe-defined codes only; exception messages are never displayed. Exception: since WS-CONNECT-02 (§11), a WebSocket connection-loss event stores scrubbed, length-bounded messages of the failure, its cause chain and its suppressed exceptions.
- **Token custody:** a REST token is needed only when the optional minute-bar observation is enabled. A stored VIRTUAL token is reused when it is valid for more than 5 minutes. Otherwise a new token is held in memory only and never persisted.

## 3. Foreground service

- `IntradayProbeService`:
  - `foregroundServiceType="specialUse"`, `exported="false"`;
  - subtype property "BJStock isolated intraday market-data feasibility measurement".
- Permissions added in debug only: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `ACCESS_NETWORK_STATE`.
- User-started only:
  - `START_NOT_STICKY`; a null restart intent stops the service without creating a session;
  - no boot receiver, alarm, WorkManager, wake lock, or battery-exemption request.
- Notification: "BJStock 12-B2 Probe", "VIRTUAL / KRX / NO TRADING", the current state, and a Stop action.
- `onTimeout` (API 35+) records `FGS_TIMEOUT` and stops the session.

## 4. Delta corrections F-1 … F-9

| Finding | Correction | Tests |
|---|---|---|
| F-1 ERROR must stay stoppable | ERROR is not terminal. While a session exists, Stop stays enabled (`canStop`). Stop works from any state: it cancels the heartbeat, network and minute-bar jobs; stops and joins the stream and reconnect; unregisters the platform listeners; finalizes evidence and summary; then the service removes the notification and stops. Start while a session exists returns `REFUSED_ACTIVE_SESSION` with "Stop it before starting a new one." | `errorSessionRequiresStopThenAllowsANewStart` |
| F-2 MemoryLimiter flag | `memory_limiter_suspected` is true only for `REASON_OTHER` plus a description containing `MemoryLimiter` / `MemoryLimiter:AnonSwap`. `REASON_LOW_MEMORY` never sets it. Raw reason, timestamp and description are kept. | `memoryLimiterClassificationIsConservative` |
| F-3 Clock evidence (M-19 / M-20) | See §6 | `networkTime*`, `clockChangeBroadcastsAreObservedAndUnregisteredOnStop`, `controllerWritesIsolatedSessionEvidenceAndStopsCleanly` |
| F-4 WS allowlist denial | An endpoint denial (including one wrapped as a cause) or a subscription denial terminates the stream: no retry, no backoff, no reconnect. It records a safe `PROBE_ERROR ALLOWLIST_DENIED` (stage, reason code) and moves the session to ERROR, which remains stoppable. Subscriptions are all checked before any is sent. | `endpointAllowlistDenialIsTerminalWithoutReconnect`, `subscriptionAllowlistDenialSendsNothingAndIsTerminal`, `webSocketDenialMakesSessionErrorButStoppable` |
| F-5 Summary integrity | Stop order: (1) quiesce producers (jobs, stream, reconnect); (2) detach observers; (3) final samples; (4) close the recorder, which rejects new events and drains the queue; (5) derive the summary. Counts cover persisted events only. A record after close returns false and is not counted. Late WebSocket callbacks after stop are ignored. The summary adds `persisted_event_total`, `events_rejected_after_close` and `events_not_persisted`. | `recordAfterCloseIsRejectedAndNotCounted`, `lateCallbacksAfterStopProduceNoEventsAndNoReconnect`, summary equals JSONL check |
| F-6 Notification cleanup | `ProbeNotificationLifecycle` cancels and joins the state collector before cancelling the notification, so nothing is reposted afterwards. Gate refusal, storage refusal, normal Stop and ERROR → Stop all use `STOP_FOREGROUND_REMOVE`; nothing detaches the notification. | `ProbeNotificationLifecycleTest`, static `serviceRemovesNotificationOnEveryForegroundExit` |
| F-7 First after reconnect | Tracked independently for 005930 and 000660. Missing symbols are never inferred. | `firstAfterReconnectIsPerSymbol`, `firstAfterReconnectIsMarkedOncePerSymbol` |
| F-8 Storage failure | Evidence storage is resolved and validated before the credential gate and transport. If unavailable: no exception escapes, no credential lookup, no transport, no network, no fallback directory or database. State becomes `ERROR` "PROBE_STORAGE_FAILED: Probe evidence storage failed.", and Stop stays possible (a no-op that keeps the message, see B2A-D-F01). | `unavailableStorageFailsSafelyBeforeCredentialsOrTransport` |
| F-9 Real OkHttp WebSocket path | MockWebServer `withWebSocketUpgrade` exercises the real probe transport and interceptor, including the subscription send. The exact VIRTUAL endpoint is checked in OkHttp's WebSocket request form and stopped by a terminal test interceptor before DNS/connect. Denied endpoints (local host, port 21000, `wss`, `/trading/`) are blocked before any connection. | `realOkHttpWebSocketUpgradePassesTheProbeInterceptor`, `exactVirtualEndpointIsRecognizedInOkHttpWebSocketForm`, `deniedWebSocketEndpointsAreBlockedBeforeAnyConnection` |

## 4a. Final LOW delta (B2A-D-F01 … F03)

| Finding | Correction | Tests |
|---|---|---|
| B2A-D-F01 Start-failure message overwritten | Cause: after a storage refusal the service's `onDestroy` called `controller.stop("SERVICE_DESTROYED")` with no session, which replaced `ERROR` "PROBE_STORAGE_FAILED…" with `STOPPED` "No active session.". Now a no-session stop keeps an existing `STOPPED` or `ERROR` result unchanged (and forces `sessionActive=false`). It is idempotent. The service still removes the notification and stops itself; it is not kept alive to hold the message. A no-session stop with no preceding error still reports a benign `STOPPED` state, and a stale non-terminal state becomes `STOPPED` "No active session.". An ERROR with an active session still follows F-1 (explicit Stop, then cleanup, then `STOPPED`). | `unavailableStorageFailsSafelyBeforeCredentialsOrTransport` (adds service cleanup + repeated Stop), `noSessionStopWithoutPriorErrorIsBenign`, `missingVirtualCredentialsStaysStoppedAndNeverBuildsATransport`, `errorSessionRequiresStopThenAllowsANewStart` |
| B2A-D-F02 REST observation continued after WS policy denial | One authoritative terminal-policy signal per session (`onPolicyDenied`). It is raised by the WebSocket terminal callback and by a REST allowlist denial (approval, token or minute bars). It sets a session flag, records `NETWORK_POLICY_QUIESCED` (`error_code=ALLOWLIST_DENIED`, `source`, `network_job_was_active`, `terminal=true`), cancels the session's network job (token step and minute-bar loop), and stops the WebSocket (no reconnect, no retry). Every REST attempt checks the flag and coroutine cancellation immediately before it begins, with no suspension point in between. The session stays `ERROR` and manually stoppable. The summary adds `terminal_policy_denial` and `terminal_policy_source`. Ordinary transient network failures are unchanged: WebSocket reconnect with backoff and REST retry of network-level errors still apply. | `webSocketPolicyDenialStopsMinuteBarObservationBeforeAnyNewRequest`, `policyDenialDuringInFlightMinuteBarRequestSchedulesNoFurtherRequest`, `restPolicyDenialAlsoStopsTheWebSocketWithoutReconnect` |
| B2A-D-F03 Rapid Start during service teardown | Untouched. The controller can reach `STOPPED` before the service finishes `stopSelf`/`onDestroy`; a rapid Start in that window can create a short session that service destruction then closes. No orphan transport, no safety violation, summary closes cleanly. **ACCEPTED / DEFERRED INFO.** | — |

**In-flight request semantics (F02).** The real transport runs a blocking OkHttp `execute()` inside `withContext(Dispatchers.IO)`. Cancelling the network job does **not** interrupt a request that already began before the asynchronous denial was observed: that request runs to completion or timeout. When the cancelled coroutine resumes, `withContext` throws `CancellationException`, so the response is discarded (no `REST_REQUEST_END`, no state change) and no further request is scheduled. A request whose pre-start check ran before the flag was set counts as already in flight. Nothing from any probe response reaches business or trading state. In unit tests the fake request suspends cooperatively and is cancelled at once.

## 5. F-10 — H0STCNT0 field width (first-live-frame rule)

- The parser stays fail-closed. `declared_count × 47` must equal the actual field count. Any other width gives `WIDTH_MISMATCH` and **no records**. There is no 46-field support, column shifting, or guessed mapping.
- **First Smoke must inspect the first valid H0STCNT0 data frames** (`WS_FRAME` `field_count`, `observed_width`, `issue`).
  - If VIRTUAL returns 47 fields: continue the bounded smoke.
  - If VIRTUAL consistently returns 46 fields / `WIDTH_MISMATCH`: STOP the live probe once enough evidence establishes the shape. Do not guess a mapping, shift columns, or fabricate records. A separate evidence-based parser delta is then required.

## 6. Clock evidence (M-19 / M-20)

- **`NETWORK_TIME_SAMPLE`** (API 33+ `SystemClock.currentNetworkTimeClock()`), recorded at `SESSION_START`, `PERIODIC` (every 10 heartbeats, about 5 minutes), `TIME_CHANGED`, `TIMEZONE_CHANGED` and `SESSION_STOP`.
  - Fields: `wall_time_epoch_ms`, `elapsed_realtime_nanos`, `network_time_available`, `network_time_epoch_ms` and `wall_minus_network_ms` (only when available), `sdk_int`, `sample_reason`, `capture_span_nanos`, `reference_kind`, `evidentiary_scope`.
  - Unavailable (below API 33, or the API throws): `network_time_available=false` with a reason. Zero is never invented, the probe never fails because of it, and there is no NTP or other internet time source. No allowlist was widened.
- **Clock changes:** `ACTION_TIME_CHANGED` records `WALL_CLOCK_CHANGED`, and `ACTION_TIMEZONE_CHANGED` records `TIMEZONE_CHANGED` with the zone id. Each immediately triggers a new network-time sample. The probe never changes the clock or timezone and requests no extra privileges.
- **Drift:** raw periodic samples only; drift is derived later. It is not a trading rule.
- **Claim boundary:**
  - Android network time is a reference observation only. It is not exchange time, not security-grade time, and does not prove exchange-time correctness.
  - It never sets timestamp semantics or a clock-offset bound for the strict-after classifier.
  - Same-second stays `UNPROVEN_SAME_SECOND`. `PROVABLY_LATER` still requires every explicit prerequisite.
  - Test `networkTimeSampleAloneCannotProveStrictAfter` verifies this.

## 7. M-28 build / source provenance

- The probe code is currently uncommitted, so no final commit hash is recorded here and none is invented. Session meta records `build_provenance.source_commit = "externally_bound_at_run_gate"` together with versionName / versionCode.
- **M-28 source/build provenance will be finalized at the physical-run gate, after implementation review and commit.** The physical-run evidence bundle must record:
  - the Git HEAD used to build the APK;
  - the APK SHA-256;
  - app versionName / versionCode;
  - the device package/version confirmation.
- This mapping ties the installed APK to the reviewed source build. Production Gradle is not modified to inject a Git SHA.

## 8. Known risks and measurement items

1. **Cleartext WebSocket — HUMAN ACKNOWLEDGED FOR THE BOUNDED 12-B2 VIRTUAL SMOKE ONLY.**
   - The official VIRTUAL WebSocket endpoint is `ws://` (cleartext); `ws` was not switched to `wss` because no official KIS evidence supports it. The approval key and subscription traffic travel unencrypted, by KIS design.
   - The Android Network Security Config applies to the whole **debug app**, not only to the probe class. Cleartext traffic to `ops.koreainvestment.com` is therefore technically possible for other debug code as well.
   - Current repository inspection found no other code path that uses that host for cleartext WebSocket traffic.
   - The probe allowlist still enforces VIRTUAL port 31000, the exact path, H0STCNT0, and 005930 / 000660. The host is shared with the production WebSocket endpoint; the platform config does not restrict the port.
   - Human acceptance: **12-B2 bounded VIRTUAL cleartext WebSocket Smoke: HUMAN ACKNOWLEDGED.**
     - Scope: VIRTUAL only; bounded 12-B2 Smoke only.
     - Does **not** extend to: personal-phone Alpha, PRODUCTION market data, production credentials, or real-money trading.
     - Personal-phone Alpha cleartext: **NOT APPROVED**.
     - No change to `ws://`, the config or endpoints. This acknowledgement is not a Smoke authorization and does not change 12-B2: NOT PASS.
2. **PINGPONG compatibility: UNVERIFIED — BOUNDED SMOKE MEASUREMENT.** No protocol change; instrumentation preserved.
   - The KIS sample answers with a WebSocket pong frame. OkHttp cannot send an unsolicited pong, so the probe echoes the PINGPONG text, and only when it is at most 512 characters and contains no approval key.
   - Instrumentation records: each PINGPONG received (`frame_kind=PINGPONG`); reply attempted / sent / skipped reason (`pingpong_reply_mode=TEXT_ECHO_UNVERIFIED`); and on disconnect, `connection_duration_nanos`, `pingpong_count_this_connection` and `since_last_pingpong_nanos`, followed by reconnect scheduling and attempt timing.
   - Raw control payloads are not persisted. No protocol compatibility is claimed.
3. **Exported debug activity — DEBUG-ONLY ACCEPTED FOR PROBE ACCESS; NOT A PRODUCTION DESIGN.** Verified:
   - the service is `exported="false"`;
   - launching the activity externally cannot start the service; only the Start button does;
   - the activity reads no intent extras, so extras cannot choose environment, host, port, TR, symbols or venue;
   - the service reads only the boolean `rest_minute_bars` extra from its own app's intents;
   - no credentials are displayed.
4. **Pre-existing merged permissions.** `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` and `ACCESS_NETWORK_STATE` appear in the merged release manifest from pre-existing dependencies (WorkManager). The probe does not use them to auto-start, hold a wake lock, or schedule work. They are left unchanged.
5. **Encrypted frames** (flag `1`) are recorded as `ENCRYPTED_UNSUPPORTED` with no records. H0STCNT0 is documented as unencrypted.

## 9. Evidence format

- Location: `getExternalFilesDir("intraday_probe")` only, with the files `session_<id>_meta.json`, `session_<id>_events.jsonl` and `session_<id>_summary.json`. No Room, no auto-delete.
- Session id: `probe_<yyyyMMddTHHmmssZ>_<8 hex>`.
- Every event carries `session_id`, `event_type`, `wall_time_epoch_ms` and `elapsed_realtime_nanos`.
- Provider `STCK_CNTG_HOUR` and `BSOP_DATE` are stored raw; no milliseconds are manufactured. `derived_*` fields are labelled as derived.
- Terminal policy denial: `NETWORK_POLICY_QUIESCED` event; summary fields `terminal_policy_denial`, `terminal_policy_source`, `ws_terminated_by_policy`.
- Continuity: reconnects and heartbeat gaps (30 s monotonic interval, 15 s threshold) are recorded as `UNPROVEN_POSSIBLE_GAP`; data is never backfilled.
- Platform evidence:
  - screen and Doze/power-save broadcasts; default-network callback;
  - battery, thermal and per-uid traffic samples;
  - the last 5 `ApplicationExitInfo` entries (API 30+).

## 9a. External runtime observation carried forward from Phase 11 (not probe evidence)

Phase 11 B10 on 2026-10-02 (production app, not the probe) observed:

- WorkManager 07:00 scheduler timing: PASS.
- Automatic worker start: PASS.
- Background network access: FAIL. Android netpolicy `APP_BACKGROUND` blocked provider access.
- The network became usable when BJStock moved to TOP / foreground.

This observation proves nothing yet about `specialUse` FGS, screen-off runtime, Doze behaviour, or Phase 12 Android-local feasibility. It is not proof that an FGS works, and Phase 12 does not yet solve Phase 11. It raises the importance of the 12-B2 physical measurements of FGS survival, background network access with the screen OFF, and the distinction between a foreground app and a foreground service. No Phase 11 code was changed. Configurable intraday cadence (e.g. 15 / 20 / 30 minutes with deterministic slot anchors) is a future architecture delta and is not implemented here.

## 10. Verification performed (no provider call, no device, no credentials)

- Probe-focused tests: 73 tests, 0 failures (final LOW delta revision).
- `gradlew.bat :app:testDebugUnitTest --rerun-tasks`: 810 tests, 0 failures. 1 skipped: a pre-existing, environment-gated real-database migration test, unrelated to the probe.
- `gradlew.bat :app:assembleDebug`: build succeeded. The APK was not installed.
- `git diff --check`: clean, including all untracked probe files.
- Merged debug manifest contains the probe activity, the service, the four permissions and the network security config. The merged release manifest contains none of them.

## 11. WS-CONNECT-02 — bounded WebSocket connection-stage telemetry (telemetry only)

- **Reason.** The B2 physical smoke recorded 10 initial WebSocket connect failures. The evidence contained only `error_class=IOException`. DNS success was observed for 10 of 11 attempts (attempts 1 and 3–11); no DNS lookup line was logged for attempt 2, which may have used a cached result. Where a DNS success was observed, the failure followed it by about 100–220 ms. WS-CONNECT-01 (read-only) could not classify the failure further. The root cause remains **UNKNOWN**.
- **No behavioral authority.** This change adds observation only. These are unchanged:
  - the endpoint, allowlist, timeouts, ping interval and `retryOnConnectionFailure`;
  - the backoff schedule (1, 2, 5, 10, then 30 s, no jitter, no limit, reset on open);
  - approval and approval-key reuse, subscription timing and payload, and the parser;
  - the FGS, KIS REST, the database and `app/src/main`.
  - Every hook calls its delegate exactly once, returns its exact result or rethrows its exact exception, and drops its own telemetry failures. Tests pin each of these points.
- **B2 result not rewritten.** The B2 classification (PASS — UNATTENDED WITH LIMITATIONS) stands as recorded. This telemetry only serves a later, separately authorized run.
- **Hooks.** `KisProbeTransport.connect` builds a per-attempt client with `wsClient.newBuilder()`, which shares the dispatcher and connection pool. On that client:
  - a DNS wrapper (`ProbeTelemetryDns`) delegates to the original DNS;
  - an application interceptor (`ProbeConnectStageInterceptor`) sits after the endpoint allowlist interceptor;
  - the listener's `onFailure` now also passes the response status.
- **OkHttp 4.12 limitation (verified in bytecode).**
  - For WebSocket calls, `RealCall` skips network interceptors.
  - `RealWebSocket.connect` replaces the EventListener with `EventListener.NONE`.
  - As a result, the TCP connection, the remote IP:port and the TCP/upgrade boundary are **not observable**. The stage events record `tcp_connection_observable=false` rather than guess.
  - This corrects the WS-CONNECT-01 suggestion to use a network interceptor.
  - The application interceptor sees the whole upgrade call, including OkHttp's internal route retries.
- **New events.** Every new event carries `attempt` (the session's connect-attempt number) and `since_attempt_start_nanos` (monotonic). `WS_FAILURE` and `WS_DISCONNECTED` now carry the same two fields.

| Event | Fields |
|---|---|
| `WS_DNS_START` | `host_is_provider_endpoint`; `host` only when it is the fixed VIRTUAL WebSocket host |
| `WS_DNS_END` | `result`, `elapsed_nanos`; on success `address_count`, `ipv4_count`, `ipv6_count` (never a literal address); on failure `error_class` |
| `WS_CONNECT_STAGE_START` | `tcp_connection_observable=false` |
| `WS_UPGRADE_RESPONSE` | `elapsed_nanos`, `response_code` |
| `WS_CONNECT_STAGE_FAILURE` | `elapsed_nanos`, `error_class` |
| `WS_FAILURE` (added fields) | `error_message`; `cause_N_class` / `cause_N_message` (up to 7 causes, identity-based cycle stop, `cause_chain_truncated`); `suppressed_count`, `suppressed_N_class` / `suppressed_N_message` (up to 3); `response_present`, and `response_code` only when a response exists |

- **Security boundary.**
  - Never recorded: the app key, app secret, approval key, access token, any Authorization or Bearer value, request or response headers or bodies, and the subscription JSON.
  - Each message is first passed through the session `SecretScrubber`. The credentials and approval key are registered before the WebSocket connects.
  - A message that names any `ProbeRedaction.FORBIDDEN_KEYS` entry is withheld whole.
  - A message longer than 160 characters is truncated after scrubbing.
  - The evidence store scrubs every line again at write time.
  - Response telemetry is limited to `response_present` and `response_code`.
  - No stack traces, no Logcat output, and no logging interceptor.
- `evidence_schema_version` is 3.
- **Verification (no device, no provider call, no install).**
  - Probe package: 97 tests, 0 failures, 5 consecutive runs.
  - `:app:testDebugUnitTest`: 834 tests, 0 failures, 1 pre-existing skip.
  - `:app:assembleDebug`: succeeded, with build output redirected outside the repository so the reviewed B2 APK was not overwritten.
  - A real MockWebServer test shows the probe client sends the same upgrade request line and headers as a plain OkHttp client, except the random `Sec-WebSocket-Key`.
  - A peer close before any response surfaces as `IOException` "unexpected end of stream" with no response.

## 12. Stage B-1 / BG-RECONNECT — new socket opened while NOT TOP (2026-10-12)

- **Final status: PASS_UNATTENDED_WITH_LIMITATIONS** (Human accepted, 2026-10-12). Run: PH12-BG-RECONNECT-02. Stage B-1 only; no source change, build or install.
- **Device and build.**
  - Samsung SM-F711N (Galaxy Z Flip3), Android 15 / API 35.
  - Installed APK SHA-256 `3fa7ce667bdaacfed5f058f8000371d7bbe12a8db58260af4b5911a9e3434c70`, the UI-integration candidate built from merge `a2031de`. The probe source is unchanged since `c9a5634`. Main at run time: `51b1f15`.
- **Accepted session:** `…004550Z_7b4ad0b5`. Scope: VIRTUAL, `H0STCNT0`, venue J, 005930 + 000660, REST minute-bar observation off.
- **Evidence:** archived outside the repository at `D:\ProjectArchive\BJStock\Phase12\PH12-BG-RECONNECT-02\b1_capture\`; it is not copied here. No secret values are recorded, and the Logcat capture was secret-filtered.
- **Ordering** (KST, 2026-10-12):

| Event | Time |
|---|---|
| `SESSION_START` | 09:45:50.025 |
| `FGS_START` | 09:45:50.029 |
| Home (launcher resumed) | 09:45:50.960 |
| NOT TOP (probe Activity lost top-resumed state) | 09:45:50.962; sampler confirmation 09:45:51.366 |
| First `WS_CONNECTING` | 09:45:51.555 |
| HTTP 101 (`WS_UPGRADE_RESPONSE`) | 09:46:00.192 |
| `WS_CONNECTED` | 09:46:00.195 |
| `WS_SUBSCRIBE_SENT` | 09:46:00.197 / 09:46:00.198 |
| First `WS_RECORD` | 09:46:00.359 |
| Foreground return | 09:58:14.785 |
| `SESSION_STOP` | 09:58:29.403 |

- **Interpretation.** A new WebSocket connection was initiated after Home while BJStock was NOT TOP and the foreground service was alive. This is **not** existing-socket continuity evidence, which is what the B2 approved scope covers.
- **Background state** (snapshots at T0 09:45:51.366, T+1 09:46:51.551 / 09:46:54.472, T+5 09:50:57.915 and T+10 09:55:50.199):
  - the launcher was TOP and BJStock was NOT TOP;
  - procState was FGS, and `IntradayProbeService` was foreground and running;
  - netpolicy `blocked` included `APP_BACKGROUND`; the effective blocked reasons were NONE;
  - the screen was ON for the connection and for almost the entire 10-minute observation;
  - USB was plugged in and Battery Saver was OFF.
- **Scheduler separation.**
  - No BJStock job was active or pending at any snapshot, and the app log shows no WorkManager activity.
  - The next job stayed 2026-10-13 07:00.
  - No Auto change, Run Now or scheduler mutation.
- **Connection result.**
  - 4 attempts. Attempts 1–3 failed. Attempt 4 got HTTP 101 and reached `WS_CONNECTED`.
  - Records: 21,893 before the foreground return; 22,564 in total.
  - No disconnect after the successful connection. `ws_reconnects` = 3.
  - Attempts 1–3 are **not** Android background failures (see the failure interpretation below).
- **Failure interpretation.**
  - The foreground and background failure signatures were consistent.
  - DNS succeeded.
  - The failures were predominantly `IOException` "unexpected end of stream" with `EOFException` and no HTTP response.
  - netpolicy effective remained NONE.
  - Therefore no Android-background-specific failure was established, and provider / connection-layer behavior is consistent with the evidence. The root cause remains **UNKNOWN**. No KIS server root cause is claimed.
- **Limitations.**
  - **LIMITATION-1: NON_BLOCKING_TIMING_LIMITATION.** The device's normal 10-minute screen timeout started the transition to Dozing right around the exact T+10 boundary. This is non-blocking because the new connection and the record flow were already established and observed while the screen was awake. The actual screen-off happened after the T+10 boundary.
  - **LIMITATION-2:** Launcher touch events were not fully captured. No BJStock foreground rescue occurred before success.
  - **LIMITATION-3:** The evidence bundle does not contain the terminal database / operation snapshot for the 2026-10-12 07:00 slot. Scheduler separation during the B-1 session itself was verified.
- **Earlier sessions the same morning.** Neither contaminates the accepted session. Both were stopped before it started, and session 2 never connected.
  - Session 1 (`…003657Z_f20d23ea`): **INVALID**. Socket attempts began while BJStock was TOP, and later foreground actions contaminated the run.
  - Session 2 (`…004326Z_b40b8af5`): **FOREGROUND_FAILURE_ONLY**. All attempts occurred while TOP, and no connection succeeded.
- **Not proven by Stage B-1** (unchanged):

| Item | Status |
|---|---|
| Screen-off reconnect | UNPROVEN |
| Deep Doze reconnect | UNPROVEN |
| Stage B-2 | UNPROVEN at Stage B-1; later PASS_UNATTENDED_RECONNECT_WITH_LIMITATION (§13) |
| Background FGS start | UNPROVEN |
| Unattended 07:00 scheduler acceptance | NOT SATISFIED |
| General provider reliability | NOT ESTABLISHED |

## 13. Stage B-2 / BG-RECONNECT — established socket reconnected after Wi-Fi loss while NOT TOP (2026-10-12)

- **Final status: PASS_UNATTENDED_RECONNECT_WITH_LIMITATION** (Human accepted, 2026-10-12). Run: PH12-BG-RECONNECT-B2-EXECUTION. Stage B-2 only; no source change, build or install.
- **Device and build.**
  - Samsung SM-F711N (Galaxy Z Flip3), Android 15 / API 35. USB plugged in, Battery Saver OFF. Wi-Fi was the only network; there is no SIM, so mobile data could not take over.
  - Installed APK SHA-256 `3fa7ce667bdaacfed5f058f8000371d7bbe12a8db58260af4b5911a9e3434c70`, the same build as Stage B-1 (§12). Main at run time: `25e0830`.
- **Accepted session:** `…20261012T022626Z_88766482`. Scope: VIRTUAL, `H0STCNT0`, venue J, 005930 + 000660, REST minute-bar observation off.
- **Evidence:** archived outside the repository at `D:\ProjectArchive\BJStock\Phase12\PH12-BG-RECONNECT-B2\b2_capture\`; it is not copied here. No secret values are recorded, and the Logcat capture was secret-filtered.
- **Core result.** An established WebSocket was:
  1. lost after an Android-visible Wi-Fi interruption;
  2. detected as failed;
  3. followed by reconnect scheduling and retries;
  4. reconnected after Wi-Fi restoration;
  5. re-subscribed;
  6. followed by resumed records;

  all while BJStock remained NOT TOP and before the foreground return.
- **Ordering** (KST, 2026-10-12):

| Event | Time |
|---|---|
| `SESSION_START` / `FGS_START` | 11:26:26.064 |
| First `WS_CONNECTING` | 11:26:27.019 |
| Home (launcher resumed) | 11:26:27.078 |
| NOT TOP (probe Activity lost top-resumed state) | 11:26:27.080 |
| Initial HTTP 101 (`WS_UPGRADE_RESPONSE`) | 11:26:27.158 |
| Initial `WS_CONNECTED` | 11:26:27.161 |
| Initial `WS_SUBSCRIBE_SENT` | 11:26:27.163 / 11:26:27.164 |
| Initial first `WS_RECORD` | 11:26:27.344 |
| Wi-Fi disable (adb command) | 11:27:47.505 – 11:27:47.828 |
| `NETWORK_LOST` | 11:27:47.999 |
| Socket failure (`WS_FAILURE`) | 11:27:48.121 |
| Wi-Fi enable (adb command) | 11:28:17.635 – 11:28:17.884 |
| `NETWORK_AVAILABLE` | 11:28:21.041 |
| Network validated | 11:28:21.431 (probe); 11:28:21.728 (sampler) |
| New HTTP 101 | 11:29:36.647 |
| New `WS_CONNECTED` | 11:29:36.650 |
| Re-subscribe (`WS_SUBSCRIBE_SENT`) | 11:29:36.658 / 11:29:36.660 |
| First post-reconnect `WS_RECORD` | 11:29:37.170 (005930) / 11:29:37.332 (000660) |
| Foreground return | 11:33:39.117 |
| `SESSION_STOP` | 11:33:44.186 |

- **Initialization limitation: B2_INITIALIZATION_LIMITATION.**
  - The first `WS_CONNECTING` occurred 59 ms before Home, while the probe Activity was still TOP. HTTP 101 and `WS_CONNECTED` occurred after Home.
  - This session must **not** be reused as Stage B-1 proof. Stage B-1 was proven separately (§12).
  - The reconnect judged by Stage B-2 occurred more than 3 minutes after Home, while BJStock was firmly NOT TOP. This limitation does not make Stage B-2 INVALID.
- **Pre-drop stability.** The initial connection was stable for about 80 seconds, with 729 records before the Wi-Fi disable and no prior socket failure.
- **Network loss and reconnect.**
  - `NETWORK_LOST` was recorded, and the established socket was aborted: `SocketException` "Software caused connection abort", `connection_opened=true`.
  - The reconnect loop stayed active. There were 7 reconnect attempts after the drop.
  - During the outage the attempts failed at DNS (`UnknownHostException`).
  - After the restore, two attempts failed with DNS success and `IOException` "unexpected end of stream" with `EOFException` and no HTTP response.
  - Attempt 8 succeeded.
- **Post-reconnect result.**
  - A new `WS_CONNECTED` was followed by resent subscriptions, and `first_after_reconnect` was marked for both symbols.
  - 2,590 records arrived after the reconnect and before the foreground return.
  - The 3-minute stability observation passed. There was no immediate disconnect loop.
- **Background state** (continuous sampler from Home to the foreground return, plus snapshots before the drop, during the outage, after the restore and after the reconnect):
  - the launcher was TOP and BJStock was NOT TOP;
  - procState was FGS, and `IntradayProbeService` was foreground and alive;
  - netpolicy `blocked` included `APP_BACKGROUND`; the effective blocked reasons were NONE;
  - the screen was Awake throughout;
  - there was no foreground rescue before the reconnect.
- **Scheduler separation.** No BJStock job was active or pending at any snapshot, and the app log shows no WorkManager activity. The next job stayed 2026-10-13 07:00. No Auto change, Run Now or scheduler mutation.
- **Failure interpretation.**
  - The two post-restore failed attempts occurred with validated Wi-Fi, effective blocked reasons NONE, DNS success, and the same "unexpected end of stream" / `EOFException` / no-response signature seen in Stage B-1 (§12) and WS-CONNECT-02 (§11).
  - Therefore no Android background failure and no reconnect-logic failure was established, and provider / connection-layer behavior is consistent with the evidence. The root cause remains **UNKNOWN**. No KIS server root cause is claimed.
- **Temporary device setting.**
  - `stay_on_while_plugged_in` went 0 → 2 → 0. It was restored after the run.
  - `screen_off_timeout` stayed 600000 ms.
  - Wi-Fi was restored ON and validated.
  - No device-setting change remains.
- **Scope boundary:**

| Item | Status |
|---|---|
| Android-visible Wi-Fi loss reconnect (screen ON, NOT TOP, TOP-started FGS) | PROVEN |
| Silent upstream outage recovery | UNPROVEN |
| Screen-off reconnect | UNPROVEN |
| Deep Doze continuity | DEFERRED |
| Deep Doze reconnect | DEFERRED |
| Background FGS start | UNPROVEN |
| General provider reliability | NOT ESTABLISHED |
