# 163 Security / Tamper-Evident Audit / Failure-Containment Architecture

- Document Status: APPROVED — Phase 12-S0 Architecture Closed
- Gate: Phase 12-S0
- GateStatus: CLOSED
- HumanApproval: APPROVED
- IndependentVerification: PASS (final independent verification: PASS WITH FINDINGS — INFO ONLY)
- Final Finding Counts: BLOCKER 0, HIGH 0, MEDIUM 0, LOW 0, INFO 2
- Architecture Baseline: edcc29c
- ImplementationAuthority: NONE
- Related: [docs/150](150_OPERATIONAL_RELIABILITY_STANDARD.md) (Operational Reliability Standard), [docs/160](160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md) (Intraday architecture, HUMAN APPROVED), [docs/161](161_INTRADAY_PROVIDER_PLATFORM_FEASIBILITY.md) (12-B1 feasibility, HUMAN APPROVED), [docs/162](162_INTRADAY_ISOLATED_PROBE_IMPLEMENTATION.md) (12-B2-A probe, DRAFT)
- Authoring Date: 2026-10-02

**This approval authorizes architecture only.** It does **not** authorize: 12-S1 implementation; schema / migration work; runtime changes; physical Smoke; 12-C; strategy implementation; real trading.

This document is architecture and design only. **Nothing described here as PROPOSED exists in the repository.** It authorizes no Kotlin, Room schema, migration, manifest, scheduler, provider, device, or credential work. It selects no cryptographic primitive, threshold, quota, retention period, or anchor destination as final; those are FUTURE HUMAN DECISIONS (§46) and require an independent security review. The architecture decisions HD-A0 … HD-A5 are **HUMAN APPROVED** and recorded in §46.1; that architecture approval does **not** authorize S1 implementation, a DB migration, code changes, Smoke, 12-C, or real trading.

PAPER TRADING ONLY. No real brokerage order capability. KIS `/trading/` remains forbidden.

## 0. Status Summary

| Item | Status |
| --- | --- |
| Phase 12-S0 | **APPROVED / CLOSED** (Human approval). Review history: first independent security review HOLD — ARCHITECTURE DELTA REQUIRED (BLOCKER 0, HIGH 0, MEDIUM 7, LOW 9, INFO 3); independent delta review PASS WITH FINDINGS (BLOCKER 0, HIGH 0, MEDIUM 0, LOW 3, INFO 3); final independent verification **PASS WITH FINDINGS — INFO ONLY (BLOCKER 0, HIGH 0, MEDIUM 0, LOW 0, INFO 2)**, both INFO items closed (§0.3) |
| Architecture | APPROVED (architecture only) |
| Independent Verification | PASS |
| Human architecture decisions HD-A0 … HD-A5 | **HUMAN APPROVED** (§46.1). Architecture approval only; authorizes no S1 implementation, DB migration, code change, Smoke, 12-C, or real trading |
| ImplementationAuthority | NONE |
| 12-S1 ImplementationAuthority | NONE |
| Physical Smoke authority | NOT granted by this document |
| Strategy ImplementationAuthority | NONE |
| Next runtime action | Phase 12-B2 bounded physical / provider Smoke (requires its own Human authorization) |
| Next security implementation | 12-S1 only **after** the 12-B2 Smoke (HD-A0) **and** a separate Human authorization |
| Phase 12-B2-A (probe implementation) | Implementation review complete; 12-B2 NOT PASS; physical / provider Smoke NOT STARTED (docs/162) |
| 12-B2 bounded VIRTUAL cleartext Smoke | HUMAN ACKNOWLEDGED (bounded VIRTUAL Smoke only) |
| Personal-phone Alpha cleartext | NOT APPROVED (HD-C10) |
| Phase 12-C | NOT AUTHORIZED |
| Tamper-evident audit chain, signed checkpoints, manifest, hourly digest, off-device anchor, Safe Mode, independent verifier, AI export | Architecture APPROVED — none implemented |
| Real trading | NOT AUTHORIZED; separate future gates required (§49) |

Metadata reconciliation (S0-R-F17): docs/162 §8 item 1 previously read "HUMAN ACKNOWLEDGEMENT PENDING" for the bounded Smoke cleartext exception. It was corrected in the Phase 12-S0 finalization revision to HUMAN ACKNOWLEDGED for the bounded 12-B2 VIRTUAL cleartext WebSocket Smoke only. The acknowledgement does not extend to the personal-phone Alpha, PRODUCTION market data, production credentials, or real-money trading. The former known metadata delta is **RECONCILED**.

### 0.1 Independent Review Delta Status (S0-R-F01 … S0-R-F18)

| Finding | Severity | Status | Where addressed |
| --- | --- | --- | --- |
| S0-R-F01 Parallel operational audit risk | MEDIUM | CLOSED (confirmed by final independent verification) | §8.1 (authority invariant), §9.3, §23, §28 |
| S0-R-F02 Atomicity and coverage | MEDIUM | CLOSED (confirmed by final independent verification) | §9.4.4–§9.4.5 (same-transaction rule, `COMMITMENT_MISSING`, `commitment_coverage_start`), §12.1 (segment sealing protocol) |
| S0-R-F03 Mutable row commitments | MEDIUM | CLOSED (confirmed by final independent verification) | §9.4.1–§9.4.3, §9.4.6 (immutable vs mutable classes, per-transition commitments, completeness) |
| S0-R-F04 Crash classification | MEDIUM | CLOSED (confirmed by final independent verification) | §23 (session ≠ process, `UNKNOWN` initial classification, PID / process start / boot correlation), §26 (fatal-evidence-only counting) |
| S0-R-F05 Startup integrity gate | MEDIUM | CLOSED (confirmed by final independent verification) | §29.1 (minimal gate), §47 (Alpha MUST) |
| S0-R-F06 Safe Mode without successful audit write | MEDIUM | CLOSED (confirmed by final independent verification) | §25.1 (re-derivation, write-failure behaviour, acknowledgement scope) |
| S0-R-F07 Build provenance | MEDIUM | CLOSED (confirmed by final independent verification) | §30 (runtime-observed vs out-of-band identity), §22, §45 |
| S0-R-F08 Canonicalization details | LOW | CLOSED (confirmed by final independent verification) | §10 (no silent NFC, domain-tag registry, decimal-scale registry, Instant truncation), §9.4.3 |
| S0-R-F09 Key trust / high-water mark | LOW | CLOSED (confirmed by final independent verification) | §14 (explicit enrollment, TOFU labelling), §5 G |
| S0-R-F10 External anchor minimum semantics | LOW | CLOSED (confirmed by final independent verification) | §16.1 |
| S0-R-F11 Registry consistency | LOW | CLOSED (confirmed by final independent verification) | §45.1, §51 (`CHECKPOINT_SIGN_FAILED` resolved) |
| S0-R-F12 Correlation and full-audit export | LOW | CLOSED (confirmed by final independent verification) | §9.1 (correlation fields), §40 |
| S0-R-F13 Alpha build profile | LOW | CLOSED (confirmed by final independent verification) | §46 HD-C9 (not approved) |
| S0-R-F14 Alpha market-data transport | LOW | CLOSED (confirmed by final independent verification) | §46 HD-C10 (not approved); §0 |
| S0-R-F15 Environment default PRODUCTION | LOW | CLOSED (confirmed by final independent verification) as an architecture finding: recorded as repository fact. The `PRODUCTION` default itself remains **MUST RESOLVE BEFORE PERSONAL-PHONE ALPHA** (HD-C11); no code change | §2.2, §46 HD-C11, §47 |
| S0-R-F16 Crash-loop time basis | LOW | CLOSED (confirmed by final independent verification) | §26 |
| S0-R-F17 Human authority metadata | INFO / metadata | CLOSED — docs/162 §8 cleartext metadata reconciled in the finalization revision | §0 |
| S0-R-F18 Smoke vs S1 sequencing | INFO / sequencing | CLOSED — sequencing rule frozen; HD-A0 HUMAN APPROVED (Smoke before S1) | §48.1, §46.1 HD-A0 |

All S0-R findings are closed; the final independent verification confirmed the closures. Closing them changed no architecture.

### 0.2 Delta Review Findings (independent delta review: PASS WITH FINDINGS)

| Finding | Severity | Status | Where addressed |
| --- | --- | --- | --- |
| S0-D-F01 `session_id` definition | LOW | CLOSED (confirmed by final independent verification) | §9.1 (`session_id` = BJStock operational session), §23, §57 |
| S0-D-F02 Unexpected-termination classification (including the hourly `crash_count` definition) | LOW | CLOSED (confirmed by final independent verification) | §17 (`crash_count` = verified fatal / crash categories only), §21, §23, §29, §45.2 |
| S164-F01 Strict-after SELL wording (docs/164) | LOW | CLOSED (final independent verification: LOW 0) | docs/164 §10 |
| Diagram consistency (`UNEXPECTED_TERMINATION` → `UNKNOWN` → postmortem) | INFO | CLOSED | §52 C |
| Build-confirmation trust limit | INFO | CLOSED | §30 |
| docs/164 READY-blocker visibility | INFO | CLOSED | docs/164 §0, §18 |

No MEDIUM finding was reintroduced. The authority invariant (§8.1, no parallel domain / operational audit subsystem) is unchanged.

### 0.3 Final Independent Verification (PASS WITH FINDINGS — INFO ONLY)

Counts: BLOCKER 0, HIGH 0, MEDIUM 0, LOW 0, INFO 2.

| Finding | Severity | Status | Where addressed |
| --- | --- | --- | --- |
| B2A-S0-I01 Stale §9.4.4 example (an external store "under an HD-A1 topology choice") | INFO | CLOSED / metadata-example correction | §9.4.4 |
| B2A-S0-I02 Status labels may be marked CLOSED | INFO | CLOSED / final status metadata | Header, §0, §0.1, §0.2, §1 |

These INFO items do not change architecture. Phase 12-S0: **APPROVED / CLOSED**.

## 1. Authority / Scope

- Executor role: Phase 12-S0 Architecture Author. ImplementationAuthority: NONE.
- Inputs read: docs/150, docs/160, docs/161, docs/162, plus read-only inspection of the repository structures listed in §2.
- Revision history: initial S0 draft (2026-10-02); architecture delta after the independent security review HOLD (2026-10-02, §0.1); Human decision record for HD-A0 … HD-A5 and final LOW delta after the independent delta review PASS WITH FINDINGS (2026-10-02, §0.2, §46.1); finalization after the final independent verification PASS WITH FINDINGS — INFO ONLY and Human approval (2026-10-02, §0.3): status APPROVED — Phase 12-S0 Architecture Closed.
- Executor role for the latest revision: Phase 12-S0 Finalization / Metadata Author. ImplementationAuthority: NONE.
- No credential, token, approval key, account identifier, or credential preference was read or printed. No provider call, device action, build, or test was performed for this document.
- This document does not modify or reinterpret docs/150, docs/160, docs/161, or docs/162. (The docs/162 §8 cleartext acknowledgement metadata was corrected directly in docs/162 during the finalization task; no docs/162 design changed.) Where a proposal here interacts with an approved decision, the approved decision stands and the interaction is listed in §55.
- Product scope: the Paper Trading Alpha running on the user's real personal Android phone, and the evidence needed to analyze 6+ months of that Alpha later.

## 2. Evidence Labels and Current Repository Baseline

### 2.1 Labels

| Label | Meaning |
| --- | --- |
| CURRENT REPOSITORY FACT | Read in the repository at `edcc29c` (read-only), or in the untracked 12-B2-A probe files described by docs/162 where explicitly stated |
| APPROVED HUMAN REQUIREMENT | Stated in a HUMAN APPROVED document (docs/160, docs/161 §21.1) or in a Human directive for this gate |
| HUMAN APPROVED ARCHITECTURE DECISION | One of HD-A0 … HD-A5, approved by the Human and recorded in §46.1. Binds future design; authorizes no implementation, migration, code change, Smoke, 12-C, or real trading. Details it explicitly leaves open stay open |
| PROPOSED ARCHITECTURE | Architecture designed in this document. With Phase 12-S0 APPROVED / CLOSED it is approved **as architecture only**; it is **not implemented** and does not exist in the repository. Anything marked FUTURE HUMAN DECISION, candidate, OPEN, or not frozen remains undecided |
| FUTURE HUMAN DECISION | Requires an explicit Human decision (§46); never auto-approved |

### 2.2 Current repository facts relevant to security, audit, and failure containment

All rows are CURRENT REPOSITORY FACT unless noted.

| Area | Current state | Evidence |
| --- | --- | --- |
| Error model | `ErrorCategory`, `ErrorSeverity`, `RetryPolicy`, `OperationAction`, `AppErrorCode` catalog, `SafeAppError` / `SafeDiagnostics`, `AppErrorMapper`, `IntegrityViolationException` exist (docs/150 §2–§5) | `core/error/*` |
| Safe text | `SafeLogText` allowlist-oriented redaction helper (docs/150 §17) | `core/error/SafeLogText.kt` |
| Domain audit (WHY) | `trade_audit_logs` with idempotent `event_key`, run / instrument / evaluation / order / execution references, `reason_code`, `observed_value`, `threshold_value`, `operation_id`. DAO exposes no `UPDATE` / `DELETE` query | `TradeAuditLogEntity.kt`, `TradeAuditLogDao.kt`, `TradeAuditLogService.kt` |
| Operational trace (HOW) | `forward_operations` (guarded `RUNNING → terminal` status transitions only) and `operational_events` (DAO exposes no `UPDATE` / `DELETE` query) | `ForwardOperationDao.kt`, `OperationalEventDao.kt`, `ForwardOperationLogService.kt` |
| API errors (WHAT) | `api_error_logs`; 7-day cleanup via `DELETE ... WHERE occurred_at < :cutoff`; `recordOrReport` / `cleanupOrReport` with fallback logger tag `BJStockKisAuth` | `ApiErrorLogDao.kt`, `ApiErrorLogService.kt`, `KisModule.kt` |
| Database | Room `bjstock.db`, schema version 12, `exportSchema = true`; **no database-wide encryption** (no SQLCipher or custom open-helper factory) | `BJStockDatabase.kt` |
| Integrity of audit | **No hash chain, no signature, no manifest, no digest, no off-device anchor.** Append-only semantics rely on the absence of mutating DAO methods only; any process with file access can rewrite `bjstock.db` without detection | Repository search |
| Startup / recovery | `runAppStartSequence`: interrupted-operation recovery (`processStartCutoff`, `PROCESS_INTERRUPTED`) → auto-schedule reconciliation → maintenance. Each step runs through `runIgnoringFailure`, a known docs/150 gap (silent swallowing at startup) | `AppStartSequence.kt`, `BJStockApplication.kt` |
| Crash evidence | No `ApplicationExitInfo` use, no uncaught-exception handler, no clean-shutdown marker | Repository search |
| Diagnostic logging | `android.util.Log` in 7 main-source files; no rotation (logcat only) | Repository search |
| Secret custody | KIS app key, app secret, and access token stored as AndroidKeyStore AES-GCM ciphertext in `SharedPreferences`; key alias `bjstock_kis_secret_key_v1`; purpose encrypt / decrypt; no user-authentication or StrongBox requirement set | `AesGcmSecretCipher.kt`, `EncryptedKisSecretStore.kt` |
| Selected KIS environment default (S0-R-F15) | The main app's selected-environment setting **defaults to `PRODUCTION`**: `EncryptedKisSecretStore.selectedEnvironment()` returns `PRODUCTION` when no value is stored and when the stored value is invalid, and `KisAuthRepository` initializes its selected-environment state to `PRODUCTION`. Not changed by this document. **MUST RESOLVE before the Alpha credential policy** (HD-C11). The 12-B2 probe does not read this setting (VIRTUAL gate, docs/162 §2) | `EncryptedKisSecretStore.kt`, `KisAuthRepository.kt` |
| Mutability of domain / operational tables | Insert-only DAOs (no `@Update`, no `UPDATE` / `DELETE` query): `executions`, `cash_ledger`, `trade_audit_logs`, `operational_events`. Updated in place: `orders` (`@Update`), `positions` (`@Update`), `strategy_runs` (status `UPDATE`), `forward_operations` (guarded status `UPDATE`) | `ExecutionDao.kt`, `CashLedgerDao.kt`, `TradeAuditLogDao.kt`, `OperationalEventDao.kt`, `OrderDao.kt`, `PositionDao.kt`, `StrategyRunDao.kt`, `ForwardOperationDao.kt` |
| Operation lifecycle events | `OperationalEventType`: `OPERATION_STARTED`, `OPERATION_FINISHED`, `MARKET_SYNC_RESULT`, `RUN_RESULT`, `CYCLE_STARTED`, `CYCLE_FINISHED`, `WORKER_SCHEDULE_CHANGED`; interrupted operations closed as `PROCESS_INTERRUPTED` with the missing `OPERATION_FINISHED` | `DomainCodes.kt`, `ForwardOperationLogService.kt` |
| Read-only guard | `KisReadOnlyGuard` substring **deny** rule (`/trading/` rejected; `/uapi/` without `/quotations/` rejected); not a positive allowlist; host and TR not checked (docs/161 §12) | `KisReadOnlyGuard.kt`, `KisReadOnlyInterceptor.kt` |
| Probe allowlist | The untracked debug-only 12-B2-A probe implements a positive host / path / TR allowlist and redaction (pending independent verification and Smoke; docs/162) | `app/src/debug/.../probe/intraday/ProbeAllowlist.kt`, `ProbeRedaction.kt` |
| Scheduler | DAILY Auto via one-time WorkManager requests per slot (`auto:<date>:0700:KST`) | `ForwardTestScheduler.kt`, `AutoScheduleSlot.kt` |
| Manifest | Main manifest declares only `INTERNET`; `allowBackup="false"` with backup / data-extraction rules. Library-merged permissions are not enumerated here (see §7) | `app/src/main/AndroidManifest.xml` |
| Build metadata | `applicationId com.mirunubi.bjstock`, `versionCode 1`, `versionName 0.1.0`, `buildConfig = false`, minify off. **No Git HEAD, APK hash, or signing identity is embedded or recorded at runtime** | `app/build.gradle.kts` |

### 2.3 Phase 11 B10 runtime evidence (2026-10-02, carried forward)

CURRENT REPOSITORY FACT (external observation, recorded in docs/162 §9a):

| Observation | Result |
| --- | --- |
| 07:00 scheduler timing | PASS (WorkManager fired in-process at 07:00:00) |
| Automatic worker start | PASS |
| Background provider access | FAIL — Android network policy `blocked=APP_BACKGROUND`; worker returned RETRY |
| Recovery | Only after a human foregrounded the app (UID moved to TOP ≈ 07:00:02.5); network unblocked ≈ 07:00:30.6; attempt succeeded ≈ 07:00:31.6 |
| Verdict | FAIL — UNATTENDED 07:00. Not solved. No claim that a foreground service solves this |

## 3. Core Purpose — Answers to the Ten Questions

| # | Question | Architectural answer (PROPOSED unless labelled) | Sections |
| --- | --- | --- | --- |
| 1 | Protect a real personal phone during Alpha | Least-privilege permission policy with a manifest-permission diff as build evidence; no unrelated data access; app-internal storage only; secrets in Keystore-protected storage; positive network allowlist; Fail-Closed automation; Safe Mode | §4, §6, §7, §25, §38 |
| 2 | Isolate secrets, market data, strategy state, future financial actions | Explicit trust boundaries; separate credential classes (VIRTUAL market data ≠ PRODUCTION market data ≠ REAL-TRADING); separate keys per purpose; diagnostic log ≠ audit; probe evidence isolated from business source of truth (docs/161 A-9) | §6, §8, §14 |
| 3 | Contain failures instead of cascading | Error taxonomy with explicit retry / automation / audit behaviour; component failure boundaries; crash-loop protection; recovery startup gate | §21, §26, §27, §29 |
| 4 | Distinguish crash causes without falsely declaring "hacking" | Next-start postmortem using `ApplicationExitInfo`, audit head, checkpoint, manifest, build / config identity; reason-code correlation; Crash ≠ Hack; escalation only with corroborating integrity evidence | §21, §22, §23 |
| 5 | Reveal modification, deletion, insertion, reordering, rollback, file replacement | Canonical events + SHA-256 hash chain (modification / deletion / insertion / reordering); segment rollover + manifest (file deletion / replacement / gaps); signed checkpoints (raises the bar); off-device anchor (rollback and full-history rewrite) | §9–§16 |
| 6 | Keep evidence useful after a crash before the final write | Existing write-ahead operation records (`OPERATION_STARTED` before effects, §8.1) and transactional appends; clean-shutdown marker; next-start `UNEXPECTED_TERMINATION` reconstruction; best-effort pre-crash diagnostic file kept separate from audit | §23, §24, §28 |
| 7 | Rotate and retain without infinite growth | File classes A–H with size / retention policy; segment rollover with hash continuity; HOT / WARM / COLD tiers; never silently delete unanchored authoritative audit; deletion proof | §34–§37 |
| 8 | Hourly / daily health for a Human administrator | Offline hourly security digest with digest chain and auditor self-health; daily summary; administrator status with drill-down | §17–§19, §33 |
| 9 | Export 6+ months of evidence for AI safely | Versioned export package with integrity report, DERIVED marking, secret / PII scan, Human-selected scope; read-time normalization instead of history rewrite | §39–§43 |
| 10 | Which protections before Alpha, which deferred | Alpha MUST / SHOULD / CAN WAIT list; probe-distortion review classifies controls BEFORE SMOKE / DEFER AFTER SMOKE | §47, §48 |

