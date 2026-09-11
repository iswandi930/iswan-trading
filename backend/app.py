from __future__ import annotations

import asyncio
import math
import time
from datetime import datetime, timezone

import httpx
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field

from analysis_engine import analyze_closes

app = FastAPI(title="Iswan Trading Market Engine", version="3.0.0")

SYMBOLS = {
    "XAUUSD", "XAGUSD", "XPTUSD", "XPDUSD", "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD",
    "EURGBP", "EURJPY", "GBPJPY", "AUDJPY", "EURAUD", "EURCHF", "GBPCHF", "AUDCAD", "AUDCHF", "CADJPY", "CHFJPY",
    "NZDJPY", "NZDCHF", "EURNZD", "GBPAUD", "GBPCAD", "GBPNZD", "AUDNZD", "USOIL", "UKOIL", "NATGAS", "COPPER",
    "BTCUSD", "ETHUSD", "SOLUSD", "XRPUSD", "AAPL", "MSFT", "NVDA", "AMZN", "META", "TSLA", "GOOGL", "NFLX", "AMD",
    "AVGO", "JPM", "V", "MA", "SPY", "QQQ", "DIA", "IWM", "GLD", "SLV",
}

YAHOO_SYMBOLS = {
    "XAUUSD": "GC=F", "XAGUSD": "SI=F", "XPTUSD": "PL=F", "XPDUSD": "PA=F",
    "EURUSD": "EURUSD=X", "GBPUSD": "GBPUSD=X", "USDJPY": "JPY=X", "USDCHF": "CHF=X",
    "AUDUSD": "AUDUSD=X", "USDCAD": "CAD=X", "NZDUSD": "NZDUSD=X", "EURGBP": "EURGBP=X",
    "EURJPY": "EURJPY=X", "GBPJPY": "GBPJPY=X", "AUDJPY": "AUDJPY=X", "EURAUD": "EURAUD=X",
    "EURCHF": "EURCHF=X", "GBPCHF": "GBPCHF=X", "AUDCAD": "AUDCAD=X", "AUDCHF": "AUDCHF=X",
    "CADJPY": "CADJPY=X", "CHFJPY": "CHFJPY=X", "NZDJPY": "NZDJPY=X", "NZDCHF": "NZDCHF=X",
    "EURNZD": "EURNZD=X", "GBPAUD": "GBPAUD=X", "GBPCAD": "GBPCAD=X", "GBPNZD": "GBPNZD=X", "AUDNZD": "AUDNZD=X",
    "USOIL": "CL=F", "UKOIL": "BZ=F", "NATGAS": "NG=F", "COPPER": "HG=F",
    "BTCUSD": "BTC-USD", "ETHUSD": "ETH-USD", "SOLUSD": "SOL-USD", "XRPUSD": "XRP-USD",
    "AAPL": "AAPL", "MSFT": "MSFT", "NVDA": "NVDA", "AMZN": "AMZN", "META": "META", "TSLA": "TSLA",
    "GOOGL": "GOOGL", "NFLX": "NFLX", "AMD": "AMD", "AVGO": "AVGO", "JPM": "JPM", "V": "V", "MA": "MA",
    "SPY": "SPY", "QQQ": "QQQ", "DIA": "DIA", "IWM": "IWM", "GLD": "GLD", "SLV": "SLV",
}

_client = httpx.AsyncClient(
    timeout=httpx.Timeout(8.0, connect=3.0),
    follow_redirects=True,
    headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"},
    limits=httpx.Limits(max_connections=40, max_keepalive_connections=40),
)

@app.on_event("shutdown")
async def shutdown() -> None:
    await _client.aclose()


def now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


