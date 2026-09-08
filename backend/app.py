from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field
from datetime import datetime, timezone
import os
import httpx

from analysis_engine import analyze_closes

app = FastAPI(title="Iswan Trading Twelve Data Market Engine", version="1.0.0")

SYMBOLS = {
    "XAUUSD", "XAGUSD", "XPTUSD", "XPDUSD", "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD",
    "EURGBP", "EURJPY", "GBPJPY", "AUDJPY", "EURAUD", "EURCHF", "GBPCHF", "AUDCAD", "AUDCHF", "CADJPY", "CHFJPY",
    "NZDJPY", "NZDCHF", "EURNZD", "GBPAUD", "GBPCAD", "GBPNZD", "AUDNZD", "USOIL", "UKOIL", "NATGAS", "COPPER",
    "BTCUSD", "ETHUSD", "SOLUSD", "XRPUSD", "AAPL", "MSFT", "NVDA", "AMZN", "META", "TSLA", "GOOGL", "NFLX", "AMD",
    "AVGO", "JPM", "V", "MA", "SPY", "QQQ", "DIA", "IWM", "GLD", "SLV",
}

TWELVE_DATA_SYMBOLS = {
    "XAUUSD": "XAU/USD", "XAGUSD": "XAG/USD", "XPTUSD": "XPT/USD", "XPDUSD": "XPD/USD",
    "EURUSD": "EUR/USD", "GBPUSD": "GBP/USD", "USDJPY": "USD/JPY", "USDCHF": "USD/CHF",
    "AUDUSD": "AUD/USD", "USDCAD": "USD/CAD", "NZDUSD": "NZD/USD", "EURGBP": "EUR/GBP",
    "EURJPY": "EUR/JPY", "GBPJPY": "GBP/JPY", "AUDJPY": "AUD/JPY", "EURAUD": "EUR/AUD",
    "EURCHF": "EUR/CHF", "GBPCHF": "GBP/CHF", "AUDCAD": "AUD/CAD", "AUDCHF": "AUD/CHF",
    "CADJPY": "CAD/JPY", "CHFJPY": "CHF/JPY", "NZDJPY": "NZD/JPY", "NZDCHF": "NZD/CHF",
    "EURNZD": "EUR/NZD", "GBPAUD": "GBP/AUD", "GBPCAD": "GBP/CAD", "GBPNZD": "GBP/NZD",
    "AUDNZD": "AUD/NZD", "BTCUSD": "BTC/USD", "ETHUSD": "ETH/USD", "SOLUSD": "SOL/USD", "XRPUSD": "XRP/USD",
    "AAPL": "AAPL", "MSFT": "MSFT", "NVDA": "NVDA", "AMZN": "AMZN", "META": "META", "TSLA": "TSLA",
    "GOOGL": "GOOGL", "NFLX": "NFLX", "AMD": "AMD", "AVGO": "AVGO", "JPM": "JPM", "V": "V", "MA": "MA",
    "SPY": "SPY", "QQQ": "QQQ", "DIA": "DIA", "IWM": "IWM", "GLD": "GLD", "SLV": "SLV",
    "USOIL": "WTI", "UKOIL": "BRENT", "NATGAS": "NATURALGAS", "COPPER": "COPPER",
}

TWELVE_DATA_API_KEY = os.getenv("TWELVE_DATA_API_KEY", "").strip()
TWELVE_DATA_BASE = "https://api.twelvedata.com"
_tw_client = httpx.AsyncClient(
    timeout=httpx.Timeout(12.0, connect=5.0),
    follow_redirects=True,
    headers={"Accept": "application/json", "User-Agent": "Iswan-Trading/1.0"},
    limits=httpx.Limits(max_connections=40, max_keepalive_connections=40),
)

@app.on_event("shutdown")
async def _shutdown_tw_client() -> None:
    await _tw_client.aclose()

def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)

async def _twelve_data(path: str, params: dict) -> dict:
    if not TWELVE_DATA_API_KEY:
        raise HTTPException(status_code=503, detail="TWELVE_DATA_API_KEY is not configured")
    params = dict(params)
    params["apikey"] = TWELVE_DATA_API_KEY
    try:
        response = await _tw_client.get(f"{TWELVE_DATA_BASE}{path}", params=params)
        response.raise_for_status()
        data = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Twelve Data provider connection error: {exc}") from exc
    if isinstance(data, dict) and data.get("status") == "error":
        raise HTTPException(status_code=502, detail=f"Twelve Data error: {data.get('message', 'provider error')}")
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
        "service": "iswan-twelve-data-market-engine",
        "provider": "Twelve Data",
        "api_key_configured": "true" if TWELVE_DATA_API_KEY else "false",
        "policy": "twelve-data-only-quotes-and-candles-no-yahoo-fallback",
    }