## 4. Security Philosophy — Architectural Invariants

PROPOSED ARCHITECTURE (invariants for all future S-gates; several restate APPROVED HUMAN REQUIREMENTS from docs/150 / docs/160 where noted).

| Invariant | Meaning |
| --- | --- |
| Least Privilege | Each component, credential, key, and permission grants only what its function requires. No permission, credential class, or key purpose is added "for later" |
| Positive Allowlist | Network hosts, paths, TR IDs, WebSocket endpoints, permissions, exported fields, and AI-export fields are enumerated. Anything not listed is rejected. A deny-list (such as the current `KisReadOnlyGuard`) is defence in depth, not the boundary (docs/161 A-5, APPROVED for the probe) |
| Fail-Closed | Missing, stale, unverifiable, or ambiguous inputs never become zero, a default, or a fabricated value; automatic operation stops rather than guesses (docs/160 §0.2, APPROVED) |
| No Silent Exception Swallowing | `catch (Exception) {}`, `runCatching { }` with a discarded result, `catch (Throwable)` without rethrow / classification, or any equivalent silent swallowing is **prohibited** (docs/150 §6 prohibited-catch list, APPROVED). The current `runIgnoringFailure` in `AppStartSequence` is a recorded gap to be closed in S1 |
| Append-Only Audit Semantics | No application code path updates or deletes an authoritative audit event. Corrections are new events that reference the corrected event. Purge happens only through the authorized deletion path with deletion proof (§37) |
| Tamper Evidence, not Tamper Prevention | The design detects and reports tampering; it does not claim to prevent a privileged attacker from modifying local files. Every cryptographic claim states its limitation |
| Explicit Trust Boundaries | Every data flow crossing a boundary (§6) is named, validated, and minimized |
| Secret / Data Minimization | Secrets never enter logs, audit, digests, anchors, exports, crash files, or documentation. Personal-phone data unrelated to BJStock is never collected |
| Safe Failure | A failure leaves state consistent (atomic) and automation stopped or degraded, never half-applied |
| Deterministic Recovery | Recovery decisions derive from persisted evidence by fixed rules (§29); the same evidence yields the same NORMAL / DEGRADED / SAFE_MODE result |
| Independent Verification | Integrity is verified by a component that does not trust the runtime's own "PASS" flags (§50) |
| Evidence Provenance | Every authoritative record binds to build, configuration, strategy, and cadence identities (§30–§32) |
| No Real Trading Path Before a Separate Human Gate | No code, credential, endpoint, or TR enabling real orders exists until separate gates (§49) pass. KIS `/trading/` stays forbidden |

Mandatory error flow (PROPOSED; refines docs/150 §5–§6):

```
error observed
  → classify   (taxonomy class §21 + docs/150 ErrorCategory / ErrorSeverity + reason code §45)
  → contain    (failure boundary §27; stop the smallest unit that restores safety)
  → record     (authoritative audit event; diagnostic detail only in the diagnostic log)
  → clean up   (release resources, close streams, finish operations with terminal status)
  → decide     (retry per RetryPolicy, or stop: SKIP / BLOCK_RUN / SESSION_FATAL / SAFE_MODE)
  → preserve   (keep evidence; never delete or overwrite to "recover")
```

## 5. Threat Model

PROPOSED ARCHITECTURE. "Detect" means the design produces evidence; it does not mean prevention. Root / hook / debug signals are **suspicion indicators only**; no detection is claimed to be foolproof.

| ID | Threat | Example | Primary controls | Detection / evidence | Residual risk |
| --- | --- | --- | --- | --- | --- |
| A | Accidental developer defect | Null dereference, wrong state transition, swallowed exception | Structured errors, prohibited-catch rule, invariants, atomic transactions, tests | `APP_FATAL` / `SESSION_FATAL` audit events; crash diagnostics | Logic errors that pass invariants |
| B | Malformed provider data | Missing field, wrong type, out-of-range price, schema change | Strict parser; no fabricated observation; MISSING never zero (docs/160 §0.2) | `PROVIDER_MALFORMED`, `PARSER_SCHEMA_MISMATCH` | Plausible-but-wrong values |
| C | Network interception / manipulation | Hostile Wi-Fi, TLS interception, DNS spoof | TLS only for provider and anchor (bounded Smoke cleartext exception per docs/162 only with Human acknowledgement); platform trust store; candidate pinning is a FUTURE HUMAN DECISION | TLS failures recorded as `NETWORK_TLS_FAILURE`; unexpected host attempts | User-installed CA on a compromised device |
| D | Unauthorized endpoint / TR attempt | Code path tries `/trading/` or an unlisted TR | Positive allowlist; `/trading/` deny retained as second layer | `UNAUTHORIZED_ENDPOINT_ATTEMPT`, `UNAUTHORIZED_TR_ATTEMPT` (always audited; terminal for the session) | None for listed paths; allowlist mistakes |
| E | App file / database modification | Edited `bjstock.db` rows on a rooted device or via backup restore | Hash chain over audit; row commitments for authoritative domain rows (§9.4); recovery gate | `AUDIT_CHAIN_MISMATCH`, DB invariant failure | Business tables not covered by commitments; full rewrite without anchor |
| F | Audit / log modification or deletion | Remove an embarrassing event, truncate a segment | Hash chain, segment manifest, signed checkpoints, digest chain, anchor | `AUDIT_CHAIN_MISMATCH`, `AUDIT_SEQUENCE_GAP`, `AUDIT_MANIFEST_MISMATCH` | Truncation of the unanchored tail after the last checkpoint |
| G | Rollback to older files / config / build | Restore an older DB copy, older APK, older config | Monotonic sequence in checkpoints; local high-water mark; anchor comparison; build / config provenance | `AUDIT_ROLLBACK_SUSPECTED`, `BUILD_ID_MISMATCH`, `CONFIG_HASH_MISMATCH` | Consistent rollback of all local state before the first anchor. A local high-water mark detects only partial / inconsistent restores; it does **not** survive a full, consistent restore of all app storage (S0-R-F09) |
| H | Process crash | Uncaught exception | Failure boundaries; best-effort pre-crash capture; next-start postmortem | `UNEXPECTED_TERMINATION` with `ApplicationExitInfo` | Loss of in-memory, uncommitted state |
| I | Repeated crash / crash loop | Crash on every start | Crash-loop counter; backoff; Safe Mode | `CRASH_LOOP_DETECTED`, `REPEATED_CRASH_PATTERN` | Threshold tuning (OPEN) |
| J | Android process kill / OOM / MemoryLimiter | Low-memory kill, Android 17 `REASON_OTHER` "MemoryLimiter:AnonSwap" (docs/161 §15.3), user Task Manager stop | Deterministic recovery; no retroactive trades (docs/160 §29–§30) | `ApplicationExitInfo` reason / description recorded; classified as OS / resource, not security | Missed slots (MISSED, never reconstructed) |
| K | Storage full / corruption | Disk full, SQLite corruption | Storage pressure levels; bounded files; integrity check on open | `STORAGE_PRESSURE`, `STORAGE_FAILED` → `SESSION_FATAL` / `SAFE_MODE` | Corruption of unanchored tail |
| L | Clock / timezone manipulation | User or attacker changes wall clock or timezone | Record both wall and monotonic time; boot identity; clock-jump detection; KST derivation from UTC | `UNEXPECTED_CLOCK_JUMP`, `TIMEZONE_CHANGED` | Slow drift below threshold (threshold OPEN) |
| M | Permission changes | Notification permission revoked; battery optimization changed | Permission state snapshot at session start and on change | `PERMISSION_STATE_CHANGED`, `UNEXPECTED_PERMISSION_CHANGE` (only when inconsistent with user action evidence) | OEM-specific states not observable |
| N | APK replacement / unexpected build | Different signing certificate or unknown build | Runtime-observed build identity (§30); signing-identity comparison at start; out-of-band APK hash comparison by the independent verifier | Runtime: `BUILD_ID_MISMATCH`, `SIGNING_IDENTITY_CHANGED`. Verifier / out-of-band only: `APK_HASH_MISMATCH` (the app never self-approves its own APK hash) | Android itself blocks updates with a different signing key; a reinstall resets app data |
| O | Configuration tampering | Edited strategy, risk, cadence, or allowlist config | Canonical config hashes bound to Run snapshots and checkpoints | `CONFIG_HASH_MISMATCH` | Tampering before first hash recorded |
| P | Compromised app process | Injected code inside BJStock | Least privilege; no real-trading path; Keystore keys non-exportable; anchor | Indirect only (anomalies, anchor divergence) | **Can sign malicious data with the Keystore key and write a consistent chain going forward** (§15) |
| Q | Lost / stolen / unlocked phone | Third party opens the app | No real-trading capability; secrets not displayed; candidate re-authentication for sensitive admin actions (FUTURE HUMAN DECISION) | Admin actions audited | Paper-trading data and VIRTUAL credentials exposed to an unlocked-device holder |
| R | Rooted / hooked / debugged device indicators | `su` binary, debugger attached, hook framework | Passive indicator collection only (no enforcement before Smoke, §48) | `DEVICE_RISK_INDICATOR` with indicator codes; `DEVICE_RISK` class | Indicators are bypassable; absence proves nothing |

## 6. Trust Boundaries

PROPOSED ARCHITECTURE (conceptual).

```
 ┌──────────────────────────── Human Administrator ─────────────────────────────┐
 │  approves gates, acknowledges Safe Mode, chooses export scope, holds anchor  │
 └───────────────▲──────────────────────────────────────────────▲───────────────┘
                 │ status UI / acknowledgements                 │ anchor records, verifier reports
 ┌───────────────┴────────── Android OS (process, permissions, network policy, storage) ─────────┐
 │  ┌──────────────── BJStock App process ─────────────────┐     ┌──── Android Keystore ────┐     │
 │  │ UI │ Scheduler │ Strategy │ Risk Gate │ Paper Trading │◄───►│ credential encryption key│     │
 │  │ Market Data │ Transport │ Parser │ Audit │ Storage    │     │ audit signing key (prop.)│     │
 │  └────────┬──────────────────────────────────┬──────────┘     └──────────────────────────┘     │
 └───────────┼──────────────────────────────────┼─────────────────────────────────────────────────┘
             │ TLS, positive allowlist          │ TLS, minimal anchor payload (future)
   ┌─────────▼──────────┐              ┌────────▼────────────────────┐    ┌──────────────────────────┐
   │ Market Data        │              │ Future Audit Anchor Service │    │ Future Trading Backend   │
   │ Provider (KIS)     │              │ (append-only, off-device)   │    │ NOT AUTHORIZED (§49)     │
   └────────────────────┘              └─────────────────────────────┘    └──────────────────────────┘
```

| Boundary | What crosses | Trust assumption | Rule |
| --- | --- | --- | --- |
| Android OS ↔ App | Process lifecycle, permissions, network policy, files | OS is trusted to enforce sandboxing on a non-rooted device; OS may kill the process or block network at any time (B10 evidence) | App never assumes it will run or have network; recovery is deterministic |
| App ↔ Keystore | Encrypt / decrypt / sign requests; never raw key material | Keystore keys are non-exportable; a compromised process can still *use* them | Separate keys per purpose (§14) |
| App ↔ Market Data Provider | Allowlisted quotation requests; quotation responses | Provider responses are untrusted input | Strict parsing; MISSING never zero; no `/trading/` |
| App ↔ Future Anchor Service | Minimal digest / checkpoint payload | Service is trusted only for append-only storage and timestamps, not for secrecy | No secrets, no market data, no PII (§16) |
| App ↔ Future Trading Backend | Nothing | Not authorized | Separate future gates (§49) |
| App ↔ Human Administrator | Status, reasons, acknowledgements, exports | Human is the decision authority; UI must not hide integrity failures | Admin actions audited |

Credential classes (APPROVED HUMAN REQUIREMENT for the current probe: VIRTUAL only, docs/161 A-3; separation of classes is PROPOSED ARCHITECTURE):

| Class | Current status | Allowed use | Rule |
| --- | --- | --- | --- |
| VIRTUAL market-data credential | Used by the current app and the 12-B2 probe | Read-only quotation REST / WebSocket within the allowlist | Never logged; held in Keystore-protected storage or memory |
| Future PRODUCTION market-data credential | Not authorized for the probe (A-3) | Read-only quotation only, if later approved | Belongs to a real brokerage account, so the read-only boundary becomes stricter, not looser (docs/161 §12) |
| Future REAL-TRADING credential | Does not exist; not authorized | None | **Never implicitly equivalent to any market-data credential.** Separate custody, separate key, separate gate (§49) |

Hard rules: a REAL-TRADING credential is never derived from, stored with, or substituted for a market-data credential. KIS `/trading/` remains prohibited for every credential class.

## 7. Personal-Phone Least-Privilege Policy

PROPOSED ARCHITECTURE for Alpha.

- BJStock does not request: contacts, SMS, call logs, photos / media library, broad file access (`MANAGE_EXTERNAL_STORAGE`, legacy external storage), accessibility services, overlay (`SYSTEM_ALERT_WINDOW`), location, camera, microphone, phone state, account access, or device-admin rights, unless a later explicit requirement and a Human security review justify it.
- Allowed today (CURRENT REPOSITORY FACT): `INTERNET` in the main manifest. The debug-only probe manifest adds the foreground-service and notification permissions approved for the probe (docs/161 A-2, A-10; docs/162); those must never reach the release manifest (verified for the probe in docs/162).
- Storage: app-internal storage only (`filesDir` / `noBackupFilesDir` / Room). No evidence on shared or external storage. AI export leaves the app only through an explicit user action (§43).
- Backup: `allowBackup="false"` is retained (CURRENT REPOSITORY FACT). Consequence: uninstall or clear-data destroys local evidence; this is why export and anchoring matter (§16, §40).
- **Manifest permission diff as build evidence:** each release-candidate build records the **merged** manifest permission set (including library-merged permissions such as those contributed by WorkManager) and its diff against the previous approved build. An unexpected new permission blocks release until a Human security review approves it (`UNEXPECTED_PERMISSION_IN_BUILD`, build-time finding).
- Runtime permission state (notification permission, battery-optimization exemption, exact-alarm grant if ever adopted) is snapshotted into audit at session start and on change; BJStock never silently changes system settings (docs/161 A-10).

## 8. Log vs Audit Separation

PROPOSED ARCHITECTURE. Extends docs/150 §9 (three log layers) with an explicit integrity layer.

| Property | A. Diagnostic Log | B. Security / Operational Audit |
| --- | --- | --- |
| Purpose | Developer troubleshooting | Authoritative evidence trail |
| Content | Sanitized messages; may contain redacted stack traces | Canonical structured events (§9); compact references to diagnostics only |
| Ordering | Best effort | Gapless `audit_sequence` |
| Mutability | Rotatable ring; overwritten | Append-only semantics; no `UPDATE` / `DELETE` application API |
| Integrity | None required | Hash chained (§11), segment manifest (§13), signed checkpoints (§15), anchored (§16) |
| Retention | Short (§35 HOT) | Long (≥ the 6-month study period; §35) |
| Use | Debugging | Recovery decisions, security classification, Human review, AI analysis |
| Verification | Not verifiable | Independently verifiable (§50) |

**Normal log files (logcat, diagnostic files, crash files) are never authoritative audit.** No recovery, classification, financial, or security decision may rely on a diagnostic log alone.

Relationship to existing tables (consistent with docs/160 §0.2, which asks future design to extend `trade_audit_logs` and `operational_events` rather than invent a parallel audit subsystem):

- `trade_audit_logs` remains the domain WHY record; `forward_operations` / `operational_events` remain the operational HOW record; `api_error_logs` remains the WHAT record (docs/150 §9).
- The integrity / security chain adds **integrity and security semantics** over those records rather than duplicating their content (§8.1).
- Storage topology is **HD-A1, HUMAN APPROVED: HYBRID** (§46.1). The live integrity-chain state lives inside `bjstock.db`; sealed long-term audit segments are file-based immutable-style segments (§12); existing domain / operational tables remain the only authoritative source of business and operational truth (§8.1). A separate store as the live primary chain is NOT SELECTED. The exact chain-table schema and the physical segment path are not frozen by this document. Schema ownership for intraday audit remains with 12-C / 12-K (docs/160 §0.2); S1 owns only the integrity layer.

Conceptual structure (HD-A1):

```
existing domain / operational tables          (authoritative)
        │
        ▼
same-transaction row commitment where possible (§9.4.4)
        │
        ▼
integrity chain in bjstock.db                 (live chain state, single logical head writer)
        │
        ▼
signed checkpoint / chain head                (§15)
        │
        ▼
sealed segment file                           (app-private storage during Alpha, §12)
        │
        ▼
manifest / digest                             (§13, §17)
        │
        ▼
future external anchor                        (§16)
```

### 8.1 Authority Invariant — One Domain / Operational Truth (S0-R-F01, FROZEN ARCHITECTURE INVARIANT)

This invariant is frozen for all S-gates. The storage topology that implements it is HD-A1 (HUMAN APPROVED: HYBRID, §46.1); the integrity chain never becomes a second business ledger.

**Existing domain and operational records remain authoritative.** Authoritative homes include, at least: `forward_operations`, `operational_events`, `trade_audit_logs`, `orders`, `executions`, `cash_ledger`, `positions`, `strategy_runs` (and their future 12-C / 12-K intraday successors).

The integrity / security chain **must not become a competing business or operational ledger**:

| Fact | Authoritative home | Chain role |
| --- | --- | --- |
| Operation start / outcome | `forward_operations` + `operational_events` (`OPERATION_STARTED`, `OPERATION_FINISHED`, `PROCESS_INTERRUPTED`) | Reference by `operation_id` / `event_key` and row commitment only |
| Slot / cycle / Run outcome | `operational_events` (`CYCLE_*`, `RUN_RESULT`), `forward_test_cycles`, `strategy_runs`, future intraday slot records | Reference + commitment only |
| Financial outcome (orders, fills, cash, positions) | `orders`, `executions`, `cash_ledger`, `positions` | Reference + commitment only |
| Trade decision reasons | `trade_audit_logs` | Reference + commitment only |
| Integrity lifecycle (GENESIS, rollover, sealing, purge proof) | Integrity chain | **Authoritative** |
| Security events (suspicion, fatal, device-risk indicators, unauthorized endpoint / TR attempts) | Integrity chain | **Authoritative** |
| Checkpoint / manifest / digest / anchor lifecycle | Integrity chain | **Authoritative** |
| Crash / recovery / Safe Mode / security state | Integrity chain | **Authoritative** |
| Facts with no current authoritative domain home (for example build identity observations, permission-state changes, clock-jump evidence) | Integrity chain | **Authoritative** until a domain home is created; then reference-only |

Consequences:

