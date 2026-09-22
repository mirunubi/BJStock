# AI Prompt Contract

## Prompt Version

```text
BJSTOCK_AI_ADVISORY_V1
```

Changing wording/content requires `V2`, `V3`, … — do not mutate V1 in place.

## Input Payload (conceptual)

```text
evaluation_id
evaluation_date
instrument symbol / name / market / board
strategy code / name / version
quant_score
quant_decision
factor rows: code, raw, score, weight, contribution
daily bars with trade_date <= evaluation_date (max 60)
paper trading policy summary (simulation assumption rates)
role + output contract instructions
request_fingerprint (SHA-256 of canonical body without circular dependency)
```

## Output JSON Contract

```json
{
  "schema_version": "1",
  "request_fingerprint": "<sha256>",
  "stance": "BUY|HOLD|SELL|UNCERTAIN",
  "confidence": 0,
  "summary": "string",
  "supporting_reasons": ["string"],
  "risk_factors": ["string"]
}
```

### Field rules

| Field | Rule |
| --- | --- |
| schema_version | required `"1"` |
| request_fingerprint | required; must equal request fingerprint |
| stance | BUY / HOLD / SELL / UNCERTAIN only |
| confidence | integer 0..100 (self-reported, not a probability claim) |
| summary | required non-empty string |
| supporting_reasons | required string array |
| risk_factors | required string array |

### Rejection codes

| Kind | When |
| --- | --- |
| `INVALID_RESPONSE_FORMAT` | malformed JSON / missing fields / bad stance / bad confidence |
| `REQUEST_MISMATCH` | fingerprint mismatch |
| `RESULT_ALREADY_EXISTS` | second result for same request |
| `AI_DISABLED` | mode OFF |

Rejected responses are not written to `ai_advice_results`.

## Storage mapping

| Contract | Column |
| --- | --- |
| stance | `ai_advice_results.recommendation` (`UNCERTAIN` → `NO_OPINION`) |
| confidence | `confidence` Long × 10_000 of 0..1 |
| summary | `summary` |
| supporting_reasons | JSON array in `reasoning_summary` |
| risk_factors | JSON array in `risk_notes` |
| raw JSON | `raw_response` |
| fingerprint | inside `ai_advice_requests.request_payload` |

## Agreement (UI only)

```text
Quant Decision vs AI Stance → AGREE | DISAGREE | UNCERTAIN
```

Agreement never adjusts scores or creates/cancels orders.
