package com.iswan.trading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

class CandleRepository {
    private val client = OkHttpClient.Builder()
        .callTimeout(7, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    suspend fun getCandles(symbols: List<String>, range: String = "1d", interval: String = "5m"): Map<String, List<Candle>> = withContext(Dispatchers.IO) {
        if (BackendConfig.baseUrl.isBlank()) return@withContext emptyMap()
        symbols.mapNotNull { symbol -> fetch(symbol, interval).takeIf { it.isNotEmpty() }?.let { symbol to it } }.toMap()
    }

    suspend fun getCandles(symbol: String, range: String = "1d", interval: String = "5m"): List<Candle> = withContext(Dispatchers.IO) {
        if (BackendConfig.baseUrl.isBlank()) emptyList() else fetch(symbol, interval)
    }

    private fun fetch(symbol: String, interval: String): List<Candle> {
        return try {
            val timeframe = when (interval.lowercase()) {
                "1m" -> "1Min"
                "5m" -> "5Min"
                "15m" -> "15Min"
                "30m" -> "30Min"
                "1h" -> "1Hour"
                "4h" -> "4Hour"
                "1d" -> "1Day"
                else -> "5Min"
            }
            val url = BackendConfig.baseUrl.trimEnd('/') + "/v1/candles?symbol=" + symbol + "&timeframe=" + timeframe + "&limit=160"
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val array = JSONArray(response.body?.string() ?: "[]")
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val o = item.getDouble("open")
                        val h = item.getDouble("high")
                        val l = item.getDouble("low")
                        val c = item.getDouble("close")
                        if (o.isFinite() && h.isFinite() && l.isFinite() && c.isFinite() && h >= maxOf(o, c) && l <= minOf(o, c)) {
                            add(Candle(item.getLong("time"), o, h, l, c, item.optDouble("volume", 0.0)))
                        }
                    }
                }.takeLast(160)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }
}
