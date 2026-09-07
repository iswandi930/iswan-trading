from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from analysis_engine import analyze_closes

app = FastAPI(title="Iswan Trading Public Market Engine", version="0.6.0")

SYMBOLS = {
    "XAUUSD", "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD",
    "EURGBP", "EURJPY", "GBPJPY", "AUDJPY", "EURAUD", "EURCHF", "GBPCHF", "AUDCAD",
    "AUDCHF", "CADJPY", "CHFJPY", "NZDJPY", "NZDCHF", "USOIL", "UKOIL", "BTCUSD", "ETHUSD",
    "AAPL", "MSFT", "NVDA", "AMZN", "META", "TSLA", "SPY", "QQQ",
}

FOREX_BASES = {
    "EURUSD": ("EUR", "USD"), "GBPUSD": ("GBP", "USD"), "USDJPY": ("USD", "JPY"),
    "USDCHF": ("USD", "CHF"), "AUDUSD": ("AUD", "USD"), "USDCAD": ("USD", "CAD"),
    "NZDUSD": ("NZD", "USD"), "EURGBP": ("EUR", "GBP"), "EURJPY": ("EUR", "JPY"),
    "GBPJPY": ("GBP", "JPY"), "AUDJPY": ("AUD", "JPY"), "EURAUD": ("EUR", "AUD"),
    "EURCHF": ("EUR", "CHF"), "GBPCHF": ("GBP", "CHF"), "AUDCAD": ("AUD", "CAD"),
    "AUDCHF": ("AUD", "CHF"), "CADJPY": ("CAD", "JPY"), "CHFJPY": ("CHF", "JPY"),
    "NZDJPY": ("NZD", "JPY"), "NZDCHF": ("NZD", "CHF"),
}

STOOQ = {
    "AAPL": "aapl.us", "MSFT": "msft.us", "NVDA": "nvda.us", "AMZN": "amzn.us",
    "META": "meta.us", "TSLA": "tsla.us", "SPY": "spy.us", "QQQ": "qqq.us",
}

YAHOO_FUTURES = {
    # Yahoo Finance futures symbols: WTI crude and Brent crude.
    "USOIL": "CL=F",
    "UKOIL": "BZ=F",
}


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


async def _json(url: str, params: dict | None = None) -> dict | list:
    try:
        async with httpx.AsyncClient(timeout=8.0, follow_redirects=True) as client:
            response = await client.get(url, params=params)
            response.raise_for_status()
            return response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Market provider connection error: {exc}") from exc


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
        "service": "iswan-public-market-engine",
        "provider": "public-no-key",
        "policy": "no-fake-prices",
        "oilProvider": "Yahoo Finance public chart endpoint",
    }


async def _gold_quote() -> MarketQuote | None:
    data = await _json("https://api.metals.live/v1/spot")
    if isinstance(data, list):
        for row in data:
            if isinstance(row, dict) and row.get("gold") is not None:
                return MarketQuote(
                    symbol="XAUUSD", price=float(row["gold"]), marketTime=_now_ms(),
                    source="metals.live public spot endpoint", live=True, freshness="public-spot",
                )
    return None


async def _forex_quotes(requested: list[str]) -> dict[str, tuple[float, int]]:
    data = await _json("https://open.er-api.com/v6/latest/USD")
    rates = data.get("rates", {}) if isinstance(data, dict) else {}
    usd: dict[str, float] = {"USD": 1.0}
    usd.update({k: float(v) for k, v in rates.items() if isinstance(v, (int, float))})
    result: dict[str, tuple[float, int]] = {}
    for symbol in requested:
        base, quote = FOREX_BASES[symbol]
        if base in usd and quote in usd and usd[base] != 0:
            result[symbol] = (usd[quote] / usd[base], _now_ms())
    return result


async def _crypto_one(symbol: str) -> tuple[str, float, float | None, int] | None:
    pair = symbol.replace("USD", "USDT")
    data = await _json("https://api.binance.com/api/v3/ticker/24hr", {"symbol": pair})
    if isinstance(data, dict) and data.get("lastPrice") is not None:
        return symbol, float(data["lastPrice"]), float(data.get("priceChangePercent", 0.0)), _now_ms()
    return None


async def _crypto_quotes(requested: list[str]) -> dict[str, tuple[float, float | None, int]]:
    rows = await asyncio.gather(*(_crypto_one(symbol) for symbol in requested))
    return {symbol: (price, change, ts) for row in rows if row for symbol, price, change, ts in [row]}


async def _stock_one(symbol: str) -> tuple[str, float, int] | None:
    ticker = STOOQ[symbol]
    data = await _json("https://stooq.com/q/l/", {"s": ticker, "f": "sd2t2ohlcv", "h": "", "e": "json"})
    rows = data.get("data", []) if isinstance(data, dict) else []
    if rows:
        close = rows[0].get("close")
        if close not in (None, "N/D"):
            return symbol, float(close), _now_ms()
    return None


async def _stock_quotes(requested: list[str]) -> dict[str, tuple[float, float | None, int]]:
    rows = await asyncio.gather(*(_stock_one(symbol) for symbol in requested))
    return {symbol: (price, None, ts) for row in rows if row for symbol, price, ts in [row]}