- The chain never defines its own operation-intent or operation-finished concept. The earlier draft names `OPERATION_INTENT` / `OPERATION_FINISHED` as chain events are **removed**; the existing `OPERATION_STARTED` / `OPERATION_FINISHED` operational events stay the only operation lifecycle record, and the chain commits to those rows.
- If a chain reference and an authoritative row disagree, the disagreement is an integrity finding (`COMMITMENT_MISMATCH`, §9.4); the chain never "wins" as a business value, and no business decision reads business facts from the chain.
- Recovery and analytics read business facts from the authoritative tables and use the chain only to verify them.

## 9. Audit Event Canonical Schema

PROPOSED ARCHITECTURE. Schema identifier: `bjstock.audit.event`, version `1` (name and version FUTURE HUMAN DECISION after review).

### 9.1 Fields

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `schema_version` | integer | yes | Audit event schema version |
| `audit_sequence` | integer ≥ 0 | yes | Gapless, strictly increasing per installation chain; `0` = GENESIS |
| `event_id` | string (UUID) | yes | Globally unique event id |
| `installation_id` | string | yes | Random pseudonymous UUID generated at audit GENESIS (HD-A5, §38); not a hardware or personal identifier |
| `key_epoch` | integer | yes | Audit signing key epoch (§14) |
| `session_id` | string / null | yes | **BJStock operational session** id (§23): for example an explicit user-started probe / operation session, an automation operation session, or a strategy runtime session. **Not** synonymous with the Android process lifetime; process identity is carried separately by `process_ref` |
| `run_id` | string / null | yes | Strategy Run reference when applicable |
| `slot_id` | string / null | yes | Deterministic slot id when applicable (for example `auto:<date>:0700:KST`) |
| `event_type` | string (registry) | yes | From the event-type registry (§45) |
| `severity` | string (registry) | yes | docs/150 `ErrorSeverity` value |
| `taxonomy_class` | string (registry) | yes | §21 class |
| `component` | string (registry) | yes | §27 component |
| `actor` | string (registry) | yes | `SYSTEM`, `SCHEDULER`, `USER`, `RECOVERY`, `VERIFIER` |
| `wall_time` | string | yes | UTC instant, canonical format (§10) |
| `monotonic_time_ns` | integer | yes | Elapsed-since-boot time in nanoseconds |
| `boot_identity` | string / null | yes | Boot identity used to interpret `monotonic_time_ns` (HD-A5): primary logical evidence is the Android boot count where reliably available without broad permissions; a kernel / platform boot identifier may be added as optional supplementary evidence and is never a hard dependency. Exact encoding is an implementation detail |
| `build_id` | string | yes | Stable build identity reference (§30) |
| `config_hash` | string / null | yes | Active runtime configuration hash (§31) |
| `strategy_ref` | string / null | yes | Strategy version + strategy config hash when applicable |
| `provider_env` | string / null | yes | For example `KIS/VIRTUAL`; never a credential value |
| `process_ref` | object / null | yes | Process correlation where available (HD-A5): `pid`, `process_start_time` (process start timestamp evidence), `boot_identity`. Together with `installation_id` and `session_id` (operational session) this forms the HD-A5 process evidence tuple. A process is not a session (§23) |
| `operation_id` | integer / null | yes | Correlation to the authoritative `forward_operations` row (S0-R-F12) |
| `referenced_event_key` | string / null | yes | Stable key of the referenced authoritative row (for example `trade_audit_logs.event_key`, `operational_events` event key) |
| `source_row_ref` | object / null | yes | `{table, row_identity, row_version}` of the referenced authoritative row where applicable (§9.4) |
| `result` | string (registry) | yes | `SUCCESS`, `FAILURE`, `SKIPPED`, `BLOCKED`, `INTERRUPTED`, `INFO` |
| `reason_code` | string (registry) / null | yes | Central reason code (§45); never free text |
| `security_classification` | string (registry) | yes | `NONE`, `SUSPECTED`, `FATAL`, `DEVICE_RISK` |
| `security_reason_codes` | array of string | yes | Empty when none (§22) |
| `details` | object / null | yes | Optional safe structured details; keys from an allowlisted per-event-type schema; values pass `SafeLogText`-style redaction |
| `previous_event_hash` | string | yes | Hash of event `audit_sequence − 1`; GENESIS uses 64 `0` characters |
| `event_hash` | string | yes | §11 |

### 9.2 Never included

Access token, approval key, app key, app secret, authorization header, password, private key material, cookies, account number, full request / response bodies, raw WebSocket frames, or any value from credential preferences. Secret *references* use safe metadata only (for example `credential_class=VIRTUAL`, `credential_fingerprint_version=1`, where a fingerprint, if ever adopted, is a keyed hash reviewed under HD-B8, never a plain hash of a low-entropy secret).

### 9.3 Event families (illustrative, PROPOSED)

| Family | Examples |
| --- | --- |
| Chain lifecycle | `AUDIT_GENESIS`, `AUDIT_FILE_ROLLOVER`, `AUDIT_CHECKPOINT_SIGNED`, `AUDIT_KEY_EPOCH_STARTED`, `AUDIT_RANGE_PURGED` |
| Session lifecycle | `SESSION_STARTED`, `SESSION_CLOSED_CLEANLY`, `UNEXPECTED_TERMINATION`, `RECOVERY_GATE_RESULT` |
| Security | `SECURITY_SUSPECTED_RAISED`, `SECURITY_FATAL_RAISED`, `DEVICE_RISK_INDICATOR`, `UNAUTHORIZED_ENDPOINT_ATTEMPT` |
| Safe Mode | `SAFE_MODE_ENTERED`, `SAFE_MODE_ACKNOWLEDGED`, `SAFE_MODE_EXITED` |
| Digest / anchor | `DIGEST_SLOT_STARTED`, `DIGEST_GENERATED`, `DIGEST_GENERATION_FAILED`, `AUDIT_DIGEST_MISSED`, `ANCHOR_UPLOAD_FAILED`, `ANCHOR_CONFIRMED` |
| Provenance | `BUILD_IDENTITY_OBSERVED`, `CONFIG_VERSION_ACTIVATED`, `PERMISSION_STATE_CHANGED` |
| Row commitment | `ROW_COMMITTED` (immutable rows), `ROW_TRANSITION_COMMITTED` (mutable rows), `COMMITMENT_COVERAGE_STARTED`, `COMMITMENT_RECONCILED` (§9.4) |

There is deliberately **no operations family** (S0-R-F01): operation start / outcome is owned by the existing `OPERATION_STARTED` / `OPERATION_FINISHED` operational events and `forward_operations` rows (§8.1); the chain only commits to those rows.

### 9.4 Row commitments (coverage HUMAN APPROVED under HD-A2; not implemented)

Row commitments cover authoritative domain / operational rows without copying their content into the chain and without becoming a second ledger (§8.1). Commitment coverage and phase ownership are **HD-A2, HUMAN APPROVED** (§9.4.7, §46.1).

#### 9.4.1 Row classes (S0-R-F03)

Mutability is a property of the authoritative table, verified against the repository (§2.2) and re-verified at S1 design time against the then-current schema.

| Class | Meaning | Tables (current repository verification) |
| --- | --- | --- |
| IMMUTABLE SOURCE / EVENT ROWS | Inserted once; never updated or deleted by application code | `executions`, `cash_ledger`, `trade_audit_logs`, `operational_events` (insert-only DAOs) |
| MUTABLE STATE / PROJECTION ROWS | Updated in place as state changes | `orders` (status / fill state), `positions` (projection of executions), `forward_operations` (`RUNNING → terminal`), `strategy_runs` (status) |

Mutable projection data is **never presented as inherently immutable**. Its integrity guarantee is "every committed transition is evidenced and the current row equals the latest valid transition", not "the row never changes".

#### 9.4.2 Commitment shapes

| Class | Commitment | Fields |
| --- | --- | --- |
| Immutable | One `ROW_COMMITTED` per row, at insert | `table`, `row_identity` (stable key, §9.4.3), `row_schema_version`, `row_hash` |
| Mutable | One `ROW_TRANSITION_COMMITTED` **per transition / version**, at each insert or update | `table`, `row_identity`, `row_version` (strictly increasing per row, starting at 1 for the insert), `previous_commitment_hash` (hash of the previous transition commitment for the same row; GENESIS-like zeros for version 1), `current_row_hash`, `transition_reason` (registry code, for example `ORDER_FILLED`, `POSITION_UPDATED_BY_EXECUTION`, `OPERATION_FINISHED`), `source_correlation` (`operation_id`, `referenced_event_key`, or the source execution id that caused the transition) |

Rules:

- The current mutable row must match the `current_row_hash` of its **latest valid** transition commitment; a mismatch is `COMMITMENT_MISMATCH`.
- A gap or fork in `row_version` / `previous_commitment_hash` for a row is `COMMITMENT_MISMATCH`.
- An immutable row whose recomputed hash differs from its `ROW_COMMITTED` hash is `COMMITMENT_MISMATCH`.

#### 9.4.3 Row canonicalization (S0-R-F08, input to HD-A3)

Rows are hashed from an explicit, versioned **per-table canonical column mapping**, never from an ORM object's arbitrary or default serialization (for example `toString()`, reflection order, or a JSON library's default output). Under HD-A3 (HUMAN APPROVED, §46.1), each committed table's mapping must define the following before S1 coding / test-vector approval:

| Element | Requirement |
| --- | --- |
| Authoritative columns | Explicit ordered list of columns included in `row_hash` |
| Excluded volatile columns | Explicit list (for example cached display fields, local-only UI flags) with the reason for exclusion |
| Stable key | The `row_identity` used across versions (primary key and / or natural key such as `event_key`) |
| Enum encoding | Enum **name** string as stored (never ordinal) |
| Integer encoding | Canonical integer (§10) |
| Decimal scale | Value as a string at the scale fixed in the decimal-scale registry (§10) |
| `Instant` encoding | §10 timestamp format, with the declared truncation rule |
| `LocalDate` encoding | `yyyy-MM-dd` (ISO-8601, no zone) |
| Nullable semantics | Explicit `null`; never omitted; empty string is not null |
| Schema / version identity | `row_schema_version` per table mapping; Room schema version recorded alongside |

#### 9.4.4 Atomicity (S0-R-F02, FROZEN ARCHITECTURE RULE)

- **Preferred:** the domain write and its integrity commitment occur in the **same SQLite transaction** where technically possible (docs/150 §11 same-transaction audit atomicity).
- **If same-transaction commitment is impossible** for a given write path (for example a technically non-atomic operation, a boundary between the live chain in `bjstock.db` and sealed segment files, or a future component that cannot share the Room transaction), a deterministic reconciliation is required, keyed by a stable correlation key (`table` + `row_identity` + `row_version`, or `operation_id` / `event_key`). Under HD-A1 (HYBRID) the live integrity chain is inside `bjstock.db`; an external live primary integrity store is not an approved option. Reconciliation appends the missing commitment as `COMMITMENT_RECONCILED` with the reconciliation reason and never rewrites the domain row.
- **`COMMITMENT_MISSING`** is a detectable recovery condition: a row (or row version) inside the coverage window of a commitment-covered table has no commitment. It is first a technical / recovery finding (RECOVERABLE or DEGRADED per §21), resolved by deterministic reconciliation where the row's provenance can be established.
- A historical or technical commitment gap is **not automatically `SECURITY_FATAL`**. Escalation follows §22 only with corroborating evidence (for example the gap lies inside a signed / anchored range, or coincides with chain or manifest failures).

#### 9.4.5 Coverage boundary

Frozen by HD-A2 (HUMAN APPROVED):

- Every commitment-covered table records a **`commitment_coverage_start`**: the first `row_identity` / `row_version` (and the chain sequence) from which commitments are required. It is recorded once per table by `COMMITMENT_COVERAGE_STARTED`.
- Rows predating coverage are **never** falsely classified as tampered. They are classified **`UNCOMMITTED_LEGACY`** in verification and exports.
- An optional one-time baseline commitment / snapshot (hash over the canonical legacy row set) may be designed later; it is not designed or selected here. Such a baseline would prove only "the rows looked like this when coverage started", not earlier history.
- Historical domain rows are never rewritten to create or repair coverage.

#### 9.4.6 Completeness checks

For each table declared commitment-covered, the recovery gate (§29) and the verifier (§50) check completeness over the coverage window:

- every row / row version has exactly one commitment;
- no commitment references a row that does not exist (unless covered by an authorized purge tombstone, §37);
- mutable rows equal their latest valid transition commitment.

Failures are reported as `COMMITMENT_MISSING` or `COMMITMENT_MISMATCH` with the table and row identity.

#### 9.4.7 Approved Coverage and Phase Ownership (HD-A2, HUMAN APPROVED)

| Row class | Tables | Commitment |
| --- | --- | --- |
| IMMUTABLE / EVENT-LIKE | `executions`, `cash_ledger`, `trade_audit_logs`, `operational_events` | One canonical commitment per authoritative row / version as applicable (`ROW_COMMITTED`) |
| MUTABLE / TRANSITION-BASED | `orders`, `positions`, `forward_operations`, `strategy_runs` | One commitment per transition / version (`ROW_TRANSITION_COMMITTED`) with, conceptually, `row_identity`, `row_version`, `previous_commitment_hash`, `current_row_hash`, `transition_reason`, `source_correlation` (§9.4.2) |

- The current mutable row must match its latest valid commitment.
- No mutable projection may be represented as permanently immutable.

| Phase | Owns (architecture assignment only; neither phase is authorized) |
| --- | --- |
| 12-S1 | Canonical row-commitment framework; immutable / event-like target commitments; mutable transition commitment framework; completeness verification foundation |
| 12-S2 | Checkpoint / signing integration; sealed segment rollover; manifest; retention / deletion proof; commitment verification across sealed ranges |

Future intraday tables owned by 12-C / 12-K (docs/160 §0.2) are outside this approval; their coverage is decided when those tables are designed.

## 10. Canonical Serialization

**HD-A3, HUMAN APPROVED** (§46.1): canonical serialization standard is **RFC 8785 JCS-compatible deterministic JSON semantics**; hash is **SHA-256**. Hash input must be unambiguous. Not implemented. Rows below that name exact literals or values are the design for that approved standard; items marked "not frozen" remain implementation details to freeze before coding / test-vector approval.

Approved requirements (HD-A3): UTF-8; explicit schema version; deterministic field order through canonical serialization; exact null / missing semantics; fixed timestamp representation; fixed decimal-scale registry; fixed enum mapping; per-table canonical column mapping (§9.4.3); no arbitrary ORM object serialization; no locale-dependent formatting; no silent Unicode normalization altering source meaning; explicit domain separation per hash class.

