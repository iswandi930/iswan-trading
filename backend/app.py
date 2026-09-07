from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from analysis_engine import analyze_closes

app = FastAPI(title="Iswan Trading Yahoo Market Engine", version="0.7.0")

SYMBOLS = {
    "XAUUSD", "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD",
    "EURGBP", "EURJPY", "GBPJPY", "AUDJPY", "EURAUD", "EURCHF", "GBPCHF", "AUDCAD",
    "AUDCHF", "CADJPY", "CHFJPY", "NZDJPY", "NZDCHF", "USOIL", "UKOIL", "BTCUSD", "ETHUSD",
    "AAPL", "MSFT", "NVDA", "AMZN", "META", "TSLA", "SPY", "QQQ",
}

# Yahoo Finance chart symbols. The app's displayed market symbols remain unchanged.
YAHOO_SYMBOLS = {
    "XAUUSD": "GC=F", "USOIL": "CL=F", "UKOIL": "BZ=F",
    "EURUSD": "EURUSD=X", "GBPUSD": "GBPUSD=X", "USDJPY": "JPY=X", "USDCHF": "CHF=X",
    "AUDUSD": "AUDUSD=X", "USDCAD": "CAD=X", "NZDUSD": "NZDUSD=X",
    "EURGBP": "EURGBP=X", "EURJPY": "EURJPY=X", "GBPJPY": "GBPJPY=X", "AUDJPY": "AUDJPY=X",
    "EURAUD": "EURAUD=X", "EURCHF": "EURCHF=X", "GBPCHF": "GBPCHF=X", "AUDCAD": "AUDCAD=X",
    "AUDCHF": "AUDCHF=X", "CADJPY": "CADJPY=X", "CHFJPY": "CHFJPY=X", "NZDJPY": "NZDJPY=X",
    "NZDCHF": "NZDCHF=X", "BTCUSD": "BTC-USD", "ETHUSD": "ETH-USD",
    "AAPL": "AAPL", "MSFT": "MSFT", "NVDA": "NVDA", "AMZN": "AMZN",
    "META": "META", "TSLA": "TSLA", "SPY": "SPY", "QQQ": "QQQ",
}


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


async def _json(url: str, params: dict | None = None) -> dict | list:
    try:
        async with httpx.AsyncClient(timeout=8.0, follow_redirects=True, headers={"User-Agent": "Mozilla/5.0"}) as client:
            response = await client.get(url, params=params)
            response.raise_for_status()
            return response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Yahoo market provider connection error: {exc}") from exc


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
    source: str | None = None
    live: bool = False
    freshness: str = "unknown"


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
        "service": "iswan-yahoo-market-engine",
        "provider": "Yahoo Finance chart endpoint",
        "policy": "same-source-quotes-and-candles-no-fake-prices",
    }


async def _yahoo_chart(symbol: str, range_: str = "1d", interval: str = "1m") -> dict:
    yahoo = YAHOO_SYMBOLS[symbol]
    data = await _json(
        f"https://query1.finance.yahoo.com/v8/finance/chart/{yahoo}",
        {"range": range_, "interval": interval, "includePrePost": "true", "events": "div,splits"},
    )
    chart = data.get("chart", {}) if isinstance(data, dict) else {}
    rows = chart.get("result") or []
    if not rows:
        raise HTTPException(status_code=502, detail=f"Yahoo returned no data for {symbol} ({yahoo})")
    return rows[0]


