# AI Advisory Foundation

Phase 8 adds an optional **advisory-only** AI path beside Quant decisions.

## Advisory Only

AI never:

- places live or paper orders
- changes positions / cash
- mutates quant scores, weights, factor scores, or `final_decision`

```text
final_decision = quant_decision   (unchanged in Phase 8)
```

PaperTradingEngine does not read `ai_advice_*` tables.

## Modes

| Mode | Status |
| --- | --- |
| `OFF` | Default. Request creation disabled. |
| `CHATGPT_MANUAL` | BJStock builds a prompt; user pastes into ChatGPT and pastes JSON back. No API key. |
| `OPENAI_API_FUTURE` | Architecture placeholder only. No network implementation. |

`CHATGPT_MANUAL` is **not** ChatGPT Plus login inside BJStock. BJStock and ChatGPT remain independent apps.

## Manual Workflow

```text
Generate Prompt → Copy / Share → ChatGPT → Paste JSON → Validate → Save Advice
Ask Again → new request (same evaluation allowed)
```

Android uses ClipboardManager and `ACTION_SEND`. No hard dependency on a ChatGPT package name.

## Prompt Rules

- Built from immutable `stock_evaluations` / `stock_evaluation_details` snapshots
- Market bars only with `trade_date <= evaluationDate` (max 60)
- No future outcomes / future executions / future snapshots
- Prompt versioned: initial `BJSTOCK_AI_ADVISORY_V1`
- Deterministic SHA-256 `request_fingerprint` over the canonical prompt body
- No secrets, PII, KIS keys, tokens, or account numbers

## Result Rules

- One result per request (`UNIQUE request_id`)
- Do not update an existing result — create a new request
- Stance `UNCERTAIN` stores as existing enum `NO_OPINION`
- Confidence 0..100 in JSON; stored as 0..1 × `CONFIDENCE_FACTOR` (not written to `ai_score`)
- `used_in_decision = false` always in Phase 8

## Schema

Existing `ai_advice_requests` / `ai_advice_results` are sufficient.
Fingerprint is embedded in `request_payload` JSON (no new column).

Room remains version **5**. No PostgreSQL migration.

## Future Gateway

When automated OpenAI access is added later:

```text
Android → BJStock Backend Gateway → OpenAI API
```

Never:

```text
Android → OpenAI API with secret key in APK
```

Prefer OpenAI Responses API + structured JSON schema. Do not hard-code a model ID in Phase 8.

Billing for ChatGPT Manual (subscription) must not be confused with future API billing.