| Rule | Design |
| --- | --- |
| Encoding | Canonical JSON with RFC 8785 JCS-compatible semantics, further restricted below. **Library selection: not frozen.** Prefer a standards-compliant JCS implementation over handwritten canonicalization, subject to Android compatibility and independent test-vector verification |
| Character set | UTF-8, no BOM. Stored content is **not** silently normalized (no NFC / NFKC rewrite) before hashing (S0-R-F08): the hash covers the exact defined representation. Where a field's schema requires a restricted form (for example ASCII registry codes, ids), input that violates it is **rejected at write time**, never rewritten. Unpaired surrogates are rejected |
| Field order | Lexicographic order of UTF-16 code units of member names (JCS); nested objects likewise |
| Field presence | Every schema field is always present; absent values are explicit `null`. Optional `details` keys follow the per-event-type schema; unknown keys rejected at write time |
| Numbers | Integers only, within ±(2^53 − 1). Decimal quantities (prices, ratios, money) are strings at the fixed scale assigned by the **decimal-scale registry** (below). No floating point in hash input |
| Booleans | `true` / `false` |
| Timestamps | UTC, `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (exactly millisecond precision, `Z` suffix). Local-time (KST) values are derived for display, never hashed. **Truncation rule:** an `Instant` with sub-millisecond precision is **truncated** (toward the past) to milliseconds before hashing, and the stored authoritative value must equal the truncated value, so recomputation is stable. Where sub-millisecond ordering matters, `monotonic_time_ns` carries it |
| `LocalDate` | `yyyy-MM-dd` |
| Monotonic time | Integer nanoseconds |
| Hashes | Lowercase hexadecimal, 64 characters |
| Locale | Locale-independent formatting everywhere (no `String.format` with default locale, no grouping separators) |
| Whitespace | No insignificant whitespace; no pretty-printing |
| Domain separation | Hash input is prefixed with an ASCII domain tag, for example `BJSTOCK-AUDIT-EVENT-V1\n`, so event, segment, manifest, digest, and checkpoint hashes cannot collide across types |

Hash input for an event: `domain_tag ‖ JCS(event with the event_hash member removed)`. Because `previous_event_hash` is a member of the canonical event, the previous hash is bound into the input (§11).

Domain-tag registry. Distinct hash domains are **HUMAN APPROVED under HD-A3**; each hash class has exactly one tag, and a tag is never reused across classes or versions. The approved minimum domain set is AUDIT_EVENT, ROW_COMMITMENT, AUDIT_SEGMENT, MANIFEST, CHECKPOINT, HOURLY_DIGEST, DAILY_DIGEST, BUILD_ID, CONFIG, STRATEGY, AI_EXPORT. **Exact literal tag strings are not frozen**: they are an implementation detail to freeze before coding / test-vector approval. The literals below are candidates only:

| Approved hash domain (HD-A3) | Hash class | Candidate literal tag (not frozen) |
| --- | --- | --- |
| AUDIT_EVENT | Audit event | `BJSTOCK-AUDIT-EVENT-V1\n` |
| ROW_COMMITMENT | Row commitment (immutable row) | `BJSTOCK-ROW-V1\n` |
| ROW_COMMITMENT | Row transition commitment (mutable row; distinct tag within the domain) | `BJSTOCK-ROW-TRANSITION-V1\n` |
| AUDIT_SEGMENT | Segment file | `BJSTOCK-AUDIT-SEGMENT-V1\n` |
| MANIFEST | Manifest | `BJSTOCK-AUDIT-MANIFEST-V1\n` |
| CHECKPOINT | Checkpoint (signature input) | `BJSTOCK-AUDIT-CHECKPOINT-V1\n` |
| HOURLY_DIGEST | Hourly digest | `BJSTOCK-DIGEST-HOURLY-V1\n` |
| DAILY_DIGEST | Daily digest | `BJSTOCK-DIGEST-DAILY-V1\n` |
| BUILD_ID | Build identity | `BJSTOCK-BUILD-ID-V1\n` |
| CONFIG | Configuration hash | `BJSTOCK-CONFIG-V1\n` |
| STRATEGY | Strategy version / strategy configuration hash (§31) | `BJSTOCK-STRATEGY-V1\n` |
| AI_EXPORT | AI export manifest / package (§40) | `BJSTOCK-AI-EXPORT-V1\n` |
| (additional, beyond the approved minimum) | Anchor payload | `BJSTOCK-ANCHOR-V1\n` |

Decimal-scale registry (a fixed registry is HUMAN APPROVED under HD-A3; the values are an implementation detail to freeze before coding / test-vector approval): one entry per decimal quantity kind, for example KRW price (scale 0 for KRX stock prices in KRW), quantity (scale 0), money amount (scale 0 KRW), rates / ratios (fixed scale such as 6), percentages (fixed scale). A value that cannot be represented exactly at its registered scale is rejected, never rounded silently.

Test-vector requirement (HD-A3, HUMAN APPROVED). Before any S1 implementation can be accepted, the Android implementation **and** the future independent verifier must produce **identical canonical bytes and hashes** from the same published test vectors. The vectors must include at least: `null`; empty string; Unicode; enum; negative integer; maximum allowed integer; decimal fixed-scale value; timestamp; nullable field; ordering-sensitive structure. No test vector is created by this document.

## 11. Event Hash Chain

PROPOSED ARCHITECTURE.

```
event_hash(N) = SHA-256( "BJSTOCK-AUDIT-EVENT-V1\n" ‖ JCS(event(N) without event_hash) )
where event(N).previous_event_hash = event_hash(N − 1)
and   event(0) = AUDIT_GENESIS with previous_event_hash = "000…000" (64 zeros)
```

- **GENESIS:** `audit_sequence = 0`, `event_type = AUDIT_GENESIS`; generates and records the random pseudonymous `installation_id` (HD-A5), and records `key_epoch`, schema version, `build_id`, and creation time. A second GENESIS for the same `installation_id` is itself an integrity finding.
- **Sequence continuity:** `audit_sequence(N) = audit_sequence(N − 1) + 1`, no gaps, no duplicates. Appends are serialized by a single writer inside a database transaction.
- **Previous-hash continuity:** each event's `previous_event_hash` equals the recomputed hash of its predecessor.

| Attack | How it is detected |
| --- | --- |
| Modification of event N | Recomputed `event_hash(N)` differs from the stored value and from `event(N+1).previous_event_hash` |
| Deletion of event N | Sequence gap and broken `previous_event_hash` link |
| Insertion | Sequence duplicate or broken links on both sides |
| Reordering | Sequence order and hash links disagree |

**Limitation (explicit):** a hash chain alone does **not** defeat an attacker who can rewrite the entire history (or the whole tail after a point) and recompute every hash. Defences against that are signed checkpoints (raise the bar; §15) and, decisively, an off-device anchor holding an earlier head (§16). Truncation of the most recent events after the last checkpoint / anchor is also not detectable by the chain alone.

## 12. File-Level Chain / Segment Rollover

PROPOSED ARCHITECTURE. Audit storage is bounded by sealing segments.

- The live audit head is appended transactionally inside `bjstock.db` (HD-A1, HUMAN APPROVED: HYBRID). When a rollover condition is met (event count, byte size, or calendar boundary; thresholds HD-B4), the current segment is **sealed** into a file-based, immutable-style canonical JSONL segment visible to the independent verifier.
- **Sealed segment location (HD-A1):** sealed segments live in **app-private storage during Alpha**, unless a later Human decision explicitly chooses a different secure location. No broad shared-storage permission is requested. The exact Android directory / path is **not** implementation-frozen and is not defined here. A future external archive / anchor (§16, docs/150 §15) is separate from local sealed-segment storage.
- The last event of a segment is `AUDIT_FILE_ROLLOVER`, which names the next segment id. The first event of the next segment carries `previous_event_hash` equal to the rollover event's hash, so continuity crosses files.

```
Segment A: seq 0 … 1000   last_event_hash = H1000   (event 1000 = AUDIT_FILE_ROLLOVER → next = B)
Segment B: seq 1001 …     event 1001.previous_event_hash = H1000
```

Segment header / footer fields:

| Field | Meaning |
| --- | --- |
| `segment_id` | Stable id (for example `<installation_id>-<key_epoch>-<ordinal>`) |
| `schema_version` | Audit event schema version used in the segment |
| `first_sequence` / `last_sequence` | Inclusive range |
| `previous_segment_last_hash` | Hash the first event links to (GENESIS zeros for the first segment) |
| `first_event_hash` / `last_event_hash` | Boundary hashes |
| `segment_sha256` | SHA-256 over the exact segment file bytes (domain-separated) |
| `created_at` / `closed_at` | UTC instants (§10) |

### 12.1 Crash-Safe Sealing Protocol (S0-R-F02, PROPOSED)

Sealing copies a closed range of live chain rows into an immutable segment. It must be safe against a crash at any step.

| Step | Action | Durable state after the step | Crash here → recovery |
| --- | --- | --- | --- |
| 1 | Select the closed range `[first_sequence, last_sequence]` (ends with `AUDIT_FILE_ROLLOVER`) | Live rows unchanged | Nothing to undo; range re-selected deterministically from the chain |
| 2 | Write an immutable **candidate** segment file under a candidate name (never the final name) | Candidate file may be partial | Candidate is untrusted until step 5; recovery deletes or regenerates it from live rows (live rows still authoritative) |
| 3 | Flush / fsync the file (and its directory where the platform permits) | Candidate durable to the extent the platform guarantees | Same as step 2 |
| 4 | Re-read the candidate, recompute `segment_sha256`, re-verify every event hash and the boundary links against the live rows | Verified candidate | If verification fails: discard the candidate, record `SEGMENT_SEAL_FAILED`, retry later; live rows untouched |
| 5 | In **one database transaction**, mark the range sealed and write the rollover / segment metadata (segment id, range, hashes, candidate → final name) and the manifest update | Range sealed; manifest references the segment | Atomic: either the range is still live (redo from step 1) or it is sealed with verified metadata |
| 6 | Only then, and only if retention policy allows (§35–§37), remove or archive the live rows of the sealed range | Live rows removed / archived | If interrupted: rows that are both live and sealed are **duplicates by range and hash**, detected deterministically and removed idempotently; never a loss |

Guarantees and limits:

- **No silent loss:** live rows are removed only after a verified, sealed segment and manifest entry exist (step 5 precedes step 6).
- **Deterministic duplicate detection:** a sequence range present both live and sealed is identified by identical sequence numbers and event hashes; conflicting content for the same sequence is `AUDIT_CHAIN_MISMATCH`.
- **Deterministic recovery:** recovery inspects the sealed-range metadata first, then candidate files, then live rows, and resumes from the first incomplete step.
- **Durability caveat:** file rename and fsync semantics differ across Android versions, file systems, and devices; this design does **not** assume rename / fsync give universal durability guarantees. Correctness rests on step 4 re-verification, on the transactional step 5, and on keeping live rows until step 6, not on rename atomicity alone.

## 13. Audit Manifest

PROPOSED ARCHITECTURE. The manifest describes the complete segment set and is itself chained.

| Field | Meaning |
| --- | --- |
| `manifest_version` | Monotonic integer, +1 per manifest update |
| `manifest_schema_version` | Schema version |
| `installation_id` | Pseudonymous installation id |
| `key_epoch` | Signing key epoch |
| `segments[]` | For each segment: `segment_id`, `first_sequence`, `last_sequence`, `segment_sha256`, `first_event_hash`, `last_event_hash`, `created_at`, `closed_at`, `state` (`LIVE`, `SEALED`, `EXPORTED`, `ANCHORED`, `PURGED`) |
| `purged[]` | Tombstones for purged segments (range, hashes, purge event reference; §37) |
| `previous_manifest_hash` | Hash of manifest `manifest_version − 1` |
| `manifest_hash` | SHA-256 over the canonical manifest without this field (domain-separated) |
| `signature_ref` | Reference to the signed checkpoint covering this manifest (§15) |

| Finding | Detection |
| --- | --- |
| File deletion | A listed segment is missing on disk without a tombstone |
| File replacement | `segment_sha256` mismatch, or boundary hashes do not link |
| Unexpected gap | Sequence ranges are not contiguous |
| Rollback | `manifest_version` or the highest sequence is lower than a previously signed checkpoint, local high-water mark, or anchor |
| Duplicate sequence range | Overlapping ranges across segments |

## 14. Key Separation

PROPOSED ARCHITECTURE. Exact Keystore API, parameters, and hardware-backing requirements are **not final** before independent review (HD-B1).

| Key | Purpose | Current status | Proposal |
| --- | --- | --- | --- |
| Credential Encryption Key | Encrypt KIS credentials / token at rest | Exists: AES-GCM, alias `bjstock_kis_secret_key_v1` (CURRENT REPOSITORY FACT) | Unchanged by this document. **Never used for audit signing** |
| Audit Signing Key | Sign checkpoints, manifests, digests | Does not exist | Non-exportable Android Keystore asymmetric private key, purpose `SIGN` only; candidate alias pattern `bjstock_audit_sign_v<epoch>` |
| Future Device Identity / Backend Key | Authenticate to a future anchor service or backend | Does not exist | Separate key and alias, created only when an anchor / backend gate authorizes it; never reused for signing audit content or encrypting credentials |
| Future Archive Encryption Key | docs/150 §15 archive (JSONL → gzip → AES-GCM) | Does not exist (docs/150 lists it as not implemented) | Separate key; not the credential key |
| Future REAL-TRADING credential key | — | Does not exist; not authorized | Separate gate only (§49) |

Audit signing key lifecycle (PROPOSED):

| Topic | Proposal |
| --- | --- |
| Alias / version | Alias includes the key epoch; `key_epoch` is recorded in every event, checkpoint, and digest |
| Creation | Created at chain GENESIS or when no usable key exists; creation recorded as `AUDIT_KEY_EPOCH_STARTED` with the public key fingerprint |
| Public key export | The public key (and, where available, the Keystore attestation certificate chain, HD-C5) is exported to the Human / verifier and, later, enrolled with the anchor service. The verifier never trusts a public key read from the device at verification time alone |
| Trust start = explicit enrollment (S0-R-F09) | Public-key trust begins only at an **explicit enrollment** event: the public key fingerprint is recorded out of band by the Human and / or the anchor service. Enrollment at GENESIS / key creation is preferred. History signed before enrollment is labelled **TOFU (trust on first use) / UNENROLLED** in verification and exports, and history before any key existed is labelled **UNSIGNED**; neither is reported as verified-signed |
| Rotation | New epoch by Human decision or schedule (HD-D3); the last checkpoint of the old epoch is signed by the old key and the first event of the new epoch references it, so continuity is preserved |
| Reinstall / clear data | Destroys Keystore keys and app data together (`allowBackup="false"`). Result (HD-A5): new installation ⇒ new `installation_id` unless a future authenticated restore mechanism exists (none is designed), new GENESIS, new key epoch. The verifier records an **installation discontinuity**; only an anchor or a prior export can prove what the previous installation's last head was |
| Key loss without data loss | (For example Keystore invalidation.) Record `AUDIT_KEY_EPOCH_STARTED` with reason `KEY_UNAVAILABLE`; classify `SECURITY_SUSPECTED` until the Human acknowledges; the chain continues with the new epoch |

## 15. Signed Checkpoints

PROPOSED ARCHITECTURE. Candidate primitive: **Android Keystore-backed ECDSA P-256 with SHA-256 (`SHA256withECDSA`)** — **PROPOSED — independent security review required** (HD-B1).

| Field | Meaning |
| --- | --- |
| `checkpoint_schema_version` | Schema version |
| `installation_id` | Pseudonymous installation id |
| `audit_sequence` | Sequence of the chain head being attested |
| `audit_head_hash` | `event_hash` at that sequence |
| `manifest_version` / `manifest_hash` | Manifest state attested |
| `build_id` / `config_hash` | Provenance at signing time |
| `wall_time` / `monotonic_time_ns` / `boot_identity` | Timestamp evidence (device-asserted; not trusted time) |
| `key_epoch` / `key_id` | Public key fingerprint |
| `signature_algorithm` | For example `ECDSA_P256_SHA256_V1` |
| `signature` | Over `"BJSTOCK-AUDIT-CHECKPOINT-V1\n" ‖ JCS(checkpoint without signature)` |

Checkpoint triggers (candidates; frequency HD-B2): segment rollover, hourly digest, `SESSION_CLOSED_CLEANLY`, Safe Mode entry, before AI export, before authorized purge.

**Limitations (explicit):**

- A compromised app process can still ask Keystore to sign maliciously generated data. A signature proves "this key signed this", not "this content is true".
- A device-asserted timestamp is not trusted time.
- Without an off-device copy, an attacker with full control can delete checkpoints together with the history they cover.
- Signed checkpoints are tamper **evidence**, not compromise prevention.

## 16. Off-Device Anchor

PROPOSED ARCHITECTURE. Implementation and destination are FUTURE HUMAN DECISIONS (HD-C1, HD-C2).

Goal: a locally rewritten history cannot match a checkpoint that was already exported off the device.

Minimal anchor payload:

| Field | Meaning |
| --- | --- |
| `installation_id` | Pseudonymous id |
| `period` | Digest period (start / end) |
| `last_sequence` / `audit_head_hash` | Chain head at the end of the period |
| `digest_hash` | Hourly / daily digest hash |
| `manifest_hash` | Manifest state |
| `build_id` | Build identity |
| `key_id` / `key_epoch` | Signing key reference |
| `signature` | Checkpoint / digest signature |

- Transmission: TLS / HTTPS only. Certificate pinning: FUTURE HUMAN DECISION (HD-C2).
- Never transmitted: raw market data, credentials, tokens, secrets, account data, personal-phone data.
- **Offline first:** local audit, checkpoint, and digest creation never depend on the network. If upload fails: local signed digest succeeds → `anchor_state = PENDING` → entry kept in the anchor pending queue (file class G) → retried later with bounded backoff (policy HD-C2). Repeated failure becomes `FAILED` in the digest and a WARNING in admin status; it does not stop paper trading by itself (§51).
- Anchor service requirements: see §16.1 minimum semantics (exact trust model HD-C1).
- Transitional substitute (candidate, needs Human approval): the Human periodically copies the latest signed checkpoint / digest hash off the device (for example manual export to a separate storage the phone cannot modify). Weaker: depends on Human cadence.
- **Requirement:** an external anchor (or a Human-approved transitional substitute) is required before the real personal-device Alpha is called **security-complete** (APPROVED HUMAN REQUIREMENT stated in this gate's directive).
- Limitations: an anchor proves that a head existed at the server's receive time; it does not prove the events were truthful, and it cannot protect events created after the last successful anchor. Network policy can delay anchoring for long periods (B10 evidence, §20).

### 16.1 External Anchor Minimum Semantics (S0-R-F10, PROPOSED)

Any anchor destination or transitional substitute chosen under HD-C1 must provide at least:

| Property | Requirement |
| --- | --- |
| History-preserving | Every accepted anchor record is retained; the store is **not** a simple overwrite-in-place "current head" row |
| Authenticated identity | Records are accepted only from an authenticated device / installation identity (enrolled key, §14); unauthenticated submissions are rejected |
| Idempotent duplicates | Re-submitting an identical record (same installation, epoch, sequence, head hash) is accepted idempotently and not counted twice |
| Conflicting heads retained | A different head hash for an already-anchored `(installation_id, key_epoch, last_sequence)` is **retained as a finding** (both records kept), never silently replaced |
| Server receive time | Recorded by the service, independent of the device clock |
| Retention | At least the Paper Trading study period (≥ 6 months; exact period HD-C3) |
| Independent read path | The verifier reads anchor records directly from the anchor store, not via the device or the app |

## 17. Hourly Security Digest

PROPOSED ARCHITECTURE. Schema `bjstock.audit.digest.hourly` v1.

| Group | Fields |
| --- | --- |
| Period | `period_start`, `period_end` (UTC, aligned to clock hours; display in KST) |
| Coverage | `first_sequence`, `last_sequence`, `first_hash`, `last_hash` (contiguous with the previous digest's `last_sequence + 1`) |
| Counts | `audit_event_count`, `error_count`, `warning_count`, `security_suspected_count`, `security_fatal_count`, `crash_count`, `unknown_termination_count`, `non_crash_termination_count` |
| Health | `network_failure_count`, `provider_failure_count`, `parser_failure_count` |
| Slots | `expected_slot_count`, `executed_slot_count`, `missed_slot_count` |
| Validation | `audit_chain_validation`, `signature_validation`, `manifest_validation` (`PASS`, `FAIL`, `NOT_RUN`) |
| Provenance | `build_id`, `config_hash`, `active_strategy_refs[]`, `active_run_refs[]` |
| Generation | `generated_at`, `generated_late` (boolean), `generation_trigger` (`SCHEDULED`, `CATCH_UP`, `SESSION_CLOSE`) |
| Chain | `previous_digest_hash`, `digest_hash`, `signature_ref` |
| Anchor | `anchor_state`: `NOT_REQUIRED`, `PENDING`, `ANCHORED`, `FAILED` |

Termination counts (S0-D-F02):

- **`crash_count`** = count of **verified fatal / crash termination categories used by the crash-loop policy** (§23, §26): `CRASH`, `CRASH_NATIVE`, `ANR`, `INITIALIZATION_FAILURE`, `RECOVERY_GATE_FAILED`. It is **not** the count of every `UNEXPECTED_TERMINATION`.
- **`unknown_termination_count`** = terminations whose classification is still `UNKNOWN` (no matching exit evidence). Counted separately; never added to `crash_count`.
- **`non_crash_termination_count`** = `OS_RECLAIMED` (including expected MemoryLimiter / resource reclaim), `USER_STOPPED`, `DEVICE_REBOOT`. Normal Android process reclamation is never mixed into crash metrics.

No secrets, no raw market data, no personal data. A daily digest (file class F) aggregates the hourly digests of one KST date with the same structure plus daily performance references.

## 18. Digest Chain

PROPOSED ARCHITECTURE.

```
digest(N).previous_digest_hash = digest(N − 1).digest_hash
digest(N).first_sequence       = digest(N − 1).last_sequence + 1
```

| Finding | Detection (never by filename order alone) |
| --- | --- |
| Missing digest | Broken `previous_digest_hash` link, or an expected digest slot (§19) with neither a digest nor an `AUDIT_DIGEST_MISSED` record |
| Duplicate digest | Two digests with the same `previous_digest_hash` or overlapping sequence coverage |
| Rollback | Latest digest older than the latest signed checkpoint / local high-water mark / anchor |
| Reordering | `period_start` order, sequence coverage order, and hash links disagree |

## 19. Audit Scheduler Health

PROPOSED ARCHITECTURE. The auditor is itself audited.

- Expected digest slots are derived deterministically from a digest policy (which hours are expected: every hour, market hours only, or hours with activity — HD-B3). They never depend on when a worker actually ran.
- Each expected slot moves through recorded states:

| Case | Evidence | Outcome |
| --- | --- | --- |
| Scheduler never ran | No `DIGEST_SLOT_STARTED` for the slot | Next recovery / digest run records `AUDIT_DIGEST_MISSED` with `reason_code = DIGEST_SCHEDULER_NOT_RUN` |
| Scheduler ran but failed | `DIGEST_SLOT_STARTED` then `DIGEST_GENERATION_FAILED` (reason code) | `AUDIT_DIGEST_FAILED`; retry per policy |
| Digest generated | `DIGEST_GENERATED` with `digest_hash` | Normal |
| Anchor upload failed | `DIGEST_GENERATED` then `ANCHOR_UPLOAD_FAILED` | Digest valid; `anchor_state = PENDING` / `FAILED` |

- These cases are never collapsed into one generic error.
- No retroactive fabrication: a missed slot stays missed. The next generated digest covers the contiguous sequence range (so integrity coverage stays continuous), is marked `generated_late = true` with `generation_trigger = CATCH_UP`, and lists the missed slot ids. This mirrors docs/160 §29–§30 (missed slots are never reconstructed).

## 20. Offline / Background Rule

APPROVED HUMAN REQUIREMENT (this gate) built on CURRENT REPOSITORY FACT (§2.3, Phase 11 B10, 2026-10-02):

| Item | Result |
| --- | --- |
| 07:00 scheduler timing | PASS |
| Automatic worker start | PASS |
| Background provider access | FAIL |
| Android policy | `APP_BACKGROUND` |
| Recovery | Foreground transition (human) restored connectivity |

Therefore:

- Local audit, checkpoint signing, and hourly digest creation **must not depend on network availability**.
- External anchoring may remain `PENDING` for extended periods.
- This document does **not** claim that a foreground service solves background network access. Whether a user-started `specialUse` foreground session keeps network access is exactly what the 12-B2 Smoke measures (docs/161 M-09, M-12; docs/162), and physical Smoke has not started.

## 21. Error Taxonomy

PROPOSED ARCHITECTURE. A top-level operational classification layered over docs/150 (`ErrorCategory`, `ErrorSeverity`, `RetryPolicy`, `OperationAction` remain the detailed model). These classes are carried in the one central error / reason registry (HD-A4, HUMAN APPROVED, §45, §46.1).

Naming distinction (S0-R-F11): the docs/150 **`ErrorCategory.SECURITY`** is an error *category* (what kind of error, for example an auth / credential / guard failure) and is unchanged. **`SECURITY_SUSPECTED`** and **`SECURITY_FATAL`** are taxonomy *classes* in this document (how integrity confidence and automation are affected). An `ErrorCategory.SECURITY` error does not by itself imply `SECURITY_SUSPECTED` or `SECURITY_FATAL`; the class is assigned only through the §22 rules. Where both appear in one record they are stored in separate fields (`error_category`, `taxonomy_class`).

| Class | Meaning | Examples | Retry | Automatic operation | Audit |
| --- | --- | --- | --- | --- | --- |
| `INFO` | Expected, non-error fact | Session started, digest generated, cadence slot executed | n/a | Continue | Event recorded (INFO severity) |
| `RECOVERABLE` | Transient or bounded failure with a safe retry or skip | Network unavailable, `NETWORK_BACKGROUND_BLOCKED`, provider timeout, `EGW00201` rate limit, one missing observation (MISSING, never zero) | Bounded per docs/150 `RetryPolicy`; no retry storm | Continue; affected slot / instrument may be SKIPPED or MISSED | Each failure and the final outcome recorded with reason code |
| `SESSION_FATAL` | The current session / Run cannot continue safely, but the app is sound | Allowlist denial (terminal for the probe session, docs/162 F02), parser schema mismatch on a required source, storage pressure CRITICAL, `forward_operations` invariant breach for one Run | No automatic retry within the session | Stop the session / Run (`BLOCK_RUN`); other Runs unaffected if isolated | Terminal session event + reason; operation finished with terminal status |
| `APP_FATAL` | The process cannot continue | Uncaught exception, DB open failure, OOM | Process restart only through normal OS / user paths, gated by crash-loop protection (§26) | Stops; next start passes the recovery gate (§29) | Best-effort pre-crash capture; at next start `UNEXPECTED_TERMINATION` / `UNKNOWN`, refined to a fatal / crash category only by postmortem evidence (§23) |
| `SECURITY_SUSPECTED` | Evidence that integrity *may* be compromised; not proof | Single `AUDIT_SEQUENCE_GAP` after a crash, `UNEXPECTED_CLOCK_JUMP`, unexpected permission change, `CONFIG_HASH_MISMATCH` without an activation event, key loss | No automatic retry of the triggering action | Automatic strategy paused until the Human reviews (DEGRADED) or Safe Mode if combined with other evidence | `SECURITY_SUSPECTED_RAISED` with `security_reason_codes` |
| `SECURITY_FATAL` | Integrity failure that makes evidence or state untrustworthy | `AUDIT_CHAIN_MISMATCH`, `AUDIT_SIGNATURE_INVALID`, `AUDIT_MANIFEST_MISMATCH`, `AUDIT_ROLLBACK_SUSPECTED`, `UNAUTHORIZED_TR_ATTEMPT` in production paths, `CRASH_AFTER_INTEGRITY_VIOLATION` | None | Safe Mode (§25) | `SECURITY_FATAL_RAISED`; evidence frozen (no purge) |
| `DEVICE_RISK` | Device-environment indicator outside BJStock's control | Root indicator, debugger attached on a release build, hook-framework indicator, outdated security patch level (policy HD-C4) | n/a | Policy-defined (HD-C4); before Smoke: record only, no enforcement (§48) | `DEVICE_RISK_INDICATOR` with indicator codes |

**`UNEXPECTED_TERMINATION` ≠ `APP_FATAL` (S0-D-F02).** A session that did not close cleanly is never permanently classified `APP_FATAL`. It starts as `UNEXPECTED_TERMINATION` / `UNKNOWN`; only a postmortem fatal / crash category (`CRASH`, `CRASH_NATIVE`, `ANR`, `INITIALIZATION_FAILURE`, `RECOVERY_GATE_FAILED`) is treated as `APP_FATAL`. Non-crash outcomes (`OS_RECLAIMED`, `USER_STOPPED`, `DEVICE_REBOOT`, expected MemoryLimiter / resource reclaim) are not `APP_FATAL` unless other evidence escalates them (§23).

**Crash ≠ Hack.** An `APP_FATAL` crash is classified as a software, OS, or resource event by default. It may escalate to `SECURITY_SUSPECTED` only when surrounding evidence supports it (§22), and to `SECURITY_FATAL` only on an integrity failure.

## 22. Security-Suspicion Correlation

PROPOSED ARCHITECTURE. Escalation is reason-code driven and deterministic.

| Reason code | Default class | Escalation when combined with |
| --- | --- | --- |
| `AUDIT_CHAIN_MISMATCH` | SECURITY_FATAL | — |
| `AUDIT_SIGNATURE_INVALID` | SECURITY_FATAL | — |
| `AUDIT_MANIFEST_MISMATCH` | SECURITY_FATAL | — |
| `AUDIT_SEQUENCE_GAP` | SECURITY_SUSPECTED | SECURITY_FATAL if the gap lies inside a signed / anchored range |
| `AUDIT_ROLLBACK_SUSPECTED` | SECURITY_FATAL | — |
| `APK_HASH_MISMATCH` | **Verifier / out-of-band only** (§30, §50): raised by the independent verifier when the externally recorded APK SHA-256 for a build does not match the expected artifact. The runtime never computes or self-approves its own APK hash | Verifier reports SECURITY_SUSPECTED; the Human decides whether the affected range is SAFE_MODE-relevant |
| `BUILD_ID_MISMATCH` | RECOVERABLE (Human review), automation effect DEGRADED, for an unexpected but correctly signed version change | Cleared only when out-of-band provenance confirms the build (§30); never self-authorized to NORMAL |
| `SIGNING_IDENTITY_CHANGED` | SECURITY_FATAL | — |
| `CONFIG_HASH_MISMATCH` | SECURITY_SUSPECTED | SECURITY_FATAL inside a signed range |
| `COMMITMENT_MISSING` | RECOVERABLE (technical recovery condition, §9.4.4), automation effect DEGRADED until reconciled; **never automatically SECURITY_FATAL** | SECURITY_SUSPECTED / SECURITY_FATAL only with corroborating evidence (inside a signed / anchored range, or with chain / manifest failures) |
| `COMMITMENT_MISMATCH` | SECURITY_SUSPECTED | SECURITY_FATAL if the mismatching row version lies inside a signed / anchored range |
| `UNAUTHORIZED_ENDPOINT_ATTEMPT` | SESSION_FATAL + SECURITY_SUSPECTED | SECURITY_FATAL if repeated or targeting `/trading/` |
| `UNAUTHORIZED_TR_ATTEMPT` | SESSION_FATAL + SECURITY_SUSPECTED | SECURITY_FATAL if an order / account TR |
| `UNEXPECTED_PERMISSION_CHANGE` | SECURITY_SUSPECTED | — |
| `UNEXPECTED_CLOCK_JUMP` | SECURITY_SUSPECTED (or INFO for a recorded user / network time correction) | — |
| `REPEATED_CRASH_PATTERN` | APP_FATAL + crash-loop protection | SECURITY_SUSPECTED if correlated with an integrity code |
| `CRASH_AFTER_INTEGRITY_VIOLATION` | SECURITY_FATAL | — |
| `DEVICE_RISK_INDICATOR` | DEVICE_RISK | SECURITY_SUSPECTED if combined with any integrity code |

Output (conceptual) attached to the recovery result and admin status:

```
security_suspected     = true | false
security_reason_codes  = [ "AUDIT_SEQUENCE_GAP", "UNEXPECTED_CLOCK_JUMP", ... ]
```

BJStock never labels an event "hacked". The Human-facing wording is "integrity could not be verified" or "security review recommended", with the reason codes.

## 23. Crash / Unexpected Termination

PROPOSED ARCHITECTURE. Both mechanisms are required; neither is sufficient alone.

**A. Best-effort pre-crash diagnostic capture**

- A default uncaught-exception handler writes a bounded, redacted crash diagnostic file (class B, §24) and then delegates to the previous handler. It never writes the authoritative audit chain (the process state may be corrupt) and never blocks or retries.
- It cannot run on native crashes, OS kills, OOM kills, or Task Manager stops. Therefore it is never the only evidence.

**Session semantics (S0-R-F04, S0-D-F01).** `session_id` means a **BJStock operational session**. It is **not** synonymous with the Android **process lifetime**:

| Concept | Meaning |
| --- | --- |
| Process | One Android process instance, represented by `process_ref` (`pid`, `process_start_time` evidence, `boot_identity`; HD-A5). Android may reclaim it at any time, including while no BJStock session is active |
| Session | A BJStock operational session identified by `session_id`, for example an explicit user-started probe / operation session, an automation operation session, or a strategy runtime session. One process may host zero, one, or several sessions; a session may be cut short by process death |

`SESSION_CLOSED_CLEANLY` closes a **session**, not a process. Process-level postmortem correlation uses `process_ref` (`pid`, `process_start_time`, `boot_identity`) together with `installation_id` and `session_id`, recorded at session start and on process start, matched against `ApplicationExitInfo` (`pid`, timestamp). Monotonic time comparisons are valid only within the same boot identity (§26).

**B. Next-start postmortem recovery**

- Normal termination of a session writes `SESSION_CLOSED_CLEANLY` (chain event, followed by a checkpoint where feasible).
- At the next start, if the last open session has no `SESSION_CLOSED_CLEANLY`, the recovery gate appends `UNEXPECTED_TERMINATION` referencing that session with **`termination_classification = UNKNOWN`**, pending exit evidence. **A missing `SESSION_CLOSED_CLEANLY` does not mean "crash".** It only means the session did not close normally.
- Then it inspects safely available evidence, correlates by `pid` / `process_start_time` / `boot_identity`, and appends a compact postmortem event that refines the classification:

| Evidence | Use |
| --- | --- |
| `ApplicationExitInfo` (historical process exit reasons) | Reason, description (including `REASON_OTHER` "MemoryLimiter", `REASON_USER_REQUESTED`, `REASON_LOW_MEMORY`, `REASON_CRASH`, `REASON_CRASH_NATIVE`, `REASON_ANR`, `REASON_INITIALIZATION_FAILURE`), `pid`, timestamp, importance |
| `process_ref` of the terminated session | Correlation key against `ApplicationExitInfo`; reboot detected by a different `boot_identity` (boot count where reliably available) |
| Last audit head | Sequence / hash continuity up to the crash |
| Last checkpoint | Signed state before the crash |
| Manifest | Segment completeness |
| Latest digest | Digest-chain continuity |
| Anchor status | Pending / confirmed heads |
| Build / config identity | Unchanged across the restart? |
| Unfinished operations | Authoritative operational records only (§8.1): `forward_operations` RUNNING rows older than `processStartCutoff` and `OPERATION_STARTED` without `OPERATION_FINISHED` (existing `PROCESS_INTERRUPTED` recovery, CURRENT REPOSITORY FACT) |
| Crash diagnostic file | Compact reference only (`crash_id`) |

Classification after evidence (the initial value is always `UNKNOWN`):

| `termination_classification` | Evidence | Counts toward crash-loop protection (§26)? |
| --- | --- | --- |
| `CRASH` | `REASON_CRASH` matched by `pid`, and / or a matching crash diagnostic file | Yes |
| `CRASH_NATIVE` | `REASON_CRASH_NATIVE` | Yes |
| `ANR` | `REASON_ANR` | Yes |
| `INITIALIZATION_FAILURE` | `REASON_INITIALIZATION_FAILURE`, or a start that failed before the recovery gate completed | Yes |
| `RECOVERY_GATE_FAILED` | The recovery gate itself could not complete | Yes |
| `OS_RECLAIMED` | `REASON_LOW_MEMORY`, `REASON_OTHER` with "MemoryLimiter", other ordinary process reclamation | **No** |
| `USER_STOPPED` | `REASON_USER_REQUESTED`, `REASON_USER_STOPPED`, explicit force-stop | **No** |
| `DEVICE_REBOOT` | Different `boot_identity` with no fatal exit record | **No** |
| `UNKNOWN` | No matching exit evidence | No (recorded; repeated `UNKNOWN` raises a WARNING, not a crash-loop count) |

- `UNEXPECTED_TERMINATION` is **never permanently classified `APP_FATAL`** (S0-D-F02). Fatal / crash categories (`CRASH`, `CRASH_NATIVE`, `ANR`, `INITIALIZATION_FAILURE`, `RECOVERY_GATE_FAILED`) receive the fatal / crash classification; `OS_RECLAIMED` (including expected MemoryLimiter / resource reclaim), `USER_STOPPED`, and `DEVICE_REBOOT` receive a non-crash termination classification unless other evidence escalates them; `UNKNOWN` stays `UNKNOWN`.
- Hourly digest counts follow the same split (§17): only fatal / crash categories feed `crash_count`.
- Security classification remains evidence-driven: escalation to security classes happens only via §22.
- Evidence written before the termination remains valid because appends are transactional; at most the in-flight, uncommitted transaction is lost, and the existing `OPERATION_STARTED` row without `OPERATION_FINISHED` shows what was in progress.

## 24. Crash Diagnostic Log

PROPOSED ARCHITECTURE. Separate from audit.

| Allowed content | Requirement |
| --- | --- |
| Exception class, sanitized stack trace (frames only; messages passed through `SafeLogText`), thread name, component, build id, OS version, device model, coarse memory / storage state | Secret redaction; no request / response bodies; no credential values |
| Size | Bounded per file (HD-B4) |
| Count | Bounded number of files; oldest removed first |
| Retention | HOT tier (§35) |

The audit stores only a compact structured reference: `crash_id`, `exception_class`, `component`, `reason_code`, integrity state at the crash, last sequence / hash, classification.

## 25. Safe Mode

PROPOSED ARCHITECTURE. Not implemented.

| Trigger | Notes |
| --- | --- |
| `SECURITY_FATAL` | Any §22 code classified SECURITY_FATAL |
| Audit chain failure | Chain mismatch, unverifiable chain |
| Signature failure | Invalid checkpoint / manifest / digest signature |
| Unrecoverable DB invariant failure | docs/150 `INVARIANT` / `FINANCIAL_INTEGRITY` failure that recovery cannot resolve |
| Repeated crash loop | §26 |
| Critical storage failure | Storage EXHAUSTED or corruption |
| Unknown build / config provenance | Signing-identity change (`SIGNING_IDENTITY_CHANGED`), config hash change inside a signed range. A correctly signed but unconfirmed version change is DEGRADED (§30), not Safe Mode, unless combined with other integrity findings |

Safe Mode behaviour:

- Stop automatic strategy execution and all automatic future order flow (paper today; real is not authorized anyway).
- Preserve evidence: no purge, no rotation of authoritative audit, no export overwrite; diagnostic rotation continues within bounds.
- Show a Human-readable reason with reason codes and the evidence summary.
- Avoid reconnect storms: no automatic provider reconnects; scheduled automatic work is suspended.
- Read-only access to history and the AI export (integrity-marked) remains available.
- Exit only through an explicit recovery / acknowledgement path: the Human acknowledges (`SAFE_MODE_ACKNOWLEDGED`), the recovery gate re-runs, and exit is recorded (`SAFE_MODE_EXITED`). Acknowledging does not erase the finding; the affected range stays marked.

### 25.1 Safe Mode Persistence, Write Failure, and Acknowledgement Scope (S0-R-F06, FROZEN ARCHITECTURE RULES)

- **Restart never clears Safe Mode.** Safe Mode is not an in-memory flag. At every start the recovery gate (§29) **re-derives** Safe Mode from durable evidence and state (unresolved SECURITY_FATAL findings, failed verification results, crash-loop evidence, storage state, unacknowledged reason codes). If the triggering condition or an unacknowledged finding still exists, Safe Mode is re-entered.
- **If the recovery / integrity result cannot be safely persisted** (audit write fails, storage exhausted, database cannot open), **no automatic strategy operation starts**. The app may show a best-effort, non-authoritative status screen, but it never "assumes NORMAL" because recording failed.
- **Acknowledgement scope:**
  - An acknowledgement applies only to **explicitly identified** reason codes, event ids, and / or sequence ranges; there is no global "clear all".
  - It is itself audited (`SAFE_MODE_ACKNOWLEDGED` with the identified scope) when writing is possible; an acknowledgement that cannot be recorded has no effect.
  - It does not suppress future or new findings; a **new relevant finding re-enters Safe Mode**, even for the same reason code in a different range.
  - The UI alone cannot declare the system healthy: exit requires the recovery gate to re-run and produce a recorded non-SAFE_MODE result.
- Exact authentication UX for acknowledgement (for example device credential re-prompt) remains a later Human decision (HD-C6).

## 26. Crash-Loop Protection

PROPOSED ARCHITECTURE. Thresholds: **OPEN — Human decision after evidence** (HD-B6).

Counted evidence (S0-R-F04): only verified fatal terminations count — `CRASH`, `CRASH_NATIVE`, `ANR`, `INITIALIZATION_FAILURE`, `RECOVERY_GATE_FAILED` (§23), or equivalent verified fatal reasons. An `UNEXPECTED_TERMINATION` that remains `UNKNOWN`, ordinary Android process reclamation (`OS_RECLAIMED`, including low memory and MemoryLimiter), user stops, and device reboots **do not** increment crash-loop counters and never feed security escalation by themselves.

Time basis (S0-R-F16): windows use a **monotonic, boot-aware** basis — `monotonic_time_ns` within one `boot_identity` (HD-A5: monotonic comparisons are valid only within an appropriate boot boundary), plus a count of starts across boots — never the mutable wall clock. A wall-clock change can neither create nor hide a crash loop. Cross-boot windows are expressed as "K consecutive starts" rather than wall-clock durations.

| Class | Definition (shape only) | Response |
| --- | --- | --- |
| Single crash | One counted fatal termination | Normal recovery gate |
| Repeated crash within a bounded period | ≥ N counted fatal terminations within monotonic window W (same boot) or within the last K starts | `REPEATED_CRASH_PATTERN`; automatic operation paused; exponential backoff for scheduled work |
| Persistent startup failure | Recovery gate fails on K consecutive starts | `CRASH_LOOP_DETECTED` → Safe Mode |

Prevents: infinite restart (no self-restart mechanism is proposed), battery drain (backoff, no wake-lock retries), network storm (no reconnect in Safe Mode, bounded retry budget), storage flood (crash files bounded by count and size; repeated identical crashes are counted, not duplicated).

## 27. Component Failure Boundaries

PROPOSED ARCHITECTURE.

| Component | Failure | Contained behaviour | Must never |
| --- | --- | --- | --- |
| Market Data | Source unavailable / stale | Observation MISSING; affected slot SKIPPED / MISSED; no whole-app crash if containable | Substitute zero, last value, or an approximation |
| Provider Transport | Timeout, TLS error, policy block, rate limit | Bounded retry per docs/150; `NETWORK_BACKGROUND_BLOCKED` recorded distinctly | Re-issue tokens on every reconnect (docs/161 §12); retry storms |
| Parser | Malformed payload | Reject the payload; record `PROVIDER_MALFORMED` / `PARSER_SCHEMA_MISMATCH` | Fabricate an observation |
| Factor Calculation | Missing input / numeric error | Factor MISSING with reason; strategy sees MISSING | Treat MISSING as neutral or zero |
| Strategy Evaluation | Exception / invariant breach | Evaluation FAILED for that slot; Run BLOCK_RUN if repeated | Emit a signal from partial inputs |
| Risk Gate | Cannot evaluate | Deny (Fail-Closed) | Allow by default |
| Paper Trading | Fill evidence unavailable / unprovable (Option A strict-after) | NO FILL (docs/160 §21, docs/161) | Fake or retroactive fills |
| Audit | Cannot append / verify | Automatic strategy FAIL CLOSED (§51) | Continue trading after "logging failed" |
| Storage | Pressure / failure | Escalate per §36 | Silently delete unanchored authoritative audit |
| UI | Render failure | Show an error state; no side effects | Mutate trading state |
| Scheduler | Did not run / ran late | Slot MISSED or LATE recorded deterministically | Reconstruct missed slots |
| External Anchor | Unavailable | `anchor_state = PENDING` / `FAILED`; WARNING | Block local audit / digest creation |

## 28. Data / Database Atomicity

PROPOSED ARCHITECTURE principles, referencing the current Paper Trading architecture (no Room redesign here).

- **Transaction boundaries:** the domain state change, its `trade_audit_logs` row, and (when adopted) its row commitment are written in one Room transaction (docs/150 §11, CURRENT design requirement); where that is technically impossible, deterministic reconciliation and `COMMITMENT_MISSING` detection apply (§9.4.4).
- **Single source of truth:** business and operational outcomes stay in their existing authoritative tables; the integrity chain only commits to them (§8.1).
- **Idempotency keys:** existing `event_key` uniqueness and operation keys (docs/150 §12) remain the duplicate-prevention mechanism; chain events reference them.
- **State transition validation:** transitions are validated against allowed-transition tables before persistence (for example `forward_operations` only `RUNNING → terminal`, CURRENT REPOSITORY FACT; docs/150 §20.5–§20.7 financial-integrity and execution-invariant rules).
- **Atomic persistence:** no multi-transaction partial updates for one logical financial action; an interrupted action is detected by recovery (existing `PROCESS_INTERRUPTED` handling: `OPERATION_STARTED` without `OPERATION_FINISHED`) and finalized as interrupted, never completed retroactively.
- Intraday tables, pending-exposure state, and fill provenance (F-21, F-22) are owned by 12-C / 12-I / 12-K (docs/160) and are out of scope here.

## 29. Recovery Startup Gate

PROPOSED ARCHITECTURE. Runs before any automatic operation after a restart. It replaces the "ignore failure" behaviour of the current `runIgnoringFailure` startup wrapper with explicit classification (S1).

| Step | Check | Failure result |
| --- | --- | --- |
| 1 | Compare runtime-observed build identity (package, version, build type, signing identity) with the last recorded identity (§30) | Signing identity changed → SAFE_MODE; correctly signed but unexpected version change → DEGRADED until out-of-band provenance confirms it (never self-authorized NORMAL); unchanged → continue |
| 2 | Verify audit chain from the last verified checkpoint to the head | Mismatch → SAFE_MODE |
| 3 | Verify manifest (segments present, hashes, contiguous ranges) | Mismatch → SAFE_MODE |
| 4 | Verify the latest signed checkpoint | Invalid → SAFE_MODE; missing (pre-S2) → DEGRADED |
| 5 | Compare with the external anchor when available | Divergence → SAFE_MODE; unavailable → no change (anchor PENDING) |
| 6 | Validate DB invariants (docs/150 invariants, `cash_ledger` / position consistency) and commitment completeness for covered tables (§9.4.6) | Unrecoverable → SAFE_MODE; recoverable → DEGRADED with repair event; `COMMITMENT_MISSING` → deterministic reconciliation / DEGRADED (not automatically SECURITY_FATAL) |
| 7 | Detect unfinished session / operation | Record `UNEXPECTED_TERMINATION` with initial classification `UNKNOWN`, refine by postmortem evidence (§23); finalize interrupted operations (existing `PROCESS_INTERRUPTED`) |
| 8 | Detect crash loop | Loop → SAFE_MODE; repeated → DEGRADED with backoff |
| 9 | Evaluate security state (§22) | SECURITY_FATAL → SAFE_MODE; SECURITY_SUSPECTED → DEGRADED |

Result: `NORMAL`, `DEGRADED` (automatic strategy paused or restricted per policy; Human informed), or `SAFE_MODE`. The result is appended as `RECOVERY_GATE_RESULT` with all reason codes. **No silent resume when integrity is uncertain.** If the result cannot be persisted, no automatic strategy operation starts (§25.1).

### 29.1 Minimal Recovery / Integrity Gate — ALPHA MUST (S0-R-F05)

The full nine-step gate depends on later machinery (checkpoints S2, anchor S4, verifier S5). A **minimal gate is promoted to ALPHA MUST** (§47) and does **not** require that machinery. Before any automatic operation after a start, it must:

| Minimal step | Requirement |
| --- | --- |
| M1 Continuity | Verify chain continuity (sequence + hash links, and commitment completeness for covered tables) from the **last trusted / verified point** (last verified head recorded by a previous gate run, or GENESIS) to the current head |
| M2 Unfinished operations | Inspect authoritative unfinished operation state (`forward_operations` RUNNING older than `processStartCutoff`; `OPERATION_STARTED` without `OPERATION_FINISHED`) and finalize per existing `PROCESS_INTERRUPTED` recovery |
| M3 Unresolved fatal / security state | Inspect durable unresolved SECURITY_FATAL / SECURITY_SUSPECTED findings, unacknowledged reason codes, and counted crash-loop evidence (§25.1, §26) |
| M4 Decision | Determine and persist `NORMAL` / `DEGRADED` / `SAFE_MODE` |

**Automatic strategy operation must not silently resume when local integrity cannot be established** (M1 fails or cannot run, M4 cannot be persisted). This minimal gate replaces the silent `runIgnoringFailure` startup behaviour for the steps it covers.

## 30. Build / APK Provenance

PROPOSED ARCHITECTURE. Currently no build provenance is embedded (§2.2).

S0-R-F07 separates two identities that must never be conflated:

| Identity | Owner | Elements |
| --- | --- | --- |
| **Runtime-observed build identity** | The app (self-report; evidence, not proof) | Package id (`com.mirunubi.bjstock`), `versionName` / `versionCode` (currently `0.1.0` / `1`), build type, signing certificate identity (SHA-256 fingerprint) where safely available from the package manager, embedded Git / build metadata **if later implemented** (not present today) |
| **Out-of-band expected artifact identity** | Build evidence held outside the device (Human / build record / verifier) | Git HEAD used for the build, `dirty` flag, **APK SHA-256** of the produced artifact, expected signing certificate fingerprint, versionName / versionCode |

Rules:

- The runtime records `BUILD_IDENTITY_OBSERVED` (the elements above) at process start and whenever the observed identity differs from the last recorded one. `build_id` in audit events references the runtime-observed identity (domain-separated hash over its canonical tuple, §10).
- **APK SHA-256 is external build / install evidence.** The app does not compute, embed, or self-approve its own APK hash. `APK_HASH_MISMATCH` is raised only by the independent verifier / out-of-band comparison of the recorded artifact hash against the expected artifact identity (§22, §50).
- **Unexpected signing-identity change** remains severe (`SIGNING_IDENTITY_CHANGED` → SECURITY_FATAL / SAFE_MODE).
- **Unexpected but correctly signed version change** → `BUILD_ID_MISMATCH` → **DEGRADED / Human review** until out-of-band provenance confirms the build; the app never self-authorizes NORMAL for a new build. A confirmation is recorded as an audited Human acknowledgement scoped to that build identity (§25.1 scope rules).
- **Build-confirmation trust limit.** A Human build acknowledgement performed **on the same device** is **not** an independent cryptographic trust root: whoever controls the device or app process could produce or replay it. Its strength depends on (1) Alpha authentication for the acknowledgement (HD-C6), (2) out-of-band artifact evidence (Git HEAD, APK SHA-256, signing fingerprint held off the device), and (3) the future independent verifier / external evidence (§16, §50). The acknowledgement is recorded as evidence of a Human decision, not as proof of build authenticity.

## 31. Config / Strategy Provenance

PROPOSED ARCHITECTURE. Canonical hashes (domain-separated, §10) for:

| Configuration | Hash | Notes |
| --- | --- | --- |
| Runtime configuration | `runtime_config_hash` | Feature flags, retention / quota settings |
| Provider configuration | `provider_config_hash` | Environment, host / path / TR allowlist, pacing; credential **class** only |
| Strategy configuration | `strategy_config_hash` | Strategy version, factor weights, rules |
| Risk policy | `risk_policy_hash` | docs/160 Risk Gate parameters |
| Cadence configuration | `cadence_config_hash` | §32 |
| Approved symbol universe | `universe_hash` | Ordered instrument list |

- `config_hash` in events = hash over the canonical set of the above.
- No secret plaintext ever enters provenance. Secret references use safe metadata (class, version, key alias epoch).
- Activation of a new configuration is an audit event (`CONFIG_VERSION_ACTIVATED`); a hash change without it is `CONFIG_HASH_MISMATCH`.

## 32. Cadence Provenance

PROPOSED ARCHITECTURE. No implementation.

- Future intraday cadence is configurable. **15 minutes is the DEFAULT, not permanent semantics.** Future examples: 15, 20, 30 minutes (values remain Human decisions under docs/160).
- Cadence belongs to the immutable StrategyVersion / Run snapshot, is part of `cadence_config_hash` and `config_hash`, and appears in audit provenance of every slot event.
- Slot anchors are deterministic from the session anchor (for example `session_open + k × cadence`), never derived from delayed actual execution times. A late worker records lateness against the deterministic slot; it does not shift subsequent slots.

## 33. Administrator Security Status

PROPOSED ARCHITECTURE. Future Human-facing status; UI scope HD-C6.

Overall state: `NORMAL`, `WARNING`, `SECURITY_SUSPECTED`, `SAFE_MODE`.

| Shown | Source |
| --- | --- |
| Last audit event (time, sequence) | Chain head |
| Last hourly digest (time, state) | Digest chain |
| Last integrity verification (time, result) | Recovery gate / periodic verification |
| Last external anchor (time, state) | Anchor queue |
| Anchor pending count | Queue G |
| Crash count (verified fatal / crash categories only, §17), unknown-termination count, error count, security-event count | Digests |
| Build provenance | `build_id` and its components |
| Config provenance | `config_hash` and active versions |
| Audit chain status | PASS / FAIL / NOT_VERIFIED |
| Signature status | PASS / FAIL / NOT_AVAILABLE (pre-S2) |

Drill-down: by hour (hourly digests), day (daily digests), session, and Run. Routine administration never requires reading raw JSON; raw events remain available for export.

## 34. File Classes

PROPOSED ARCHITECTURE. All in app-internal storage; values marked HD are Human decisions.

| Class | Purpose | Sensitivity | Integrity requirement | Retention class | Size policy | Deletion policy |
| --- | --- | --- | --- | --- | --- | --- |
| A. Runtime diagnostic logs | Developer troubleshooting | Medium (redacted) | None | HOT | Size-based ring (HD-B4) | Automatic ring overwrite |
| B. Crash diagnostic files | Postmortem detail | Medium (redacted stack traces) | None; referenced by `crash_id` | HOT | Bounded size and count | Oldest first, automatic |
| C. Audit event segments | Authoritative evidence | High (structured, no secrets) | Hash chain + segment hash | COLD for the study period | Rollover thresholds (HD-B4) | Authorized purge only, after export / anchor, with deletion proof |
| D. Audit manifests | Segment-set description | Medium | Chained + signed | COLD | Small | Never purged while any covered segment exists; tombstones kept |
| E. Hourly digests | Hourly health / integrity | Low–medium | Chained + signed | WARM | Small | Authorized cleanup after the daily digest is signed and anchored / exported |
| F. Daily digests / summaries | Long-term health / integrity | Low–medium | Chained + signed | COLD | Small | Kept for the study period at minimum |
| G. External-anchor pending queue | Unsent anchor payloads | Low | Payloads signed | Until anchored | Bounded; overflow → WARNING, never silent drop | Removed only after `ANCHOR_CONFIRMED` |
| H. AI export package | Human-initiated analysis export | Depends on scope (§43) | Export manifest + integrity report | User-controlled after export | Per export | Temporary in-app copy deleted after hand-off; the export itself is recorded in audit |

Raw probe evidence (12-B2) follows its own docs/162 policy and is never mixed with these classes.

## 35. Retention Tiers

PROPOSED ARCHITECTURE. Configurable tiers with **reviewable starting ranges, not final policy** (HD-C3).

| Tier | Content | Proposed starting range | Rationale |
| --- | --- | --- | --- |
| HOT | Diagnostic logs (A), crash files (B), detailed raw diagnostics | 3–30 days (docs/150 §14 DEBUG: 3 days) | Debugging value decays quickly; minimizes personal-phone footprint |
| WARM | Hourly digests (E), checkpoints, error summaries | 90–400 days | Supports hour-level drill-down across the study |
| COLD | Daily digests (F), authoritative audit segments (C) and manifests (D), provenance, anchor receipts | ≥ 400 days hot, archive per docs/150 §14–§15 (domain audit: 400 days hot / 5 years archive) | Must outlive the 6-month study with margin |

Requirement (APPROVED HUMAN REQUIREMENT from this gate): **Paper Trading study evidence must survive at least 6 months.** Detailed logs may expire; authoritative summaries and provenance must survive the study period. docs/150 §10 still applies: source-of-truth tables are never disposable logs.

## 36. Size / Rotation

PROPOSED ARCHITECTURE.

| Class | Rotation |
| --- | --- |
| Diagnostic logs | Size-based rotation / ring |
| Audit | Segment rollover preserving hash continuity (§12) |
| Crash files | Bounded count and size |
| Hourly / daily digests | Small; longer retention |
| Raw probe data | Separate policy (docs/162) |

Storage pressure levels (thresholds HD-B5):

| Level | Action |
| --- | --- |
| NORMAL | — |
| PRESSURE | Shrink HOT classes (A, B) first; WARNING; `STORAGE_PRESSURE` |
| CRITICAL | No new sessions / Runs start; current session ends → `SESSION_FATAL` |
| EXHAUSTED or write failure | `STORAGE_FAILED` → `SAFE_MODE` |

**Critical rule:** unanchored or unexported authoritative audit evidence is **never silently deleted** because of storage pressure. Pressure escalates to `SESSION_FATAL` or `SAFE_MODE` instead.

## 37. Deletion / Cleanup

PROPOSED ARCHITECTURE. Only explicit, authorized cleanup.

| Case | Precondition |
| --- | --- |
| Retention expiration | Covered by a signed daily digest and checkpoint; for authoritative classes also exported or anchored |
| Manual export | Export verified (§41) before any local removal |
| Verified archive | docs/150 §15–§16 archive manifest verified ("never purge hot rows before archive verification succeeds") |
| Authorized purge | Explicit Human authorization recorded in audit |

Deletion proof: before removing authoritative history, append `AUDIT_RANGE_PURGED` with the sequence range, segment ids and hashes, boundary hashes, authorization reference, and export / archive / anchor references; then sign a checkpoint. The manifest keeps a tombstone. Expired raw history therefore still leaves signed digest / checkpoint proof that the range existed and closed normally.

## 38. Privacy / Secret Redaction

PROPOSED ARCHITECTURE.

| Output | Rule |
| --- | --- |
| Diagnostic logs | `SafeLogText`-style allowlist redaction; no bodies, headers, tokens, keys |
| Crash logs | Stack frames plus redacted messages only |
| Audit | Allowlisted structured fields only (§9); no free-text payloads |
| AI export | Positive field allowlist plus pre-export scan (§43) |

Never included anywhere: access tokens, approval keys, app keys, app secrets, authorization headers, passwords, private keys, account numbers.

Personal-phone minimization: no contacts, accounts, location, installed-app lists, phone numbers, IMEI / serial, or advertising ids. Device identity in evidence is a **pseudonymous installation id** (HD-A5, HUMAN APPROVED): a random UUID generated at audit GENESIS; not derived from IMEI, phone number, Google account, or any other personal or hardware identifier; stored app-privately; referenced by audit, checkpoints, and anchors; a new installation receives a new installation id unless a future authenticated restore mechanism exists. Device model and OS version are recorded only where they explain behaviour (docs/161 M-28). The AI export defaults to the minimum necessary data.

## 39. AI 6-Month Analysis Requirement

APPROVED HUMAN REQUIREMENT (this gate), treated as first-class. After 6+ months of Paper Trading, AI analysis must be possible for:

strategy performance; Run differences; market regimes; errors; provider / network reliability; missed slots; retries; crashes; security anomalies; configuration changes; build changes; audit integrity; system health.

PROPOSED: preserve structured metadata sufficient for reproducible analysis — every Run snapshot with config / strategy / cadence hashes, every build identity, every reason code with registry version, every digest, and the integrity status of each range. Free-form text is never the only carrier of meaning.

## 40. AI Export Package

PROPOSED ARCHITECTURE. Package name: `BJStock_Analysis_Export_<date-range>.zip`.

| File | Content | Kind |
| --- | --- | --- |
| `export_manifest.json` | Export schema version, date range, scope choice, file list with SHA-256, source ranges | Metadata |
| `builds.json` | Build identities in range | Source |
| `strategies.json` | Strategy versions and config hashes | Source |
| `runs.json` | Run snapshots (cadence, universe, risk policy refs) | Source |
| `config_versions.json` | Config activations and hashes | Source |
| `daily_performance.csv` / `.jsonl` | Daily performance metrics | DERIVED |
| `trade_summary.csv` / `.jsonl` | Paper orders / fills summary | DERIVED |
| `signal_summary.csv` / `.jsonl` | Signals and decisions summary | DERIVED |
| `audit_events.jsonl` | Canonical audit events (full-audit scope only) | Source |
| `source_rows/orders.jsonl`, `source_rows/executions.jsonl`, `source_rows/cash_ledger.jsonl` (and other authoritative tables as approved) | Authoritative source rows in their per-table canonical form (§9.4.3), each with `row_identity`, `row_version`, `row_hash`, and the referencing commitment sequence (full-audit scope only; S0-R-F12) | Source |
| `hourly_security_digests.jsonl` | Hourly digests | Source |
| `daily_security_digests.jsonl` | Daily digests | Source |
| `error_summary.jsonl` | Errors by reason code / component / period | DERIVED |
| `crash_summary.jsonl` | Crashes with classification | DERIVED |
| `provider_health.jsonl` | Provider / network outcomes | DERIVED |
| `scheduler_health.jsonl` | Expected vs actual slots, delays, retries (§44) | DERIVED |
| `integrity_report.json` | §41 | Verification |

Giant raw diagnostic logs are **not** included by default; diagnostic inclusion requires an explicit Human choice (§43).

## 41. AI Export Integrity

PROPOSED ARCHITECTURE.

- The export is independently verifiable (§50). `integrity_report.json` includes: date range; source sequence ranges; source segment ids and SHA-256; latest signed checkpoints covering the range; manifest hashes; build / config / strategy identities; anchor references; the verification result per range (`VERIFIED`, `VERIFIED_UNANCHORED`, `FAILED`, `NOT_AVAILABLE`) and any security findings.
- The export never rewrites original event history; source events are copied byte-for-byte in canonical form.
- Derived tables are marked `DERIVED` (per-file in `export_manifest.json` and per-record `derived: true`), with source sequence ranges where practical, the derivation version, and the code `build_id` that produced them.

## 42. Schema Versioning

PROPOSED ARCHITECTURE.

| Schema | Version field |
| --- | --- |
| Audit event | `schema_version` (§9) |
| Segment / manifest | `manifest_schema_version` |
| Checkpoint | `checkpoint_schema_version` |
| Digest | `digest_schema_version` |
| Export | `export_schema_version` |
| Reason-code registry | `registry_version` (§45) |

- Field semantics never change silently; a semantic change requires a new version.
- Old audit history is never rewritten because a schema evolved; hashes of old events stay valid.
- Normalization to the current analysis shape happens at read / export time through versioned readers, and the export records which reader versions were used.

## 43. External Analysis Safety

PROPOSED ARCHITECTURE. Before any AI export:

1. Secret scan (pattern-based detection of token / key / header shapes as defence in depth behind the field allowlist).
2. PII / minimal-personal-data scan.
3. Integrity verification of the covered range (§41).
4. Export manifest generation.

Any scan hit blocks the export and is recorded (`EXPORT_BLOCKED_SECRET_SCAN`); the offending value is never echoed.

Human choices: **summary-only** (DERIVED summaries + digests), **full audit** (adds `audit_events.jsonl`), **diagnostic-inclusive** (adds redacted diagnostics; explicit opt-in). Default: summary-only, no secrets, minimal personal data. Each export is itself an audit event (`AI_EXPORT_CREATED` with scope, range, package hash).

## 44. Scheduler / Operational Health

PROPOSED ARCHITECTURE. Each scheduled slot records: expected slot (deterministic id), actual worker start, delay, result, retries, recovery path, network state (including Android network-policy block reason where observable), and provider outcome.

Carry-forward (CURRENT REPOSITORY FACT, Phase 11 B10, 2026-10-02): the 07:00 slot fired on time and the worker started automatically, but background provider access was blocked by Android policy `APP_BACKGROUND`; the attempt succeeded only after a human brought the app to the foreground (§2.3). Verdict FAIL — UNATTENDED 07:00. **This is not solved.** Health records must therefore distinguish "worker started but network blocked by policy" (`NETWORK_BACKGROUND_BLOCKED`) from "network unavailable" (`NETWORK_UNAVAILABLE`) and from "provider failed" (`PROVIDER_TIMEOUT`, `PROVIDER_MALFORMED`).

## 45. Central Reason-Code Registry

**HD-A4, HUMAN APPROVED** (§46.1): there are **not** two independent semantic error systems. Existing `AppErrorCode` semantics are preserved and become part of **one versioned central Error / Reason Code Registry** (`registry_version`), which owns every reason code, event type, component, and taxonomy class used in audit. Not implemented; the individual code entries in §45.2 remain proposed entries for that registry.

Registry entry model (HD-A4):

| Element | Meaning |
| --- | --- |
| `reason_code` | Stable semantic identifier; never renamed or re-purposed |
| `category` | docs/150 `ErrorCategory` (for example `SECURITY` as an error category) |
| `severity` / `taxonomy_class` | docs/150 `ErrorSeverity` and the §21 class (for example `SECURITY_SUSPECTED`, `SECURITY_FATAL`) |
| `automation_effect` | `CONTINUE`, `DEGRADED`, `SESSION_STOP`, `SAFE_MODE` |
| Human-readable message | Display only; **never** the semantic identifier |

The registry must support: existing operational / reliability errors; provider / network errors; parser errors; audit integrity errors; security suspicion; security fatal conditions; recovery errors; Safe Mode reasons. Registry meaning cannot silently change between versions. Free-form strings are never authoritative semantics; `reason_text`, where it exists, is display-only.

### 45.1 Namespace Relationship and Versioning (S0-R-F11)

- **One reason-code namespace.** Integrity / security reason codes and the existing docs/150 `AppErrorCode` values share a single namespace: a code string means exactly one thing across `AppErrorCode`, `api_error_logs`, `operational_events`, `trade_audit_logs.reason_code`, and the integrity chain. New integrity / security codes must not collide with or redefine an existing `AppErrorCode` name; where an existing `AppErrorCode` already expresses a condition, it is reused rather than duplicated. The one-registry decision is HD-A4 (HUMAN APPROVED); the code-level mechanism (one enum, or a registry that imports `AppErrorCode`) is an implementation detail for S1 design and must preserve existing `AppErrorCode` semantics.
- **Category vs class (HD-A4, no naming collision).** `ErrorCategory.SECURITY` (docs/150) is a category; `SECURITY_SUSPECTED` / `SECURITY_FATAL` are taxonomy classes (§21). They represent different concepts, are stored in separate fields, and must remain explicitly separated.
- **Taxonomy class vs automation effect.** The taxonomy class says what kind of condition occurred; the automation effect (`CONTINUE`, `DEGRADED`, `SESSION_STOP`, `SAFE_MODE`) says what automatic operation does. Both are listed below so no code has two contradictory meanings.
- **Registry versioning is frozen before S1** (HD-A4): `registry_version` is an integer; codes are never renamed or re-purposed, only added or deprecated; a deprecated code stays resolvable for old history; every audit event records the `registry_version` in force.

### 45.2 Codes

| Code | Taxonomy class | Automation effect | Component |
| --- | --- | --- | --- |
| `NETWORK_UNAVAILABLE` | RECOVERABLE | CONTINUE (bounded retry / skip) | Provider Transport |
| `NETWORK_BACKGROUND_BLOCKED` | RECOVERABLE | CONTINUE (bounded retry / skip) | Provider Transport |
| `NETWORK_TLS_FAILURE` | RECOVERABLE; SECURITY_SUSPECTED if repeated on one host | CONTINUE / DEGRADED | Provider Transport |
| `PROVIDER_TIMEOUT` | RECOVERABLE | CONTINUE | Provider Transport |
| `PROVIDER_MALFORMED` | RECOVERABLE; SESSION_FATAL for a required source | CONTINUE / SESSION_STOP | Parser |
| `PARSER_SCHEMA_MISMATCH` | SESSION_FATAL | SESSION_STOP | Parser |
| `AUDIT_CHAIN_MISMATCH` | SECURITY_FATAL | SAFE_MODE | Audit |
| `AUDIT_SEQUENCE_GAP` | SECURITY_SUSPECTED | DEGRADED | Audit |
| `AUDIT_SIGNATURE_INVALID` | SECURITY_FATAL | SAFE_MODE | Audit |
| `AUDIT_MANIFEST_MISMATCH` | SECURITY_FATAL | SAFE_MODE | Audit |
| `AUDIT_ROLLBACK_SUSPECTED` | SECURITY_FATAL | SAFE_MODE | Audit |
| `AUDIT_WRITE_FAILED` | SESSION_FATAL | SESSION_STOP (automatic strategy fails closed); SAFE_MODE if persistent | Audit |
| `AUDIT_DIGEST_MISSED` | RECOVERABLE (WARNING) | CONTINUE | Scheduler / Audit |
| `AUDIT_DIGEST_FAILED` | RECOVERABLE (WARNING) | CONTINUE | Audit |
| `DIGEST_SCHEDULER_NOT_RUN` | RECOVERABLE (WARNING) | CONTINUE | Scheduler |
| `CHECKPOINT_SIGN_FAILED` | RECOVERABLE | **DEGRADED** (automatic strategy paused until signing recovers or the Human acknowledges; chain continues) — consistent with §51 | Audit |
| `SEGMENT_SEAL_FAILED` | RECOVERABLE (WARNING) | CONTINUE (live rows retained, §12.1) | Audit |
| `COMMITMENT_MISSING` | RECOVERABLE (technical) | DEGRADED until reconciled; never automatically SAFE_MODE (§9.4.4) | Audit |
| `COMMITMENT_MISMATCH` | SECURITY_SUSPECTED; SECURITY_FATAL inside a signed / anchored range | DEGRADED / SAFE_MODE | Audit |
| `ANCHOR_UPLOAD_FAILED` | RECOVERABLE | CONTINUE (anchor PENDING) | External Anchor |
| `APK_HASH_MISMATCH` | SECURITY_SUSPECTED — **raised by the independent verifier / out-of-band comparison only** (§30) | Human decision on the affected range | Provenance (verifier) |
| `BUILD_ID_MISMATCH` | RECOVERABLE (Human review) | DEGRADED until out-of-band provenance confirms the build; never self-authorized NORMAL | Provenance |
| `SIGNING_IDENTITY_CHANGED` | SECURITY_FATAL | SAFE_MODE | Provenance |
| `CONFIG_HASH_MISMATCH` | SECURITY_SUSPECTED | DEGRADED; SAFE_MODE inside a signed range | Provenance |
| `UNAUTHORIZED_ENDPOINT_ATTEMPT` | SESSION_FATAL + SECURITY_SUSPECTED | SESSION_STOP | Provider Transport |
| `UNAUTHORIZED_TR_ATTEMPT` | SESSION_FATAL + SECURITY_SUSPECTED | SESSION_STOP | Provider Transport |
| `UNEXPECTED_PERMISSION_CHANGE` | SECURITY_SUSPECTED | DEGRADED | Platform |
| `UNEXPECTED_CLOCK_JUMP` | SECURITY_SUSPECTED | DEGRADED | Platform |
| `TIMEZONE_CHANGED` | INFO | CONTINUE | Platform |
| `UNEXPECTED_TERMINATION` | No fixed class: starts `UNKNOWN`; postmortem refines it (§23). Fatal / crash categories → APP_FATAL; `OS_RECLAIMED` / `USER_STOPPED` / `DEVICE_REBOOT` → non-crash (INFO) unless other evidence escalates; `UNKNOWN` → WARNING | Recovery gate decides | Recovery |
| `REPEATED_CRASH_PATTERN` | APP_FATAL | DEGRADED (backoff) | Recovery |
| `CRASH_LOOP_DETECTED` | APP_FATAL | SAFE_MODE | Recovery |
| `CRASH_AFTER_INTEGRITY_VIOLATION` | SECURITY_FATAL | SAFE_MODE | Recovery |
| `DEVICE_RISK_INDICATOR` | DEVICE_RISK | Policy (HD-C4); record-only before Smoke | Platform |
| `STORAGE_PRESSURE` | RECOVERABLE (WARNING); SESSION_FATAL at CRITICAL | CONTINUE / SESSION_STOP | Storage |
| `STORAGE_FAILED` | APP_FATAL | SAFE_MODE | Storage |
| `EXPORT_BLOCKED_SECRET_SCAN` | RECOVERABLE | CONTINUE | AI Export |

## 46. Human Decision Table

HD-A0 … HD-A5 are **HUMAN APPROVED** by the Human and recorded in §46.1. All other rows are FUTURE HUMAN DECISIONS: **none of them is approved by this document.** The architecture may recommend; the Human decides. In particular this document does not choose: the physical storage layout or path beyond the approved HYBRID topology (HD-A1), the exact signing algorithm, the checkpoint interval, the anchor provider, retention limits, Safe Mode UX, the Alpha build profile, or the Alpha WebSocket / market-data transport.

| ID | Decision | Category | Resolution status | Notes |
| --- | --- | --- | --- | --- |
| HD-A0 | Sequencing of 12-S1 relative to the 12-B2 physical / provider Smoke | A. Before S1 | **HUMAN APPROVED** (§46.1) | Smoke before S1; reviewed B2 probe artifact with no S1 modifications (§48.1). Does not authorize S1 or Smoke |
| HD-A1 | Audit storage topology | A. Before S1 | **HUMAN APPROVED** (§46.1) | HYBRID: live chain in `bjstock.db`; sealed file-based segments in app-private storage; existing tables authoritative. Separate store as live primary chain NOT SELECTED. Exact path not frozen |
| HD-A2 | Commitment coverage, row classes, `commitment_coverage_start`, legacy classification, phase ownership | A. Before S1 / S2 | **HUMAN APPROVED** (§46.1) | §9.4.5, §9.4.7. Optional legacy baseline may be designed later |
| HD-A3 | Canonical serialization and hashing | A. Before S1 | **HUMAN APPROVED** (§46.1) | RFC 8785 JCS-compatible semantics + SHA-256 + domain separation; literal tags, library, decimal-scale values, and test vectors to freeze before coding / test-vector approval |
| HD-A4 | One error / reason registry | A. Before S1 | **HUMAN APPROVED** (§46.1) | `AppErrorCode` semantics preserved inside one versioned central registry (§45) |
| HD-A5 | Installation / boot / process identity | A. Before S1 | **HUMAN APPROVED** (§46.1) | Random pseudonymous UUID at GENESIS; boot count primary; optional supplementary boot id; process evidence tuple |
| HD-B1 | Signing primitive / Keystore API (candidate ECDSA P-256 + SHA-256) and hardware-backing requirement | B. During S1/S2 | OPEN | Independent security review required |
| HD-B2 | Checkpoint frequency / triggers | B. During S1/S2 | OPEN | |
| HD-B3 | Hourly digest timing and expected-slot policy | B. During S1/S2 (S3 implementation) | OPEN | Must respect B10 evidence |
| HD-B4 | Segment rollover thresholds; diagnostic / crash file size and count bounds | B. During S1/S2 | OPEN | |
| HD-B5 | Storage quotas and pressure thresholds | B. During S1/S2 | OPEN | |
| HD-B6 | Crash-loop thresholds (N, W, K) on the monotonic / boot-aware basis (§26) | B. During S1/S2 | OPEN | After evidence |
| HD-B7 | Safe Mode trigger thresholds and DEGRADED policy | B. During S1/S2 | OPEN | |
| HD-B8 | Clock-jump threshold; secret-fingerprint policy (if any) | B. During S1/S2 | OPEN | |
| HD-C1 | External anchor destination and trust model (must meet §16.1) | C. Before personal-phone Alpha | OPEN | Or an approved transitional substitute |
| HD-C2 | Anchor retry policy; certificate pinning | C. Before personal-phone Alpha | OPEN | |
| HD-C3 | Retention periods per tier | C. Before personal-phone Alpha | OPEN | ≥ 6-month study minimum is fixed |
| HD-C4 | Device-integrity signals policy (record-only vs enforce) | C. Before personal-phone Alpha | OPEN | Record-only before Smoke (§48) |
| HD-C5 | Key attestation use; public-key enrollment procedure (§14 explicit enrollment) | C. Before personal-phone Alpha | OPEN | |
| HD-C6 | Administrator UI scope; Safe Mode acknowledgement authentication UX | C. Before personal-phone Alpha | OPEN | |
| HD-C7 | Approval of the Alpha MUST list (§47) | C. Before personal-phone Alpha | OPEN | |
| HD-C8 | DB-wide encryption decision | C. Before personal-phone Alpha | OPEN | Deferred after Smoke (§48) |
| HD-C9 | **Personal-phone Alpha build profile** (S0-R-F13) | C. Before personal-phone Alpha | OPEN — **not Human-approved** | Recommended secure direction (not a decision): a separately signed **non-debuggable** Alpha build, distinct from the debug build that hosts the 12-B2 probe |
| HD-C10 | **Personal-phone Alpha market-data transport**, including WebSocket transport (S0-R-F14) | C. Before personal-phone Alpha | OPEN — **not Human-approved** | The bounded 12-B2 VIRTUAL Smoke cleartext acknowledgement does **not** extend to Alpha. Personal-phone Alpha cleartext: NOT APPROVED |
| HD-C11 | **Alpha credential / environment policy**, including the current `PRODUCTION` default of the selected KIS environment (S0-R-F15, §2.2) | C. Before personal-phone Alpha | **MUST RESOLVE before the Alpha credential policy** | No code change in this document |
| HD-D1 | AI export retention and storage location | D. Can wait until Beta | OPEN | |
| HD-D2 | Secure archive implementation (docs/150 §15) | D. Can wait until Beta | OPEN | |
| HD-D3 | Key rotation schedule | D. Can wait until Beta | OPEN | |
| HD-D4 | Play distribution / Play Integrity use | D. Can wait until Beta | OPEN | docs/161 group C |
| HD-E1 | Trading threat model, execution backend, trading credential isolation, real-money Risk Gate, kill switch, Human-confirmed pilot, production market-data security | E. Only before real-money trading | OPEN | §49 |

### 46.1 Human-Approved Architecture Decisions HD-A0 … HD-A5

All six decisions below are **HUMAN APPROVED**. Architecture approval of these decisions does **not** authorize: S1 implementation, DB migration, code changes, Smoke, 12-C, or real trading. Items explicitly listed as "left open" remain open implementation details and are not decided here.

| Decision | Status |
| --- | --- |
| HD-A0 Smoke before S1 | HUMAN APPROVED |
| HD-A1 Integrity storage topology | HUMAN APPROVED |
| HD-A2 Commitment coverage | HUMAN APPROVED |
| HD-A3 Canonical serialization / hashing | HUMAN APPROVED |
| HD-A4 One error / reason registry | HUMAN APPROVED |
| HD-A5 Installation / boot / process identity | HUMAN APPROVED |

#### HD-A0 — Smoke Before S1 (HUMAN APPROVED)

- **Decision:** the Phase 12-B2 physical / provider Smoke MUST occur before S1 changes are allowed to alter the artifact under measurement.
- **Preferred execution:** use the reviewed B2 probe artifact / build with **no** S1 modifications.
- **Rationale:** S1 may alter startup / recovery / runtime behaviour and would contaminate the Android Local / `specialUse` FGS / background-network feasibility measurement.
- **Frozen invariant:** the artifact used for B2 feasibility evidence must be traceable to the reviewed pre-S1 source state. No S1-modified artifact may silently become the B2 feasibility artifact.
- No S1 implementation before Smoke unless a separately isolated build proves it contains no S1 changes (§48.1).

#### HD-A1 — Integrity Storage Topology (HUMAN APPROVED)

- **Selected architecture: HYBRID.**
  - Live integrity-chain state: inside `bjstock.db`.
  - Sealed long-term audit segments: file-based immutable-style segments.
  - Domain / operational source of truth: existing tables remain authoritative.
- Conceptual structure: §8 (existing tables → same-transaction row commitment where possible → integrity chain in `bjstock.db` → signed checkpoint / chain head → sealed segment file → manifest / digest → future external anchor).
- **Frozen:**
  - existing domain / operational tables remain the **only** authoritative source of business and operational truth;
  - the integrity chain never becomes a second business ledger;
  - single logical chain-head writer;
  - stable row references;
  - atomic domain write + commitment where possible;
  - deterministic reconciliation where atomicity is impossible;
  - sealed segments visible to the independent verifier.
- Separate store as live primary chain: **NOT SELECTED**.
- **Sealed segment location:** app-private storage during Alpha unless a later Human decision explicitly chooses a different secure location; no broad shared-storage permission; future external archive / anchor is separate from local sealed-segment storage.
- Left open: the physical Android directory / path (not implementation-frozen; not defined in this document); the exact chain-table schema.

#### HD-A2 — Commitment Coverage (HUMAN APPROVED)

- **Immutable / event-like:** `executions`, `cash_ledger`, `trade_audit_logs`, `operational_events` — one canonical commitment per authoritative row / version as applicable.
- **Mutable / transition-based:** `orders`, `positions`, `forward_operations`, `strategy_runs` — one commitment per transition / version, conceptually with `row_identity`, `row_version`, `previous_commitment_hash`, `current_row_hash`, `transition_reason`, `source_correlation`.
- The current mutable row must match its latest valid commitment. No mutable projection may be represented as permanently immutable.
- **Phase ownership** (neither phase is implemented or authorized):
  - **12-S1:** canonical row-commitment framework; immutable / event-like target commitments; mutable transition commitment framework; completeness verification foundation.
  - **12-S2:** checkpoint / signing integration; sealed segment rollover; manifest; retention / deletion proof; commitment verification across sealed ranges.
- **Legacy coverage (frozen):** every commitment-covered table records `commitment_coverage_start`; rows predating coverage are classified `UNCOMMITTED_LEGACY` and never falsely classified as tampered; no rewriting of historical domain rows.
- Left open: an optional one-time baseline commitment / snapshot may be designed later.

#### HD-A3 — Canonical Serialization / Hashing (HUMAN APPROVED)

- **Serialization:** RFC 8785 JCS-compatible deterministic JSON semantics. **Hash:** SHA-256.
- **Requirements:** UTF-8; explicit schema version; deterministic field order through canonical serialization; exact null / missing semantics; fixed timestamp representation; fixed decimal-scale registry; fixed enum mapping; per-table canonical column mapping; no arbitrary ORM object serialization; no locale-dependent formatting; no silent Unicode normalization altering source meaning.
- **Domain separation:** explicit, with at minimum distinct domains for AUDIT_EVENT, ROW_COMMITMENT, AUDIT_SEGMENT, MANIFEST, CHECKPOINT, HOURLY_DIGEST, DAILY_DIGEST, BUILD_ID, CONFIG, STRATEGY, AI_EXPORT (§10).
- **Library rule:** prefer a standards-compliant JCS implementation rather than handwritten canonicalization, subject to Android compatibility and independent test-vector verification.
- **Test vectors:** before S1 implementation can be accepted, the Android implementation and the future independent verifier must produce identical canonical bytes and hashes from the same published test vectors, including `null`, empty string, Unicode, enum, negative integer, maximum allowed integer, decimal fixed-scale value, timestamp, nullable field, and an ordering-sensitive structure. No test vector is created now.
- Left open: exact literal domain-tag strings (to freeze before coding / test-vector approval); library selection; decimal-scale values.

#### HD-A4 — One Error / Reason Registry (HUMAN APPROVED)

- Do **not** create two independent semantic error systems. Existing `AppErrorCode` semantics are preserved and become part of one versioned central Error / Reason Code Registry (§45).
- **Model:** stable `reason_code` + `category` + severity / class + `automation_effect` + human-readable message. The human-readable message is **not** the semantic identifier.
- **Coverage:** existing operational / reliability errors; provider / network errors; parser errors; audit integrity errors; security suspicion; security fatal conditions; recovery errors; Safe Mode reasons.
- `ErrorCategory.SECURITY` and `SECURITY_SUSPECTED` / `SECURITY_FATAL` represent different concepts and are explicitly separated (no naming collision).
- Registry meaning cannot silently change between versions.
- Left open: the code-level mechanism (one enum vs a registry importing `AppErrorCode`).

#### HD-A5 — Installation / Boot / Process Identity (HUMAN APPROVED)

- **Installation identity:** a random pseudonymous UUID generated at audit GENESIS; not derived from IMEI, phone number, or Google account; no personal identifier; stored app-privately; referenced by audit / checkpoint / anchor. Reinstall semantics are explicit: new installation ⇒ new installation identity unless a future authenticated restore mechanism exists.
- **Boot identity:** primary logical evidence is the Android boot count where reliably available without broad permissions. Optional supplementary evidence: a kernel / platform boot identifier where safely and portably available. Optional boot-id access is **never** a hard dependency.
- **Process evidence** (conceptual): `pid`, `process_start_time`, `installation_id`, `boot_identity`, `operational_session_id` (the `session_id` field, §9.1).
- Monotonic time comparisons are valid only within an appropriate boot boundary.
- Left open: exact encoding of `boot_identity` and of the process evidence fields.

## 47. Alpha Security Minimum

PROPOSED ARCHITECTURE. This is a proposal for Human approval (HD-C7), not policy.

| Level | Control | Reasoning |
| --- | --- | --- |
| MUST | Least-privilege permissions + merged-manifest permission diff evidence | Real personal phone; cheapest, highest-value control |
| MUST | VIRTUAL credential separation; no PRODUCTION / REAL-TRADING credential; resolve the current `PRODUCTION` default of the selected KIS environment before the Alpha credential policy (HD-C11) | Approved probe boundary (A-3); no real-money exposure; a PRODUCTION default contradicts a VIRTUAL-first Alpha |
| MUST | Minimal recovery / integrity gate before any automatic operation (§29.1, S0-R-F05) | Automatic strategy must not silently resume when local integrity cannot be established |
| MUST | Safe Mode re-derived from durable state at every start; no automatic operation when the recovery result cannot be persisted (§25.1) | A restart must never clear a safety stop |
| MUST (decision) | Alpha build profile (HD-C9) and Alpha market-data transport (HD-C10) decided by the Human | Debug build and bounded-Smoke cleartext are not Alpha artifacts |
| MUST | Positive allowlist for every provider path / TR | The current deny-list guard is not a boundary (docs/161 §12) |
| MUST | Secret redaction in logs, audit, crash files, exports | Secrets on a personal phone must never leak |
| MUST | Structured error handling; removal of silent swallowing (including `runIgnoringFailure`) | Fail-Closed is impossible if failures are invisible |
| MUST | Failure containment per §27 + Fail-Closed automation | Prevents cascading failures and fake fills |
| MUST | Hash-chained audit with canonical serialization | Basic tamper evidence; detects modification / deletion / insertion / reordering |
| MUST | Crash recovery evidence (clean-shutdown marker, `UNEXPECTED_TERMINATION`, `ApplicationExitInfo`) | Distinguishes OS kills from defects; supports Crash ≠ Hack |
| MUST | Build / config provenance | Evidence is meaningless without knowing which code and config produced it |
| MUST | Bounded retention that keeps ≥ 6 months of study evidence | Approved study requirement; prevents unbounded growth |
| MUST | Audit-system failure is fail-closed (§51) | Never trade without evidence |
| MUST for "security-complete" | Off-device anchor or Human-approved transitional substitute | Only defence against full local rewrite / rollback |
| SHOULD | Signed checkpoints | Raises the bar for tampering; limited against a compromised process |
| SHOULD | Hourly digest with digest chain and auditor self-health | Human visibility; missed-digest evidence |
| SHOULD | Full nine-step recovery gate (§29) beyond the minimal gate (checkpoint / manifest / anchor comparison as those phases land) | Stronger startup assurance; the minimal gate is already MUST |
| SHOULD | Administrator status (minimal) | Routine health without raw JSON |
| CAN WAIT | Independent verifier (full) | Needed for 6-month analysis, not for day one; export keeps evidence verifiable later |
| CAN WAIT | AI export package | Needed after months of evidence |
| CAN WAIT | DB-wide encryption, Play Integrity, anti-debug enforcement, root refusal | Distort the probe or add dependencies (§48) |
| CAN WAIT | Key rotation schedule, secure archive | Low value early; Beta |

## 48. Controls That May Distort the Current Probe

PROPOSED ARCHITECTURE. Goal: maximum practical security without invalidating the Android foreground-service / network feasibility test (docs/161 §19, docs/162).

| Control | Classification | Reason |
| --- | --- | --- |
| DB-wide encryption migration | DEFER AFTER SMOKE | Business-DB migration risk; the probe does not touch the business DB (A-9); adds CPU / IO variables to timing measurements |
| Anti-debug / anti-hook enforcement | DEFER AFTER SMOKE | The probe runs as a debug build by design; enforcement would block or alter it. Passive indicator recording can be designed (S1) |
| Play Integrity dependency | DEFER AFTER SMOKE | Network + Play-services dependency changes the network profile; personal ADB-installed APK (A-10) |
| Forced root refusal | DEFER AFTER SMOKE | Could abort measurement; root indicators are suspicion signals only (record as DEVICE_RISK) |
| Background-service topology change | DEFER AFTER SMOKE | The Smoke measures exactly the approved topology (user-started `specialUse` FGS, A-2); changing it invalidates the measurement |
| Cloud executor | DEFER AFTER SMOKE | Option B fallback requires a Human topology decision after 12-B2 (docs/160 §27; F-23 secret custody) |
| VPN / tunnel | DEFER AFTER SMOKE | Alters the network path, latency, and policy behaviour under measurement |
| Aggressive watchdog | DEFER AFTER SMOKE | Restart loops distort process-survival evidence (M-11) and risk reconnect / token-issuance storms |

BEFORE SMOKE: no new control from the list above. The Smoke continues to rely on controls already required by docs/161 / docs/162 (positive allowlist A-5, VIRTUAL-only A-3, isolated evidence A-9, redaction, `/trading/` forbidden). The bounded-Smoke cleartext exception is HUMAN ACKNOWLEDGED for the bounded 12-B2 VIRTUAL Smoke only (§0; docs/162 §8 reconciled). This document adds no Smoke precondition.

### 48.1 Smoke vs S1 Sequencing Rule (S0-R-F18, FROZEN)

The 12-B2 runtime-feasibility evidence must come from the reviewed B2 probe artifact. Therefore exactly one of the following holds:

1. **Smoke first:** the 12-B2 physical Smoke runs before any 12-S1 change is made to the build; or
2. **Smoke from the reviewed B2 probe build without S1 changes:** if S1 work starts earlier, the Smoke APK is built from the reviewed B2 probe source (recorded Git HEAD and APK SHA-256, docs/162 §7) with no S1 changes included.

S1 changes must never silently alter the artifact used to establish B2 runtime feasibility.

**HD-A0 (HUMAN APPROVED, §46.1) selects option 1, Smoke before S1**, using the reviewed B2 probe artifact / build with no S1 modifications. Option 2 remains only as the exception HD-A0 allows: S1 implementation before Smoke is not permitted unless a separately isolated build proves it contains no S1 changes. The B2 feasibility artifact must be traceable to the reviewed pre-S1 source state. HD-A0 does not itself authorize the Smoke or S1.

## 49. Real-Money Future Boundary

APPROVED HUMAN REQUIREMENT (restated): **the current architecture does NOT authorize real trading.** BJStock remains PAPER TRADING ONLY.

Separate future gates are required for: Trading Threat Model; Execution Backend; Trading Credential Isolation; real-money Risk Gate; Kill Switch; Human-confirmed real-money pilot; production market-data security (docs/150 §19 real-money stages). Nothing in docs/163 — signing keys, anchors, admin UI — is a step toward those gates. KIS `/trading/` remains prohibited.

## 50. Independent Verifier

PROPOSED ARCHITECTURE. Implementation later (S5).

- A separate program, not part of the BJStock runtime (candidate: desktop CLI run by the Human), that does not trust any runtime "PASS" flag.
- Trust inputs obtained out of band: audit public keys per epoch from explicit enrollment (§14; pre-enrollment history reported as TOFU / UNENROLLED), anchor records read directly from the anchor store (§16.1), and expected artifact identities including APK SHA-256 from build evidence (§30).
- Verifies: audit sequence continuity; hash chain; segment rollover links and sealing metadata (§12.1); segment file hashes; manifest chain; checkpoint / manifest / digest signatures; digest chain and missed-digest records; external anchors (including conflicting-head findings); row-commitment completeness and mutable-row transition chains within each table's `commitment_coverage_start` (§9.4); build / config provenance, including the out-of-band APK hash comparison that alone may raise `APK_HASH_MISMATCH`; AI export integrity (§41).
- Output: a verification report per range with findings expressed as registry reason codes. It shares the canonical-serialization test vectors with the runtime (§10).

## 51. Failure of the Audit System

PROPOSED ARCHITECTURE.

| Failure | Behaviour |
| --- | --- |
| Cannot write audit | Abort the triggering transaction (domain change does not commit without its audit); `AUDIT_WRITE_FAILED`; automatic strategy FAIL CLOSED; persistent failure → SAFE_MODE |
| Cannot sign checkpoint | Chain continues (hash chain still valid); `CHECKPOINT_SIGN_FAILED` (taxonomy class RECOVERABLE, automation effect DEGRADED, §45.2); automatic strategy paused until signing recovers or the Human acknowledges. Before S2 (no signing key yet) this row does not apply |
| Cannot persist the recovery / integrity result | No automatic strategy operation starts (§25.1) |
| Manifest inconsistent | `AUDIT_MANIFEST_MISMATCH` → SAFE_MODE |
| Digest creation failure | `AUDIT_DIGEST_FAILED`; WARNING; retry per policy; does not stop audit |
| Storage exhausted | `STORAGE_FAILED` → SAFE_MODE; no deletion of unanchored authoritative audit |
| Anchor unavailable | May continue under a defined degraded policy: `anchor_state = PENDING`, WARNING; local audit and digest unaffected |

Distinction: **anchor unavailable** may continue under a degraded policy; **audit integrity unavailable** means automatic strategy FAILS CLOSED. "Logging failed → silently continue trading" is never allowed.

## 52. Architecture Diagrams

PROPOSED ARCHITECTURE.

**A. Runtime Event → Audit Chain → Signed Checkpoint**

```
component event ──► classify (taxonomy + reason code) ──► redact / allowlist fields
      │
      ▼
 single writer, Room transaction (with the domain change when applicable)
      │   audit_sequence = head + 1
      │   previous_event_hash = head.event_hash
      │   event_hash = SHA-256(domain_tag ‖ JCS(event))
      ▼
 chain head ──(trigger: rollover / hourly / clean close / Safe Mode / export)──►
      checkpoint {sequence, head hash, manifest hash, build, config, key epoch}
      └──► Keystore sign (non-exportable key) ──► AUDIT_CHECKPOINT_SIGNED