async def yahoo_chart(symbol: str, range_: str, interval: str) -> dict:
    symbol = symbol.upper().strip()
    if symbol not in SYMBOLS:
        raise HTTPException(status_code=404, detail=f"Unsupported symbol: {symbol}")
    yahoo = YAHOO_SYMBOLS[symbol]
    try:
        response = await _client.get(
            f"https://query1.finance.yahoo.com/v8/finance/chart/{yahoo}",
            params={"range": range_, "interval": interval, "includePrePost": "true", "events": "div,splits"},
        )
        response.raise_for_status()
        data = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Market provider connection error: {exc}") from exc
    result = ((data.get("chart") or {}).get("result") or []) if isinstance(data, dict) else []
    if not result:
        raise HTTPException(status_code=502, detail=f"Market provider returned no data for {symbol}")
    return result[0]


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


class RiskRequest(BaseModel):
    symbol: str = Field(min_length=1, max_length=32)
    entry: float = Field(gt=0)
    stop_loss: float = Field(gt=0)
    take_profit: float = Field(gt=0)
    account_equity: float = Field(gt=0)
    risk_percent: float = Field(gt=0, le=10)


class BacktestRequest(BaseModel):
    symbol: str = Field(min_length=1, max_length=32)
    candles: list[Candle] = Field(min_length=60, max_length=5000)
    horizon: int = Field(default=3, ge=1, le=50)
    threshold: float = Field(default=0.0, ge=0)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "iswan-market-engine",
        "provider": "Yahoo Finance chart endpoint",
        "policy": "same-source-quotes-and-candles-no-fake-prices",
        "checkedAt": now_ms(),
    }


async def yahoo_quote(symbol: str) -> MarketQuote:
    result = await yahoo_chart(symbol, "1d", "1m")
    meta = result.get("meta") or {}
    quote_rows = ((result.get("indicators") or {}).get("quote") or [])
    quote = quote_rows[0] if quote_rows else {}
    closes = quote.get("close") or []
    price = meta.get("regularMarketPrice")
    if price is None:
        valid = [x for x in closes if x is not None]
        price = valid[-1] if valid else None
    if price is None:
        raise HTTPException(status_code=502, detail=f"No valid price for {symbol}")
    previous = meta.get("previousClose")
    change = None if previous in (None, 0) else (float(price) - float(previous)) / float(previous) * 100.0
    market_time = meta.get("regularMarketTime")
    if market_time is None:
        timestamps = result.get("timestamp") or []
        market_time = timestamps[-1] if timestamps else int(time.time())
    return MarketQuote(
        symbol=symbol, price=float(price), changePercent=change,
        marketTime=int(market_time) * 1000,
        source=f"Yahoo Finance ({YAHOO_SYMBOLS[symbol]})", live=False,
        freshness="Yahoo chart",
    )


def rows_to_candles(result: dict, limit: int) -> list[Candle]:
    timestamps = result.get("timestamp") or []
    quote_rows = ((result.get("indicators") or {}).get("quote") or [])
    quote = quote_rows[0] if quote_rows else {}
    opens, highs, lows, closes, volumes = [quote.get(k) or [] for k in ("open", "high", "low", "close", "volume")]
    out: list[Candle] = []
    for i, ts in enumerate(timestamps):
        values = [a[i] if i < len(a) else None for a in (opens, highs, lows, closes)]
        if any(v is None for v in values):
            continue
        o, h, l, c = map(float, values)
        v = float(volumes[i]) if i < len(volumes) and volumes[i] is not None else 0.0
        if all(math.isfinite(x) for x in (o, h, l, c, v)) and h >= max(o, c) and l <= min(o, c):
            out.append(Candle(time=int(ts) * 1000, open=o, high=h, low=l, close=c, volume=v))
    return out[-limit:]


