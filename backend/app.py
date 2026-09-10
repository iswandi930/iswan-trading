from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field
from datetime import datetime, timezone, timedelta
import os
import httpx

from analysis_engine import analyze_closes

app = FastAPI(title="Iswan Trading Massive Market Engine", version="2.0.0")

# Symbols exposed by the app. Provider tickers are mapped below.
SYMBOLS = {
    "XAUUSD", "XAGUSD", "XPTUSD", "XPDUSD", "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD",
    "EURGBP", "EURJPY", "GBPJPY", "AUDJPY", "EURAUD", "EURCHF", "GBPCHF", "AUDCAD", "AUDCHF", "CADJPY", "CHFJPY",
    "NZDJPY", "NZDCHF", "EURNZD", "GBPAUD", "GBPCAD", "GBPNZD", "AUDNZD", "USOIL", "UKOIL", "NATGAS", "COPPER",
    "BTCUSD", "ETHUSD", "SOLUSD", "XRPUSD", "AAPL", "MSFT", "NVDA", "AMZN", "META", "TSLA", "GOOGL", "NFLX", "AMD",
    "AVGO", "JPM", "V", "MA", "SPY", "QQQ", "DIA", "IWM", "GLD", "SLV",
}

# Massive/Polygon-style ticker notation.
# Forex pairs use C: prefix. Crypto uses X:. US stocks/ETFs are plain tickers.
MASSIVE_TICKERS = {
    "XAUUSD": "C:XAUUSD", "XAGUSD": "C:XAGUSD", "XPTUSD": "C:XPTUSD", "XPDUSD": "C:XPDUSD",
    "EURUSD": "C:EURUSD", "GBPUSD": "C:GBPUSD", "USDJPY": "C:USDJPY", "USDCHF": "C:USDCHF",
    "AUDUSD": "C:AUDUSD", "USDCAD": "C:USDCAD", "NZDUSD": "C:NZDUSD", "EURGBP": "C:EURGBP",
    "EURJPY": "C:EURJPY", "GBPJPY": "C:GBPJPY", "AUDJPY": "C:AUDJPY", "EURAUD": "C:EURAUD",
    "EURCHF": "C:EURCHF", "GBPCHF": "C:GBPCHF", "AUDCAD": "C:AUDCAD", "AUDCHF": "C:AUDCHF",
    "CADJPY": "C:CADJPY", "CHFJPY": "C:CHFJPY", "NZDJPY": "C:NZDJPY", "NZDCHF": "C:NZDCHF",
    "EURNZD": "C:EURNZD", "GBPAUD": "C:GBPAUD", "GBPCAD": "C:GBPCAD", "GBPNZD": "C:GBPNZD", "AUDNZD": "C:AUDNZD",
    "BTCUSD": "X:BTCUSD", "ETHUSD": "X:ETHUSD", "SOLUSD": "X:SOLUSD", "XRPUSD": "X:XRPUSD",
    "AAPL": "AAPL", "MSFT": "MSFT", "NVDA": "NVDA", "AMZN": "AMZN", "META": "META", "TSLA": "TSLA",
    "GOOGL": "GOOGL", "NFLX": "NFLX", "AMD": "AMD", "AVGO": "AVGO", "JPM": "JPM", "V": "V", "MA": "MA",
    "SPY": "SPY", "QQQ": "QQQ", "DIA": "DIA", "IWM": "IWM", "GLD": "GLD", "SLV": "SLV",
}

# Oil/gas/copper are kept configurable because the exact futures contract symbol can change.
# Defaults use common continuous futures symbols; override in hosting environment if needed.
MASSIVE_TICKERS.update({
    "USOIL": os.getenv("MASSIVE_USOIL_TICKER", "CL"),
    "UKOIL": os.getenv("MASSIVE_UKOIL_TICKER", "BZ"),
    "NATGAS": os.getenv("MASSIVE_NATGAS_TICKER", "NG"),
    "COPPER": os.getenv("MASSIVE_COPPER_TICKER", "HG"),
})

