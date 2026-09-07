from __future__ import annotations

import asyncio
import time
from datetime import datetime, timezone

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from analysis_engine import analyze_closes

app = FastAPI(title="Iswan Trading Yahoo Market Engine", version="0.8.3")

SYMBOLS = {
    "XAUUSD", "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD",
    "EURGBP", "EURJPY", "GBPJPY", "AUDJPY", "EURAUD", "EURCHF", "GBPCHF", "AUDCAD",
    "AUDCHF", "CADJPY", "CHFJPY", "NZDJPY", "NZDCHF", "USOIL", "UKOIL", "BTCUSD", "ETHUSD",
    "AAPL", "MSFT", "NVDA", "AMZN", "META", "TSLA", "SPY", "QQQ",
}

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

YAHOO_HEADERS = {"User-Agent": "Mozilla/5.0", "Accept": "application/json"}
_yahoo_client = httpx.AsyncClient(
    timeout=httpx.Timeout(8.0, connect=3.0),
    follow_redirects=True,
    headers=YAHOO_HEADERS,
    limits=httpx.Limits(max_connections=40, max_keepalive_connections=40),
)


@app.on_event("shutdown")
async def _shutdown_yahoo_client() -> None:
    await _yahoo_client.aclose()


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


async def _json(url: str, params: dict | None = None) -> dict | list:
    try:
        response = await _yahoo_client.get(url, params=params)
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
        live=False,
        freshness="Yahoo-chart-quote",
    )


async def _safe_yahoo_quote(symbol: str) -> MarketQuote | None:
    try:
        return await _yahoo_quote(symbol)
    except HTTPException:
        return None


def _rows_to_candles(result: dict, limit: int) -> list[Candle]:
    timestamps = result.get("timestamp") or []
    indicators = result.get("indicators", {})
    quote_rows = indicators.get("quote") or []
    quote = quote_rows[0] if quote_rows else {}
    opens, highs, lows, closes, volumes = (
        quote.get("open") or [], quote.get("high") or [], quote.get("low") or [],
        quote.get("close") or [], quote.get("volume") or [],
    )
    out: list[Candle] = []
    for i, ts in enumerate(timestamps):
        values = (
            opens[i] if i < len(opens) else None,
            highs[i] if i < len(highs) else None,
            lows[i] if i < len(lows) else None,
            closes[i] if i < len(closes) else None,
        )
        if any(v is None for v in values):
            continue
        out.append(Candle(
            time=int(ts * 1000), open=float(values[0]), high=float(values[1]),
            low=float(values[2]), close=float(values[3]),
            volume=float(volumes[i]) if i < len(volumes) and volumes[i] is not None else 0.0,
        ))
    return out[-limit:]


