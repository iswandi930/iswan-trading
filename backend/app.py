from __future__ import annotations

from fastapi import FastAPI
from pydantic import BaseModel, Field

from analysis_engine import analyze_closes

app = FastAPI(title="Iswan Trading Python Engine", version="0.1.0")


class AnalysisRequest(BaseModel):
    symbol: str = Field(min_length=1, max_length=32)
    closes: list[float] = Field(min_length=1, max_length=5000)


class AnalysisResponse(BaseModel):
    symbol: str
    signal: str
    confidence: int
    trend: str
    rsi: float | None


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "iswan-python-engine"}


@app.post("/v1/analyze", response_model=AnalysisResponse)
def analyze(request: AnalysisRequest) -> AnalysisResponse:
    result = analyze_closes(request.closes)
    return AnalysisResponse(
        symbol=request.symbol.upper(),
        signal=result.signal,
        confidence=result.confidence,
        trend=result.trend,
        rsi=result.rsi,
    )
