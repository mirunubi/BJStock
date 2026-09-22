# BJStock AI Advisory Policy

AI is an optional advisor. It is not the trading engine.

## Policy

- AI is optional
- AI can be turned OFF
- Quant judgment and AI judgment are separated
- AI must not trade directly
- Provider must be replaceable
- Model must be recorded
- Prompt version must be recorded
- AI result must be stored
- Whether AI was used must be recorded (`used_in_decision`; Phase 8 always false)

## Phase 8 Modes

- `OFF` (default)
- `CHATGPT_MANUAL` (prompt export / response paste; no API key)
- `OPENAI_API_FUTURE` (architecture placeholder; no network)

## Decision Path

```text
Quant Decision ─┐
                ├─ Decision Record (final_decision = quant_decision)
AI Advice ──────┘  (advisory metadata only)
```

The paper-trading path follows the quant decision only.

## Required Records

When AI is used, persist at least:

- Provider
- Model
- Prompt version
- Request payload (includes fingerprint)
- Result
- AI-used flag (`used_in_decision`)

When AI is off, the app must still complete factor scoring, strategy evaluation, and paper trading.

## Non-Goals

- AI-driven live trading
- Hidden model calls without storage
- Hard-coded single-vendor AI
- OpenAI secrets inside the Android APK
- Direct on-device OpenAI API calls

See also `docs/130_AI_ADVISORY.md` and `docs/131_AI_PROMPT_CONTRACT.md`.
