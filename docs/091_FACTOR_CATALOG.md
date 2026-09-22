# Factor Catalog

Phase 4 system factors. Score mapping is a baseline, not a final investment rule.

History counts are Room trading-day rows on or before `asOfDate`.

| Code | Name | Category | Value Type | History | Formula | Normalizer | Direction | Version |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PRICE_VS_MA20 | Price vs MA20 | TECHNICAL | PERCENT | 20 | (close / MA20 - 1) × 100 | Linear −20→0, 0→50, +20→100, clamp | higher is better | v1 |
| PRICE_VS_MA60 | Price vs MA60 | TECHNICAL | PERCENT | 60 | (close / MA60 - 1) × 100 | Linear −30→0, 0→50, +30→100, clamp | higher is better | v1 |
| MOMENTUM_20D | Momentum 20D | MOMENTUM | PERCENT | 21 | (close / close20DaysAgo - 1) × 100 | Linear −20→0, 0→50, +20→100, clamp | higher is better | v1 |
| MOMENTUM_60D | Momentum 60D | MOMENTUM | PERCENT | 61 | (close / close60DaysAgo - 1) × 100 | Linear −30→0, 0→50, +30→100, clamp | higher is better | v1 |
| VOLATILITY_20D | Volatility 20D | TECHNICAL | PERCENT | 21 | population stdev of 20 daily returns × 100. Not annualized. Daily return = close_t / close_t-1 − 1 | Linear 0%→100, 5%→50, 10%→0, clamp | lower is better | v1 |
| VOLUME_RATIO_20D | Volume Ratio 20D | VOLUME | RATIO | 21 | currentVolume / average(previous 20 volumes). Current day is excluded from the average. Zero average → INVALID_DATA | Piecewise 0.5→20, 1.0→50, 2.0→80, 3.0→100, clamp outside | higher is better | v1 |

PER, PBR, ROE, and other financial statement factors are not in this catalog. No trusted financial data source is wired yet.