MASSIVE_API_KEY = os.getenv("MASSIVE_API_KEY", "").strip()
MASSIVE_BASE = os.getenv("MASSIVE_BASE_URL", "https://api.massive.com").rstrip("/")
_client = httpx.AsyncClient(
    timeout=httpx.Timeout(12.0, connect=5.0),
    follow_redirects=True,
    headers={"Accept": "application/json", "User-Agent": "Iswan-Trading/2.0"},
    limits=httpx.Limits(max_connections=40, max_keepalive_connections=40),
)

@app.on_event("shutdown")
async def _shutdown_client() -> None:
    await _client.aclose()


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


async def _massive(path: str, params: dict | None = None) -> dict:
    if not MASSIVE_API_KEY:
        raise HTTPException(status_code=503, detail="MASSIVE_API_KEY is not configured")
    query = dict(params or {})
    query["apiKey"] = MASSIVE_API_KEY
    try:
        response = await _client.get(f"{MASSIVE_BASE}{path}", params=query)
        response.raise_for_status()
        data = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Massive provider connection error: {exc}") from exc
    if isinstance(data, dict) and data.get("status") == "ERROR":
        raise HTTPException(status_code=502, detail=f"Massive error: {data.get('error', data.get('message', 'provider error'))}")
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
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "service": "iswan-massive-market-engine",
        "provider": "Massive",
        "api_key_configured": "true" if MASSIVE_API_KEY else "false",
        "policy": "massive-only-market-data-no-yahoo-or-twelve-fallback",
    }


def _validate_symbol(symbol: str) -> str:
    symbol = symbol.upper().strip()
    if symbol not in SYMBOLS:
        raise HTTPException(status_code=400, detail=f"Unsupported symbol: {symbol}")
    return symbol


def _timeframe_spec(timeframe: str) -> tuple[int, str]:
    mapping = {
        "1m": (1, "minute"), "1min": (1, "minute"),
        "5m": (5, "minute"), "5min": (5, "minute"),
        "15m": (15, "minute"), "15min": (15, "minute"),
        "30m": (30, "minute"), "30min": (30, "minute"),
        "1h": (1, "hour"), "2h": (2, "hour"), "3h": (3, "hour"), "4h": (4, "hour"),
        "1d": (1, "day"), "1day": (1, "day"), "1w": (1, "week"), "1week": (1, "week"),
    }
    key = timeframe.lower().strip()
    if key not in mapping:
        raise HTTPException(status_code=400, detail=f"Unsupported timeframe: {timeframe}")
    return mapping[key]


def _ticker(symbol: str) -> str:
    symbol = _validate_symbol(symbol)
    return MASSIVE_TICKERS.get(symbol, symbol)


def _is_forex(symbol: str) -> bool:
    return _ticker(symbol).startswith("C:")


def _is_crypto(symbol: str) -> bool:
    return _ticker(symbol).startswith("X:")


async def _massive_quote(symbol: str) -> MarketQuote:
    symbol = _validate_symbol(symbol)
    ticker = _ticker(symbol)
    data = await _massive(f"/v2/last/trade/{ticker}")
    result = data.get("results") if isinstance(data, dict) else None
    if not isinstance(result, dict):
        # Some provider plans/endpoints expose previous aggregate even when last-trade is unavailable.
        prev = await _massive(f"/v2/aggs/ticker/{ticker}/prev")
        results = prev.get("results") if isinstance(prev, dict) else None
        if not isinstance(results, list) or not results:
            raise HTTPException(status_code=502, detail=f"Massive returned no valid price for {symbol}")
        row = results[0]
        price = float(row.get("c"))
        market_time = int(row.get("t")) if row.get("t") is not None else None
        return MarketQuote(symbol=symbol, price=price, marketTime=market_time, source=f"Massive ({ticker})", live=False, freshness="previous aggregate")

    price_value = result.get("p", result.get("price"))
    if price_value is None:
        price_value = result.get("c")
    try:
        price = float(price_value)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Massive returned no valid price for {symbol}") from exc
    market_time = result.get("t", result.get("timestamp"))
    try:
        market_time = int(market_time) if market_time is not None else None
    except (TypeError, ValueError):
        market_time = None
    return MarketQuote(symbol=symbol, price=price, marketTime=market_time, source=f"Massive ({ticker})", live=True, freshness="last trade")