def aggregate(rows: list[Candle], minutes: int) -> list[Candle]:
    if not rows:
        return []
    bucket_ms = minutes * 60_000
    grouped: dict[int, list[Candle]] = {}
    for row in rows:
        grouped.setdefault((row.time // bucket_ms) * bucket_ms, []).append(row)
    return [Candle(time=k, open=v[0].open, high=max(x.high for x in v), low=min(x.low for x in v), close=v[-1].close, volume=sum(x.volume for x in v)) for k, v in sorted(grouped.items())]


@app.get("/v1/quotes", response_model=list[MarketQuote])
async def quotes(symbols: str = Query(..., min_length=1)) -> list[MarketQuote]:
    requested = [s.strip().upper() for s in symbols.split(",") if s.strip()]
    if len(requested) > 50:
        raise HTTPException(status_code=400, detail="Maximum 50 symbols per request")
    results = await asyncio.gather(*(yahoo_quote(s) for s in requested), return_exceptions=True)
    out = []
    for result in results:
        if isinstance(result, MarketQuote):
            out.append(result)
    return out


@app.get("/v1/candles", response_model=list[Candle])
async def candles(symbol: str, timeframe: str = "5m", limit: int = Query(160, ge=21, le=1000)) -> list[Candle]:
    key = timeframe.lower().strip()
    specs = {
        "1m": ("1d", "1m", None), "5m": ("5d", "5m", None), "15m": ("5d", "15m", None),
        "30m": ("1mo", "30m", None), "1h": ("3mo", "1h", None), "4h": ("6mo", "1h", 240), "1d": ("2y", "1d", None),
        "1min": ("1d", "1m", None), "5min": ("5d", "5m", None), "15min": ("5d", "15m", None),
        "30min": ("1mo", "30m", None), "1day": ("2y", "1d", None),
    }
    spec = specs.get(key)
    if spec is None:
        raise HTTPException(status_code=400, detail=f"Unsupported timeframe: {timeframe}")
    range_, interval, aggregation = spec
    result = await yahoo_chart(symbol, range_, interval)
    rows = rows_to_candles(result, 1000 if aggregation else limit)
    if aggregation:
        return aggregate(rows, aggregation)[-limit:]
    return rows[-limit:]


@app.get("/v1/self-test")
async def self_test(symbol: str = "XAUUSD") -> dict:
    started = time.perf_counter()
    quote = await yahoo_quote(symbol)
    checks = {}
    for label, key in {"1M": "1m", "5M": "5m", "15M": "15m", "30M": "30m", "1H": "1h", "4H": "4h", "1D": "1d"}.items():
        rows = await candles(symbol, key, 80)
        valid = len(rows) >= 21 and all(r.high >= max(r.open, r.close) and r.low <= min(r.open, r.close) for r in rows)
        checks[label] = {"ok": valid, "count": len(rows), "latestClose": rows[-1].close if rows else None}
    return {"status": "PASS" if all(x["ok"] for x in checks.values()) else "FAIL", "symbol": symbol.upper(), "quote": quote.model_dump(), "timeframes": checks, "latencyMs": round((time.perf_counter() - started) * 1000, 1)}


@app.post("/v1/analyze", response_model=AnalysisResponse)
def analyze(request: AnalysisRequest) -> AnalysisResponse:
    return AnalysisResponse(symbol=request.symbol.upper(), **analyze_closes([c.close for c in request.candles]))


@app.post("/v1/risk")
def risk(request: RiskRequest) -> dict:
    risk_amount = request.account_equity * request.risk_percent / 100.0
    stop_distance = abs(request.entry - request.stop_loss)
    reward_distance = abs(request.take_profit - request.entry)
    return {"symbol": request.symbol.upper(), "riskAmount": risk_amount, "stopDistance": stop_distance, "rewardDistance": reward_distance, "riskReward": reward_distance / stop_distance if stop_distance else None}


@app.post("/v1/backtest")
def backtest(request: BacktestRequest) -> dict:
    closes = [c.close for c in request.candles]
    wins = losses = 0
    for i in range(len(closes) - request.horizon):
        delta = closes[i + request.horizon] - closes[i]
        if delta > request.threshold:
            wins += 1
        elif delta < -request.threshold:
            losses += 1
    total = wins + losses
    return {"symbol": request.symbol.upper(), "samples": total, "wins": wins, "losses": losses, "accuracy": wins / total * 100 if total else 0.0}