async def _yahoo_quote(symbol: str) -> MarketQuote:
    result = await _yahoo_chart(symbol, "1d", "1m")
    meta = result.get("meta", {})
    price = meta.get("regularMarketPrice")
    timestamps = result.get("timestamp") or []
    indicators = result.get("indicators", {})
    quote_rows = indicators.get("quote") or []
    quote = quote_rows[0] if quote_rows else {}
    closes = quote.get("close") or []
    if price is None:
        valid = [x for x in closes if x is not None]
        price = valid[-1] if valid else None
    if price is None:
        raise HTTPException(status_code=502, detail=f"Yahoo returned no price for {symbol}")

    previous = meta.get("previousClose")
    change = None
    if previous not in (None, 0):
        change = (float(price) - float(previous)) / float(previous) * 100.0
    market_time = meta.get("regularMarketTime")
    if market_time is None and timestamps:
        market_time = timestamps[-1]
    market_ms = int(market_time * 1000) if market_time else _now_ms()

    return MarketQuote(
        symbol=symbol,
        price=float(price),
        changePercent=change,
        marketTime=market_ms,
        source=f"Yahoo Finance ({YAHOO_SYMBOLS[symbol]})",
        # Polling frequency does not make an exchange quote tick-real-time.
        # Keep the flag honest where Yahoo may provide delayed exchange data.
        live=False,
        freshness="Yahoo-chart-quote",
    )


async def _safe_yahoo_quote(symbol: str) -> MarketQuote | None:
    try:
        return await _yahoo_quote(symbol)
    except HTTPException:
        return None


@app.get("/v1/quotes", response_model=list[MarketQuote])
async def quotes(symbols: str) -> list[MarketQuote]:
    requested = [s.strip().upper() for s in symbols.split(",") if s.strip()]
    unknown = [s for s in requested if s not in SYMBOLS]
    if unknown:
        raise HTTPException(status_code=404, detail=f"Unsupported symbols: {', '.join(unknown)}")
    rows = await asyncio.gather(*(_safe_yahoo_quote(symbol) for symbol in requested))
    by_symbol = {row.symbol: row for row in rows if row is not None}
    return [by_symbol[s] for s in requested if s in by_symbol]


@app.get("/v1/candles", response_model=list[Candle])
async def candles(symbol: str, timeframe: str = "5min", limit: int = 160) -> list[Candle]:
    symbol = symbol.upper().strip()
    if symbol not in SYMBOLS:
        raise HTTPException(status_code=404, detail=f"Unsupported symbol: {symbol}")
    limit = max(21, min(limit, 1000))
    intervals = {
        "1min": ("1d", "1m"), "5min": ("5d", "5m"), "15min": ("5d", "15m"),
        "30min": ("1mo", "30m"), "1h": ("3mo", "1h"), "4h": ("6mo", "1h"),
        "1day": ("2y", "1d"),
    }
    range_interval = intervals.get(timeframe.lower())
    if range_interval is None:
        raise HTTPException(status_code=400, detail=f"Unsupported timeframe: {timeframe}")
    range_, interval = range_interval
    result = await _yahoo_chart(symbol, range_, interval)
    timestamps = result.get("timestamp") or []
    indicators = result.get("indicators", {})
    quote_rows = indicators.get("quote") or []
    quote = quote_rows[0] if quote_rows else {}
    opens, highs, lows, closes, volumes = (
        quote.get("open") or [], quote.get("high") or [], quote.get("low") or [],
        quote.get("close") or [], quote.get("volume") or [],
    )
    candles_out: list[Candle] = []
    for i, ts in enumerate(timestamps):
        values = (opens[i] if i < len(opens) else None, highs[i] if i < len(highs) else None,
                  lows[i] if i < len(lows) else None, closes[i] if i < len(closes) else None)
        if any(v is None for v in values):
            continue
        candles_out.append(Candle(
            time=int(ts * 1000), open=float(values[0]), high=float(values[1]),
            low=float(values[2]), close=float(values[3]),
            volume=float(volumes[i]) if i < len(volumes) and volumes[i] is not None else 0.0,
        ))
    return candles_out[-limit:]


@app.post("/v1/analyze", response_model=AnalysisResponse)
def analyze(request: AnalysisRequest) -> AnalysisResponse:
    result = analyze_closes([c.close for c in request.candles])
    return AnalysisResponse(symbol=request.symbol.upper(), signal=result.signal, confidence=result.confidence, trend=result.trend, rsi=result.rsi)