```

**B. Audit Segment → Manifest → Hourly Digest → External Anchor**

```
live segment ──rollover──► sealed segment {range, boundary hashes, segment_sha256}
                                │
                                ▼
                 manifest v(n) {segments[], tombstones[], previous_manifest_hash} ──► signed
                                │
                                ▼
          hourly digest {coverage, counts, validations, previous_digest_hash} ──► signed (offline)
                                │
                     network? ──┴── no ──► anchor_state = PENDING (queue G, retry later)
                                │
                               yes ──► TLS upload of minimal payload ──► ANCHORED
```

**C. Crash → Next-start Recovery → Classification → Safe Mode**

```
process ends (crash, OS reclaim, user stop, reboot, ...) ──► (crash only, best effort) redacted crash file, no audit write
        │
next start ──► recovery gate
        │   no SESSION_CLOSED_CLEANLY? ──► UNEXPECTED_TERMINATION (classification = UNKNOWN)
        │                                  (a missing clean close is NOT a crash by itself)
        │   read ApplicationExitInfo (pid / process_start_time / boot_identity), chain head,
        │   checkpoint, manifest, digest, anchor, build / config identity, unfinished operations
        ▼
 postmortem classification:
   CRASH │ CRASH_NATIVE │ ANR │ INITIALIZATION_FAILURE │ RECOVERY_GATE_FAILED ──► fatal / crash (crash_count, §26)
   OS_RECLAIMED │ USER_STOPPED │ DEVICE_REBOOT                                ──► non-crash termination
   no matching evidence                                                        ──► stays UNKNOWN
   + §22 integrity correlation (evidence-driven security classification only)
        ▼
 NORMAL ──► resume │ DEGRADED ──► automation paused, Human informed │ SAFE_MODE ──► stop, preserve, explain, await acknowledgement
