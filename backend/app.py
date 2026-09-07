from __future__ import annotations

import os
from urllib.parse import quote

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from analysis_engine import analyze_closes

app = FastAPI(title="Iswan Trading Alpaca Market Engine", version="0.2.0")

ALPACA_DATA_URL = "https://data.alpaca.markets"
ALPACA_KEY = os.getenv("ALPACA_API_KEY", "")
ALPACA_SECRET = os.getenv("ALPACA_API_SECRET", "")
ALPACA_FEED = os.getenv("ALPACA_STOCK_FEED", "iex")

# Only instruments supported by Alpaca's public market-data APIs are exposed here.
STOCK_SYMBOLS = {"AAPL", "MSFT", "NVDA", "AMZN", "META", "TSLA", "SPY", "QQQ"}
CRYPTO_SYMBOLS = {"BTCUSD", "ETHUSD"}


def _headers() -> dict[str, str]:
    return {"APCA-API-KEY-ID": ALPACA_KEY, "APCA-API-SECRET-KEY": ALPACA_SECRET}


def _require_credentials() -> None:
    if not ALPACA_KEY or not ALPACA_SECRET:
        raise HTTPException(status_code=503, detail="Alpaca credentials are not configured on the server")


def _kind(symbol: str) -> str:
    symbol = symbol.upper()
    if symbol in STOCK_SYMBOLS:
        return "stock"
    if symbol in CRYPTO_SYMBOLS:
        return "crypto"
    raise HTTPException(status_code=404, detail=f"Unsupported Alpaca symbol: {symbol}")


def _crypto_symbol(symbol: str) -> str:
    return {"BTCUSD": "BTC/USD", "ETHUSD": "ETH/USD"}[symbol.upper()]


def _get(path: str, params: dict[str, str] | None = None) -> dict:
    _require_credentials()
    try:
        with httpx.Client(timeout=8.0) as client:
            response = client.get(ALPACA_DATA_URL + path, params=params, headers=_headers())
            response.raise_for_status()
            return response.json()
    except httpx.HTTPStatusError as exc:
        detail = exc.response.text[:500]
        raise HTTPException(status_code=502, detail=f"Alpaca API error: {detail}") from exc
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Alpaca connection error: {exc}") from exc


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
    return {"status": "ok", "service": "iswan-alpaca-engine", "alpaca": "configured" if ALPACA_KEY and ALPACA_SECRET else "not_configured"}


@app.get("/v1/quotes", response_model=list[MarketQuote])
def quotes(symbols: str) -> list[MarketQuote]:
    requested = [s.strip().upper() for s in symbols.split(",") if s.strip()]
    result: list[MarketQuote] = []
    stock = [s for s in requested if s in STOCK_SYMBOLS]
    crypto = [s for s in requested if s in CRYPTO_SYMBOLS]

    if stock:
        data = _get("/v2/stocks/snapshots", {"symbols": ",".join(stock), "feed": ALPACA_FEED})
        for symbol, snap in data.items():
            trade = snap.get("latestTrade") or {}
            quote = snap.get("latestQuote") or {}
            price = trade.get("p")
            if price is None:
                bid, ask = quote.get("bp"), quote.get("ap")
                price = (bid + ask) / 2 if bid is not None and ask is not None else None
            if price is None:
                continue
            prev = (snap.get("prevDailyBar") or {}).get("c")
            result.append(MarketQuote(symbol=symbol, price=float(price), changePercent=((float(price) - float(prev)) / float(prev) * 100.0) if prev else None, marketTime=trade.get("t") and _iso_to_ms(trade["t"])))

    if crypto:
        alpaca_symbols = ["BTC/USD" if s == "BTCUSD" else "ETH/USD" for s in crypto]
        data = _get("/v1beta3/crypto/us/snapshots", {"symbols": ",".join(alpaca_symbols)})
        reverse = {"BTC/USD": "BTCUSD", "ETH/USD": "ETHUSD"}
        for provider_symbol, snap in data.items():
            trade = snap.get("latestTrade") or {}
            quote = snap.get("latestQuote") or {}
            price = trade.get("p")
            if price is None:
                bid, ask = quote.get("bp"), quote.get("ap")
                price = (bid + ask) / 2 if bid is not None and ask is not None else None
            if price is None:
                continue
            prev = (snap.get("prevDailyBar") or {}).get("c")
            result.append(MarketQuote(symbol=reverse.get(provider_symbol, provider_symbol), price=float(price), changePercent=((float(price) - float(prev)) / float(prev) * 100.0) if prev else None, marketTime=trade.get("t") and _iso_to_ms(trade["t"])))

    return result


def _iso_to_ms(value: str | int | float) -> int | None:
    if isinstance(value, (int, float)):
        return int(value * 1000 if value < 10_000_000_000 else value)
    try:
        from datetime import datetime
        return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)
    except (ValueError, TypeError):
        return None


@app.get("/v1/candles", response_model=list[Candle])
def candles(symbol: str, timeframe: str = "5Min", limit: int = 160) -> list[Candle]:
    symbol = symbol.upper()
    limit = max(21, min(limit, 160))
    if _kind(symbol) == "stock":
        data = _get(f"/v2/stocks/{quote(symbol, safe='')}/bars", {"timeframe": timeframe, "limit": str(limit), "feed": ALPACA_FEED})
        rows = data.get("bars", [])
    else:
        provider = _crypto_symbol(symbol)
        data = _get("/v1beta3/crypto/us/bars", {"symbols": provider, "timeframe": timeframe, "limit": str(limit)})
        rows = data.get("bars", {}).get(provider, [])
    return [Candle(time=_iso_to_ms(row["t"]) or 0, open=float(row["o"]), high=float(row["h"]), low=float(row["l"]), close=float(row["c"]), volume=float(row.get("v", 0.0))) for row in rows]


@app.post("/v1/analyze", response_model=AnalysisResponse)
def analyze(request: AnalysisRequest) -> AnalysisResponse:
    result = analyze_closes([c.close for c in request.candles])
    return AnalysisResponse(symbol=request.symbol.upper(), signal=result.signal, confidence=result.confidence, trend=result.trend, rsi=result.rsi)
