from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence


@dataclass(frozen=True)
class Analysis:
    signal: str
    confidence: int
    trend: str
    rsi: float | None


def ema(values: Sequence[float], period: int) -> float:
    if len(values) < period:
        raise ValueError(f"need at least {period} values")
    k = 2.0 / (period + 1)
    value = sum(values[:period]) / period
    for price in values[period:]:
        value = price * k + value * (1.0 - k)
    return value


def rsi(values: Sequence[float], period: int = 14) -> float | None:
    if len(values) <= period:
        return None
    gains = 0.0
    losses = 0.0
    for i in range(1, period + 1):
        delta = values[i] - values[i - 1]
        if delta >= 0:
            gains += delta
        else:
            losses -= delta
    gains /= period
    losses /= period
    for i in range(period + 1, len(values)):
        delta = values[i] - values[i - 1]
        gains = (gains * (period - 1) + max(delta, 0.0)) / period
        losses = (losses * (period - 1) + max(-delta, 0.0)) / period
    if losses == 0:
        return 100.0
    return 100.0 - 100.0 / (1.0 + gains / losses)


def analyze_closes(closes: Sequence[float]) -> Analysis:
    if len(closes) < 21:
        return Analysis("NEUTRAL", 0, "N/A", None)
    ema9 = ema(closes, 9)
    ema21 = ema(closes, 21)
    sma20 = sum(closes[-20:]) / 20.0
    current_rsi = rsi(closes, 14)
    bullish = ema9 > ema21 and closes[-1] > sma20 and (current_rsi is None or current_rsi < 70)
    bearish = ema9 < ema21 and closes[-1] < sma20 and (current_rsi is None or current_rsi > 30)
    signal = "BUY" if bullish else "SELL" if bearish else "NEUTRAL"
    checks = [ema9 > ema21, closes[-1] > sma20, current_rsi is not None and current_rsi > 50]
    confidence = sum(checks) * 25
    trend = "BULLISH" if ema9 >= ema21 else "BEARISH"
    return Analysis(signal, confidence, trend, current_rsi)