```

**D. 6-month Evidence → Integrity Verification → AI Export → AI Analysis**

```
segments + manifests + checkpoints + digests + anchors + provenance
        │
        ▼
 integrity verification (runtime pre-check, then independent verifier)
        │
        ▼
 Human scope choice ──► secret / PII scan ──► export package + export_manifest + integrity_report
        │                                      (DERIVED marked, source ranges referenced)
        ▼
 AI analysis (performance, regimes, reliability, crashes, anomalies, config / build changes)
```

## 53. Open Questions

| # | Question |
| --- | --- |
| Q-1 | Exact signing primitive and Keystore API, hardware backing (TEE / StrongBox), attestation use |
| Q-2 | Signing frequency vs battery / IO cost |
| Q-3 | Key rotation schedule and cross-epoch verification procedure |
| Q-4 | External anchor implementation, operator, and trust model |
| Q-5 | Reinstall / key-loss semantics and how the Human proves continuity across installations |
| Q-6 | Storage quotas on the actual device |
| Q-7 | Retention periods per tier |
| Q-8 | Safe Mode UX and acknowledgement flow |
| Q-9 | Verifier implementation language and distribution |
| Q-10 | Device-integrity policy (record-only vs enforce, which indicators) |
| Q-11 | Secure archive (docs/150 §15) interplay with segment sealing |
| Q-12 | AI export privacy policy and retention outside the device |
| Q-13 | Physical chain-table schema and sealed-segment path under the approved HYBRID topology (HD-A1), and how they coexist with docs/160 §0.2 intraday audit ownership |
| Q-14 | Digest generation trigger under the B10 background constraints (in-process scheduler vs lazy catch-up) |

## 54. Phased Implementation Plan

Architecture only. **No implementation authority** for any phase; each phase requires its own Human-approved execution instruction and independent review.

| Phase | Scope |
| --- | --- |
| 12-S0 | This architecture — APPROVED / CLOSED (architecture only; final independent verification PASS) |
| 12-S1 Local Audit Core | Canonical events and serialization with test vectors (HD-A3); hash chain with live chain state in `bjstock.db` (HD-A1); per HD-A2: canonical row-commitment framework, immutable / event-like target commitments, mutable transition commitment framework, completeness verification foundation; one central error / reason registry (HD-A4); installation / boot / process identity (HD-A5); crash recovery evidence (clean-shutdown marker, `UNEXPECTED_TERMINATION` with `UNKNOWN` initial classification, `ApplicationExitInfo` correlation); minimal recovery / integrity gate (§29.1) and durable Safe Mode re-derivation (§25.1); runtime-observed build identity and config provenance; replacement of silent swallowing at startup. HD-A0 … HD-A5 are HUMAN APPROVED, but S1 still requires: the 12-B2 Smoke first (HD-A0, §48.1), the HD-A3 freeze items (literal tags, library, decimal scales, published test vectors), and its own Human-approved execution instruction |
| 12-S2 Signed Checkpoints / Manifest / Rotation | Checkpoint / signing integration (Keystore signing key, HD-B1); sealed segment rollover (file-based, app-private, HD-A1) and manifest; retention / deletion proof and storage-pressure handling; commitment verification across sealed ranges (HD-A2) |
| 12-S3 Hourly Digest / Admin Status | Digest scheduler (offline); digest chain; missed-digest audit; administrator health view with drill-down |
| 12-S4 Off-device Anchor | TLS upload; pending queue; retry; anchor verification |
| 12-S5 Independent Verifier / AI Export | Verifier; integrity report; export bundle with scans and DERIVED marking |

## 55. Consistency With docs/150, docs/160, docs/161, docs/162

| Document | Consistency |
| --- | --- |
| docs/150 | Reuses `ErrorCategory` / `ErrorSeverity` / `RetryPolicy` / `OperationAction`, the prohibited-catch list, three log layers, same-transaction audit atomicity (§11), idempotency (§12), lifecycle and retention matrix (§13–§14), archive manifest (§15–§16), `SafeLogText` (§17), incident lifecycle (§18), real-money stages (§19). Adds an integrity layer and a top-level taxonomy (mapping HD-A4); closes the recorded `runIgnoringFailure` gap in S1 |
| docs/160 | Fail-Closed and MISSING-never-zero preserved; audit requirement preserved and extended without a parallel domain-audit subsystem (§8, HD-A1); no retroactive trades and no fake fills (§19, §27); KIS `/trading/` forbidden; cadence is a Run-snapshot property with 15 minutes as default only (§32); Option A still unproven; Android foreground tested first, topology undecided; 12-C NOT AUTHORIZED |
| docs/161 | Positive allowlist (A-5), VIRTUAL-only probe (A-3), isolation (A-9), user actions (A-10) preserved; Android 17 MemoryLimiter and `ApplicationExitInfo` (§15.3, M-11) used for crash classification; Option A and Android Local remain POSSIBLY FEASIBLE — NEEDS 12-B2 PROBE |
| docs/162 | Probe untouched; 12-B2 NOT PASS; cleartext exception is ONLY for the bounded Smoke — HUMAN ACKNOWLEDGED for the bounded 12-B2 VIRTUAL Smoke only, now also recorded in docs/162 §8 (metadata reconciled, §0; probe design and endpoints unchanged); personal-phone Alpha cleartext NOT APPROVED (HD-C10); PINGPONG UNVERIFIED; physical Smoke NOT STARTED; probe evidence stays separate from file classes A–H; Smoke artifact protected by the sequencing rule (§48.1) |

Preserved invariants: Fail-Closed; audit requirement; no retroactive / fake fills; KIS `/trading/` forbidden; VIRTUAL-only current probe; Option A unproven; Android Local unproven; cleartext approval only for the bounded Smoke; PINGPONG unverified; physical Smoke not started.

## 56. Source / Evidence Discipline

- CURRENT REPOSITORY FACT is limited to §2 and rows explicitly labelled so; everything else describing audit chains, checkpoints, manifests, digests, anchors, Safe Mode, the recovery gate, the verifier, and exports is PROPOSED ARCHITECTURE and **does not exist**.
- APPROVED HUMAN REQUIREMENTS are cited to docs/160, docs/161 §21.1, or this gate's directive.
- FUTURE HUMAN DECISIONS are listed in §46 and are not approved by this document. HD-A0 … HD-A5 are HUMAN APPROVED by the Human and only recorded here (§46.1); they authorize no implementation.
- Cryptographic guarantees are stated with their limitations (§11, §15, §16). No control is described as preventing tampering by a privileged attacker or a compromised process.

## 57. Glossary

| Term | Definition |
| --- | --- |
| Audit | The authoritative, append-only, canonical, hash-chained evidence trail of security, operational, and (via row commitments) domain events; independently verifiable |
| Diagnostic Log | Sanitized, rotatable troubleshooting output (including crash files); never authoritative |
| Checkpoint | A signed statement of the audit chain head (sequence and hash), manifest state, and provenance at a point in time |
| Anchor | An off-device, append-only record of a checkpoint / digest hash, used to detect later local rewrites and rollbacks |
| Digest | An hourly or daily signed summary of audit coverage, counts, validations, and provenance, chained to the previous digest |
| Manifest | The chained, signed description of the full set of audit segments, including tombstones for purged ranges |
| Safe Mode | A state in which automatic strategy and order flow stop, evidence is preserved, reconnects are suppressed, and exit requires explicit Human acknowledgement |
| Security Suspected | A classification meaning integrity *may* be compromised based on reason-code evidence; not proof; triggers review and degraded automation |
| Security Fatal | A classification meaning an integrity check failed so evidence or state cannot be trusted; triggers Safe Mode |
| Provenance | The binding of evidence to build, configuration, strategy, cadence, and universe identities |
| Chain Head | The latest audit event (highest `audit_sequence`) and its `event_hash` |
| Row Commitment | A chain record binding an authoritative domain / operational row (immutable) or row version (mutable) to its canonical hash; it verifies the row and never replaces it as the source of truth |
| Session | A BJStock operational session identified by `session_id` (for example a user-started probe / operation session, an automation operation session, or a strategy runtime session); not synonymous with an Android process lifetime, which is represented by `process_ref` |
| Installation identity | Random pseudonymous UUID generated at audit GENESIS (HD-A5); not derived from any hardware or personal identifier |
| Boot identity | Boot boundary for monotonic time (HD-A5): Android boot count where reliably available, optionally supplemented by a kernel / platform boot identifier |
| TOFU | Trust on first use: history signed before explicit public-key enrollment, reported as unenrolled rather than verified-signed |

---

PAPER TRADING ONLY. No real brokerage order capability. No production trading credential use. KIS `/trading/` remains forbidden. Phase 12-S0: APPROVED / CLOSED (architecture only). ImplementationAuthority: NONE.