async def _yahoo_oil_one(symbol: str) -> MarketQuote | None:
    ticker = YAHOO_FUTURES[symbol]
    data = await _json(
        f"https://query1.finance.yahoo.com/v8/finance/chart/{ticker}",
        {"range": "1d", "interval": "1m", "includePrePost": "true", "events": "div,splits"},
    )
    chart = data.get("chart", {}) if isinstance(data, dict) else {}
    result_rows = chart.get("result") or []
    if not result_rows:
        return None
    result = result_rows[0]
    meta = result.get("meta", {})
    price = meta.get("regularMarketPrice")
    if price is None:
        indicators = result.get("indicators", {})
        quotes = indicators.get("quote") or []
        closes = quotes[0].get("close") if quotes else None
        if closes:
            valid = [x for x in closes if x is not None]
            price = valid[-1] if valid else None
    if price is None:
        return None
    previous = meta.get("previousClose")
    change = None
    if previous not in (None, 0):
        change = (float(price) - float(previous)) / float(previous) * 100.0
    market_time = meta.get("regularMarketTime")
    market_ms = int(market_time * 1000) if market_time else _now_ms()
    return MarketQuote(
        symbol=symbol,
        price=float(price),
        changePercent=change,
        marketTime=market_ms,
        source="Yahoo Finance public chart endpoint",
        # Yahoo's commodity/futures quote can be exchange-delayed. Do not label
        # it as tick-real-time merely because the endpoint is polled every second.
        live=False,
        freshness="Yahoo-futures-quote",
    )


async def _yahoo_oil_quotes(requested: list[str]) -> dict[str, MarketQuote]:
    rows = await asyncio.gather(*(_yahoo_oil_one(symbol) for symbol in requested), return_exceptions=True)
    result: dict[str, MarketQuote] = {}
    for row in rows:
        if isinstance(row, MarketQuote):
            result[row.symbol] = row
    return result


@app.get("/v1/quotes", response_model=list[MarketQuote])
async def quotes(symbols: str) -> list[MarketQuote]:
    requested = [s.strip().upper() for s in symbols.split(",") if s.strip()]
    unknown = [s for s in requested if s not in SYMBOLS]
    if unknown:
        raise HTTPException(status_code=404, detail=f"Unsupported symbols: {', '.join(unknown)}")
    result: dict[str, MarketQuote] = {}

    if "XAUUSD" in requested:
        gold = await _gold_quote()
        if gold is not None:
            result["XAUUSD"] = gold

    forex = [s for s in requested if s in FOREX_BASES]
    if forex:
        for symbol, (price, ts) in (await _forex_quotes(forex)).items():
            result[symbol] = MarketQuote(
                symbol=symbol, price=price, marketTime=ts,
                source="open.er-api.com", live=False, freshness="reference-rate",
            )

    crypto = [s for s in requested if s in {"BTCUSD", "ETHUSD"}]
    if crypto:
        for symbol, (price, change, ts) in (await _crypto_quotes(crypto)).items():
            result[symbol] = MarketQuote(
                symbol=symbol, price=price, changePercent=change, marketTime=ts,
                source="Binance public market-data API", live=True, freshness="real-time-public",
            )

    stocks = [s for s in requested if s in STOOQ]
    if stocks:
        for symbol, (price, change, ts) in (await _stock_quotes(stocks)).items():
            result[symbol] = MarketQuote(
                symbol=symbol, price=price, changePercent=change, marketTime=ts,
                source="Stooq", live=False, freshness="provider-quote",
            )

    oil = [s for s in requested if s in YAHOO_FUTURES]
    if oil:
        result.update(await _yahoo_oil_quotes(oil))

    return [result[s] for s in requested if s in result]


@app.get("/v1/candles", response_model=list[Candle])
async def candles(symbol: str, timeframe: str = "5min", limit: int = 160) -> list[Candle]:
    symbol = symbol.upper().strip()
    if symbol not in SYMBOLS:
        raise HTTPException(status_code=404, detail=f"Unsupported symbol: {symbol}")
    limit = max(21, min(limit, 1000))
    if symbol in {"BTCUSD", "ETHUSD"}:
        intervals = {"1min": "1m", "5min": "5m", "15min": "15m", "30min": "30m", "1h": "1h", "4h": "4h", "1day": "1d"}
        interval = intervals.get(timeframe.lower())
        if interval is None:
            raise HTTPException(status_code=400, detail=f"Unsupported timeframe: {timeframe}")
        pair = symbol.replace("USD", "USDT")
        rows = await _json("https://api.binance.com/api/v3/klines", {"symbol": pair, "interval": interval, "limit": limit})
        return [Candle(time=int(r[0]), open=float(r[1]), high=float(r[2]), low=float(r[3]), close=float(r[4]), volume=float(r[5])) for r in rows]
    raise HTTPException(status_code=503, detail="Candles for this market require a historical-data provider; no fake candles are generated")


@app.post("/v1/analyze", response_model=AnalysisResponse)
def analyze(request: AnalysisRequest) -> AnalysisResponse:
    result = analyze_closes([c.close for c in request.candles])
    return AnalysisResponse(symbol=request.symbol.upper(), signal=result.signal, confidence=result.confidence, trend=result.trend, rsi=result.rsi)