async def _massive_candles(symbol: str, timeframe: str = "1h", limit: int = 200) -> list[Candle]:
    symbol = _validate_symbol(symbol)
    multiplier, timespan = _timeframe_spec(timeframe)
    ticker = _ticker(symbol)
    limit = max(1, min(limit, 5000))
    # Request a generous time window; Massive returns at most the requested number of aggregates.
    now = datetime.now(timezone.utc)
    if timespan == "minute":
        start = now - timedelta(minutes=multiplier * limit * 2)
    elif timespan == "hour":
        start = now - timedelta(hours=multiplier * limit * 2)
    elif timespan == "day":
        start = now - timedelta(days=multiplier * limit * 2)
    else:
        start = now - timedelta(days=7 * multiplier * limit * 2)
    path = f"/v2/aggs/ticker/{ticker}/range/{multiplier}/{timespan}/{start.date().isoformat()}/{now.date().isoformat()}"
    data = await _massive(path, {"adjusted": "true", "sort": "asc", "limit": limit})
    values = data.get("results") if isinstance(data, dict) else None
    if not isinstance(values, list):
        raise HTTPException(status_code=502, detail=f"Massive returned no candles for {symbol}")
    candles: list[Candle] = []
    for row in values:
        try:
            candles.append(Candle(
                time=int(row["t"]),
                open=float(row["o"]), high=float(row["h"]), low=float(row["l"]), close=float(row["c"]),
                volume=float(row.get("v", 0) or 0),
            ))
        except (KeyError, TypeError, ValueError):
            continue
    if not candles:
        raise HTTPException(status_code=502, detail=f"Massive returned no valid candles for {symbol}")
    return candles[-limit:]


@app.get("/v1/quotes", response_model=list[MarketQuote])
async def quotes(symbols: str = Query(..., min_length=1)) -> list[MarketQuote]:
    requested = [s.strip().upper() for s in symbols.split(",") if s.strip()]
    if not requested:
        raise HTTPException(status_code=400, detail="No symbols supplied")
    if len(requested) > 50:
        raise HTTPException(status_code=400, detail="Maximum 50 symbols per request")
    return [await _massive_quote(symbol) for symbol in requested]


@app.get("/v1/candles", response_model=list[Candle])
async def candles(symbol: str, timeframe: str = "1h", limit: int = Query(200, ge=1, le=5000)) -> list[Candle]:
    return await _massive_candles(symbol, timeframe, limit)


@app.post("/v1/analyze", response_model=AnalysisResponse)
async def analyze(request: AnalysisRequest) -> AnalysisResponse:
    closes = [c.close for c in request.candles]
    result = analyze_closes(closes)
    return AnalysisResponse(symbol=request.symbol.upper(), **result)


@app.post("/v1/risk")
def risk(request: RiskRequest) -> dict:
    risk_amount = request.account_equity * request.risk_percent / 100.0
    stop_distance = abs(request.entry - request.stop_loss)
    reward_distance = abs(request.take_profit - request.entry)
    rr = reward_distance / stop_distance if stop_distance else None
    return {"symbol": request.symbol.upper(), "riskAmount": risk_amount, "stopDistance": stop_distance, "rewardDistance": reward_distance, "riskReward": rr}


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
    accuracy = (wins / total * 100) if total else 0.0
    return {"symbol": request.symbol.upper(), "samples": total, "wins": wins, "losses": losses, "accuracy": accuracy}


@app.get("/v1/self-test")
async def self_test(symbol: str = "XAUUSD") -> dict:
    symbol = _validate_symbol(symbol)
    quote = await _massive_quote(symbol)
    test_candles = await _massive_candles(symbol, "1h", 5)
    return {
        "ok": True,
        "symbol": symbol,
        "quote": quote.model_dump(),
        "candles": len(test_candles),
        "provider": "Massive",
        "no_yahoo_or_twelve_fallback": True,
        "checkedAt": _now_ms(),
    }
