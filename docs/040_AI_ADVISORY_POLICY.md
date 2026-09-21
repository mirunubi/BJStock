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
- Whether AI was used must be recorded

## Decision Path

```text
Quant Decision ─┐
                ├─ Decision Record
AI Advice ──────┘
```

The paper-trading path follows the quant decision.

AI advice may be attached to the same decision record for later comparison. AI output must never become a live or paper order by itself.

## Required Records

When AI is used, persist at least:

- Provider
- Model
- Prompt version
- Request payload reference
- Result
- AI-used flag

When AI is off, the app must still complete factor scoring, strategy evaluation, and paper trading.

## Non-Goals

- AI-driven live trading
- Hidden model calls without storage
- Hard-coded single-vendor AI
