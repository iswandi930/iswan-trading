from __future__ import annotations

import os
from urllib.parse import quote

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from analysis_engine import analyze_closes

app = FastAPI(title="Iswan Trading Twelve Data Market Engine", version="0.3.0")

TWELVE_DATA_URL = "https://api.twelvedata.com"
TWELVE_DATA_KEY = os.getenv("TWELVE_DATA_API_KEY", "")

# App-facing symbols. Provider symbols are mapped below so the Android UI can
# keep familiar MT4-style names such as XAUUSD and EURUSD.
SYMBOL_MAP: dict[str, str] = {
    "XAUUSD": "XAU/USD",
    "EURUSD": "EUR/USD",
    "GBPUSD": "GBP/USD",
    "USDJPY": "USD/JPY",
    "USDCHF": "USD/CHF",
    "AUDUSD": "AUD/USD",
    "USDCAD": "USD/CAD",
    "NZDUSD": "NZD/USD",
    "EURGBP": "EUR/GBP",
    "EURJPY": "EUR/JPY",
    "GBPJPY": "GBP/JPY",
    "AUDJPY": "AUD/JPY",
    "EURAUD": "EUR/AUD",
    "EURCHF": "EUR/CHF",
    "GBPCHF": "GBP/CHF",
    "AUDCAD": "AUD/CAD",
    "AUDCHF": "AUD/CHF",
    "CADJPY": "CAD/JPY",
    "CHFJPY": "CHF/JPY",
    "NZDJPY": "NZD/JPY",
    "NZDCHF": "NZD/CHF",
    "USOIL": "WTI/USD",
    "UKOIL": "BRENT/USD",
    "BTCUSD": "BTC/USD",
    "ETHUSD": "ETH/USD",
    "AAPL": "AAPL",
    "MSFT": "MSFT",
    "NVDA": "NVDA",
    "AMZN": "AMZN",
    "META": "META",
    "TSLA": "TSLA",
    "SPY": "SPY",
    "QQQ": "QQQ",
}


def _provider_symbol(symbol: str) -> str:
    key = symbol.upper().strip()
    if key not in SYMBOL_MAP:
        raise HTTPException(status_code=404, detail=f"Unsupported Twelve Data symbol: {key}")
    return SYMBOL_MAP[key]


def _get(path: str, params: dict[str, str]) -> dict:
    if not TWELVE_DATA_KEY:
        raise HTTPException(status_code=503, detail="Twelve Data API key is not configured on the server")
    params = dict(params)
    params["apikey"] = TWELVE_DATA_KEY
    try:
        with httpx.Client(timeout=10.0) as client:
            response = client.get(TWELVE_DATA_URL + path, params=params)
            response.raise_for_status()
            data = response.json()
    except httpx.HTTPStatusError as exc:
        raise HTTPException(status_code=502, detail=f"Twelve Data HTTP error: {exc.response.text[:500]}") from exc
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Twelve Data connection error: {exc}") from exc
    if isinstance(data, dict) and data.get("status") == "error":
        raise HTTPException(status_code=502, detail=f"Twelve Data API error: {data.get('message', 'unknown error')}")
    return data


class Candle(BaseModel):
    time: int
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0


class MarketQuote(BaseModel):
    symbol: str
    price: float
    changePercent: float | None = None
    marketTime: int | None = None


class AnalysisRequest(BaseModel):
    symbol: str = Field(min_length=1, max_length=32)
    candles: list[Candle] = Field(min_length=1, max_length=5000)


class AnalysisResponse(BaseModel):
    symbol: str
    signal: str
    confidence: int
    trend: str
    rsi: float | None


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "service": "iswan-twelve-data-engine",
        "twelve_data": "configured" if TWELVE_DATA_KEY else "not_configured",
    }


def _timestamp(value: str | int | float | None) -> int | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return int(value * 1000 if value < 10_000_000_000 else value)
    try:
        from datetime import datetime
        return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)
    except (ValueError, TypeError):
        return None


@app.get("/v1/quotes", response_model=list[MarketQuote])
def quotes(symbols: str) -> list[MarketQuote]:
    requested = [s.strip().upper() for s in symbols.split(",") if s.strip()]
    if not requested:
        return []
    result: list[MarketQuote] = []
    # Twelve Data's quote endpoint is intentionally called once per symbol:
    # this keeps provider errors isolated and preserves the Android symbol names.
    for symbol in requested:
        provider = _provider_symbol(symbol)
        data = _get("/quote", {"symbol": provider})
        price = data.get("close")
        if price is None:
            continue
        previous = data.get("previous_close")
        change = data.get("percent_change")
        change_percent = float(change) if change is not None else None
        if change_percent is None and previous not in (None, 0, "0"):
            change_percent = (float(price) - float(previous)) / float(previous) * 100.0
        result.append(MarketQuote(
            symbol=symbol,
            price=float(price),
            changePercent=change_percent,
            marketTime=_timestamp(data.get("timestamp") or data.get("datetime")),
        ))
    return result


@app.get("/v1/candles", response_model=list[Candle])
def candles(symbol: str, timeframe: str = "5min", limit: int = 160) -> list[Candle]:
    symbol = symbol.upper().strip()
    provider = _provider_symbol(symbol)
    limit = max(21, min(limit, 5000))
    allowed = {"1min", "5min", "15min", "30min", "45min", "1h", "2h", "4h", "1day", "1week"}
    if timeframe.lower() not in allowed:
        raise HTTPException(status_code=400, detail=f"Unsupported timeframe: {timeframe}")
    data = _get("/time_series", {
        "symbol": provider,
        "interval": timeframe.lower(),
        "outputsize": str(limit),
        "format": "JSON",
    })
    rows = data.get("values", [])
    candles_out: list[Candle] = []
    for row in reversed(rows):
        try:
            candles_out.append(Candle(
                time=_timestamp(row.get("datetime")) or 0,
                open=float(row["open"]),
                high=float(row["high"]),
                low=float(row["low"]),
                close=float(row["close"]),
                volume=float(row.get("volume", 0.0) or 0.0),
            ))
        except (KeyError, TypeError, ValueError):
            continue
    return candles_out


@app.post("/v1/analyze", response_model=AnalysisResponse)
def analyze(request: AnalysisRequest) -> AnalysisResponse:
    result = analyze_closes([c.close for c in request.candles])
    return AnalysisResponse(symbol=request.symbol.upper(), signal=result.signal, confidence=result.confidence, trend=result.trend, rsi=result.rsi)
