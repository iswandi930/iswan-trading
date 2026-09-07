package com.iswan.trading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PythonAnalysisClient(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    init {
        if (baseUrl.isNotBlank()) BackendConfig.baseUrl = baseUrl.trimEnd('/')
    }

    suspend fun analyze(symbol: String, candles: List<Candle>): AnalysisResult? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || candles.isEmpty()) return@withContext null
        try {
            val payload = JSONObject().apply {
                put("symbol", symbol)
                put("candles", JSONArray().apply {
                    candles.forEach { c -> put(JSONObject().apply {
                        put("time", c.time)
                        put("open", c.open)
                        put("high", c.high)
                        put("low", c.low)
                        put("close", c.close)
                        put("volume", c.volume)
                    }) }
                })
            }
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/v1/analyze")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: return@withContext null)
                val signal = runCatching { Signal.valueOf(json.optString("signal", "NEUTRAL").uppercase()) }.getOrDefault(Signal.NEUTRAL)
                AnalysisResult(signal, json.optInt("confidence", 0), json.optString("trend", "N/A"), json.optDouble("rsi", Double.NaN).takeUnless { it.isNaN() })
            }
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
