# 162 — Intraday Isolated Probe Implementation (Phase 12-B2-A)

- Status: DRAFT — Implementation Evidence Awaiting Physical Verification
- Baseline: edcc29c (docs/161 approved). The probe code is uncommitted; see §7 for build provenance.
- Gate: 12-B2
- Scope: debug-only isolated probe. VIRTUAL / KIS quotation / H0STCNT0 / venue J (KRX) / 005930 + 000660 / candidate FGS `specialUse`.
- This document records implementation facts only. It claims no gate result. **12-B2: NOT PASS.**
  - Option A: **POSSIBLY FEASIBLE — NEEDS PHYSICAL PROBE** (unproven).
  - Android-local: **POSSIBLY FEASIBLE — NEEDS PHYSICAL PROBE** (unproven).

## 0. Review and correction status

| Step | Result |
|---|---|
| Independent implementation review (Claude Code) | NO SAFETY BLOCKER; delta correction required before any live device/provider run |
| Delta correction F-1 … F-9 | Implemented, uncommitted (this revision) |
| F-10 (46 vs 47 fields) | Measurement rule only, see §5; parser unchanged |
| Independent delta re-review (Claude Code) | PASS WITH FINDINGS — BLOCKER 0, HIGH 0, MEDIUM 0, LOW 2, INFO 1 |
| B2A-D-F01 (start-failure message preserved) | **RESOLVED** (confirmed by the independent final LOW delta verification), see §4a |
| B2A-D-F02 (terminal policy denial quiesces all probe network work) | **RESOLVED** (confirmed by the independent final LOW delta verification), see §4a |
| B2A-D-F03 (rapid restart during service teardown) | **INFO — accepted/deferred**, see §4a |
| Final LOW delta verification (independent) | **PASS** — completed. BLOCKER 0, HIGH 0, MEDIUM 0, LOW 0; remaining findings INFO only |
| Phase 12-B2-A implementation review | **COMPLETE**. This does not change the gate: 12-B2 remains **NOT PASS** — physical / provider evidence still pending |

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
  - Errors record class names and probe-defined codes only; exception messages are never stored or displayed.
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