def _validate_symbol(symbol: str) -> str:
    symbol = symbol.upper().strip()
    if symbol not in SYMBOLS:
        raise HTTPException(status_code=400, detail=f"Unsupported symbol: {symbol}")
    return symbol

def _timeframe_interval(timeframe: str) -> str:
    mapping = {
        "1m": "1min", "1min": "1min", "5m": "5min", "5min": "5min", "15m": "15min", "15min": "15min",
        "30m": "30min", "30min": "30min", "1h": "1h", "4h": "4h", "1d": "1day", "1day": "1day",
        "1w": "1week", "1week": "1week",
    }
    if timeframe not in mapping:
        raise HTTPException(status_code=400, detail=f"Unsupported timeframe: {timeframe}")
    return mapping[timeframe]

async def _twelve_quote(symbol: str) -> MarketQuote:
    symbol = _validate_symbol(symbol)
    provider_symbol = TWELVE_DATA_SYMBOLS[symbol]
    data = await _twelve_data("/quote", {"symbol": provider_symbol})
    try:
        price = float(data["close"])
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Twelve Data returned no valid price for {symbol}") from exc
    change = data.get("percent_change")
    change_pct = float(change) if change not in (None, "") else None
    market_time = None
    if data.get("timestamp") not in (None, ""):
        try:
            market_time = int(float(data["timestamp"]) * 1000)
        except (TypeError, ValueError):
            market_time = None
    return MarketQuote(
        symbol=symbol,
        price=price,
        changePercent=change_pct,
        marketTime=market_time,
        source=f"Twelve Data ({provider_symbol})",
        live=True,
        freshness="Twelve Data quote",
    )

async def _twelve_candles(symbol: str, timeframe: str = "1h", limit: int = 200) -> list[Candle]:
    symbol = _validate_symbol(symbol)
    interval = _timeframe_interval(timeframe)
    provider_symbol = TWELVE_DATA_SYMBOLS[symbol]
    data = await _twelve_data("/time_series", {
        "symbol": provider_symbol,
        "interval": interval,
        "outputsize": max(1, min(limit, 5000)),
        "order": "asc",
    })
    values = data.get("values") if isinstance(data, dict) else None
    if not isinstance(values, list):
        raise HTTPException(status_code=502, detail=f"Twelve Data returned no candles for {symbol}")
    candles: list[Candle] = []
    for row in values:
        try:
            dt = datetime.fromisoformat(str(row["datetime"]).replace("Z", "+00:00"))
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            ts = int(dt.timestamp() * 1000)
            candles.append(Candle(
                time=ts,
                open=float(row["open"]), high=float(row["high"]), low=float(row["low"]), close=float(row["close"]),
                volume=float(row.get("volume", 0) or 0),
            ))
        except (KeyError, TypeError, ValueError):
            continue
    if not candles:
        raise HTTPException(status_code=502, detail=f"Twelve Data returned no valid candles for {symbol}")
    return candles

@app.get("/v1/quotes", response_model=list[MarketQuote])
async def quotes(symbols: str = Query(..., min_length=1)) -> list[MarketQuote]:
    requested = [s.strip().upper() for s in symbols.split(",") if s.strip()]
    if not requested:
        raise HTTPException(status_code=400, detail="No symbols supplied")
    if len(requested) > 50:
        raise HTTPException(status_code=400, detail="Maximum 50 symbols per request")
    return [await _twelve_quote(symbol) for symbol in requested]

@app.get("/v1/candles", response_model=list[Candle])
async def candles(symbol: str, timeframe: str = "1h", limit: int = Query(200, ge=1, le=5000)) -> list[Candle]:
    return await _twelve_candles(symbol, timeframe, limit)

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
    quote = await _twelve_quote(symbol)
    test_candles = await _twelve_candles(symbol, "1h", 5)
    return {
        "ok": True,
        "symbol": symbol,
        "quote": quote.model_dump(),
        "candles": len(test_candles),
        "provider": "Twelve Data",
        "no_yahoo_fallback": True,
        "checkedAt": _now_ms(),
    }