def _aggregate_buckets(rows: list[Candle], minutes: int) -> list[Candle]:
    if not rows:
        return []
    bucket_ms = minutes * 60_000
    grouped: dict[int, list[Candle]] = {}
    for row in rows:
        start = (row.time // bucket_ms) * bucket_ms
        grouped.setdefault(start, []).append(row)
    out: list[Candle] = []
    for start in sorted(grouped):
        bucket = grouped[start]
        out.append(Candle(
            time=start,
            open=bucket[0].open,
            high=max(c.high for c in bucket),
            low=min(c.low for c in bucket),
            close=bucket[-1].close,
            volume=sum(c.volume for c in bucket),
        ))
    return out


def _aggregate_current_bucket(one_minute: list[Candle], minutes: int) -> Candle | None:
    if not one_minute:
        return None
    bucket_ms = minutes * 60_000
    bucket_start = (one_minute[-1].time // bucket_ms) * bucket_ms
    rows = [c for c in one_minute if (c.time // bucket_ms) * bucket_ms == bucket_start]
    if not rows:
        return None
    return Candle(
        time=bucket_start,
        open=rows[0].open,
        high=max(c.high for c in rows),
        low=min(c.low for c in rows),
        close=rows[-1].close,
        volume=sum(c.volume for c in rows),
    )


def _validate_candles(rows: list[Candle]) -> dict:
    errors: list[str] = []
    previous_time = None
    for i, row in enumerate(rows):
        values = (row.open, row.high, row.low, row.close, row.volume)
        if not all(__import__("math").isfinite(v) for v in values):
            errors.append(f"non-finite value at index {i}")
            continue
        if row.high < max(row.open, row.close) or row.low > min(row.open, row.close) or row.high < row.low:
            errors.append(f"invalid OHLC relationship at index {i}")
        if previous_time is not None and row.time <= previous_time:
            errors.append(f"timestamps not strictly increasing at index {i}")
        previous_time = row.time
    return {"valid": not errors, "errors": errors[:10]}


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
    key = timeframe.lower().strip()
    intervals = {
        "1min": ("1d", "1m"), "5min": ("5d", "5m"), "15min": ("5d", "15m"),
        "30min": ("1mo", "30m"), "1h": ("3mo", "1h"), "4h": ("6mo", "1h"),
        "1day": ("2y", "1d"),
    }
    range_interval = intervals.get(key)
    if range_interval is None:
        raise HTTPException(status_code=400, detail=f"Unsupported timeframe: {timeframe}")
    range_, interval = range_interval
    result = await _yahoo_chart(symbol, range_, interval)
    raw = _rows_to_candles(result, 1000 if key == "4h" else limit)

    if key == "4h":
        raw = _aggregate_buckets(raw, 240)
        return raw[-limit:]

    candles_out = raw[-limit:]

    minutes = {"1min": 1, "5min": 5, "15min": 15, "30min": 30}.get(key)
    if minutes is not None and key != "1min":
        try:
            live_result = await _yahoo_chart(symbol, "1d", "1m")
            live_rows = _rows_to_candles(live_result, 1000)
            current = _aggregate_current_bucket(live_rows, minutes)
            if current is not None:
                if candles_out and candles_out[-1].time == current.time:
                    candles_out[-1] = current
                elif not candles_out or current.time > candles_out[-1].time:
                    candles_out.append(current)
                candles_out = candles_out[-limit:]
        except HTTPException:
            pass
    return candles_out


@app.get("/v1/self-test")
async def self_test(symbol: str = "XAUUSD") -> dict:
    """Run production-side Yahoo connectivity and candle-integrity checks.

    This endpoint is intentionally read-only. It verifies every Android chart timeframe
    using the same code path as production and never invents prices.
    """
    symbol = symbol.upper().strip()
    if symbol not in SYMBOLS:
        raise HTTPException(status_code=404, detail=f"Unsupported symbol: {symbol}")

    tests: dict[str, dict] = {}
    overall = True

    quote_started = time.perf_counter()
    try:
        quote = await _yahoo_quote(symbol)
        quote_ok = quote.price > 0 and quote.marketTime is not None
        tests["quote"] = {
            "ok": quote_ok,
            "price": quote.price,
            "marketTime": quote.marketTime,
            "source": quote.source,
            "freshness": quote.freshness,
            "latencyMs": round((time.perf_counter() - quote_started) * 1000, 1),
        }
        overall &= quote_ok
    except HTTPException as exc:
        tests["quote"] = {"ok": False, "error": str(exc.detail), "latencyMs": round((time.perf_counter() - quote_started) * 1000, 1)}
        overall = False

    timeframe_map = {"1M": "1min", "5M": "5min", "15M": "15min", "30M": "30min", "1H": "1h", "4H": "4h", "1D": "1day"}
    for label, key in timeframe_map.items():
        started = time.perf_counter()
        try:
            rows = await candles(symbol, key, 80)
            validation = _validate_candles(rows)
            enough = len(rows) >= 21
            ok = validation["valid"] and enough
            tests[label] = {
                "ok": ok,
                "timeframe": key,
                "count": len(rows),
                "latestTime": rows[-1].time if rows else None,
                "latestClose": rows[-1].close if rows else None,
                "ohlc": validation,
                "enoughData": enough,
                "latencyMs": round((time.perf_counter() - started) * 1000, 1),
            }
            overall &= ok
        except HTTPException as exc:
            tests[label] = {"ok": False, "timeframe": key, "error": str(exc.detail), "latencyMs": round((time.perf_counter() - started) * 1000, 1)}
            overall = False
        except Exception as exc:
            tests[label] = {"ok": False, "timeframe": key, "error": f"Unexpected error: {exc}", "latencyMs": round((time.perf_counter() - started) * 1000, 1)}
            overall = False

    return {
        "status": "PASS" if overall else "FAIL",
        "symbol": symbol,
        "provider": "Yahoo Finance chart endpoint",
        "testedAt": _now_ms(),
        "noFakePrices": True,
        "tests": tests,
    }


@app.post("/v1/analyze", response_model=AnalysisResponse)
def analyze(request: AnalysisRequest) -> AnalysisResponse:
    result = analyze_closes([c.close for c in request.candles])
    return AnalysisResponse(symbol=request.symbol.upper(), signal=result.signal, confidence=result.confidence, trend=result.trend, rsi=result.rsi)
