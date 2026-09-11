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

    @Volatile
    var lastError: String? = null
        private set

    suspend fun getCandles(symbols: List<String>, range: String = "1d", interval: String = "5m"): Map<String, List<Candle>> = withContext(Dispatchers.IO) {
        if (symbols.isEmpty()) {
            lastError = null
            return@withContext emptyMap()
        }
        if (BackendConfig.baseUrl.isBlank()) {
            lastError = "Backend URL belum dikonfigurasi"
            return@withContext emptyMap()
        }
        val result = symbols.mapNotNull { symbol -> fetch(symbol, interval).takeIf { it.isNotEmpty() }?.let { symbol to it } }.toMap()
        if (result.isEmpty() && lastError == null) lastError = "Server candle tidak mengembalikan data"
        else if (result.isNotEmpty()) lastError = null
        result
    }

    suspend fun getCandles(symbol: String, range: String = "1d", interval: String = "5m"): List<Candle> = withContext(Dispatchers.IO) {
        if (BackendConfig.baseUrl.isBlank()) {
            lastError = "Backend URL belum dikonfigurasi"
            return@withContext emptyList()
        }
        val result = fetch(symbol, interval)
        if (result.isEmpty() && lastError == null) lastError = "Server candle tidak mengembalikan data untuk $symbol"
        else if (result.isNotEmpty()) lastError = null
        result
    }

    private fun fetch(symbol: String, interval: String): List<Candle> {
        return try {
            // Backend menerima timeframe API dalam format 1m/5m/15m/30m/1h/4h/1d.
            // Jangan kirim label internal seperti 1Min atau 1Hour karena backend akan menolaknya.
            val timeframe = when (interval.lowercase()) {
                "1m", "1min", "1minute" -> "1m"
                "5m", "5min", "5minute" -> "5m"
                "15m", "15min", "15minute" -> "15m"
                "30m", "30min", "30minute" -> "30m"
                "1h", "1hour" -> "1h"
                "4h", "4hour" -> "4h"
                "1d", "1day" -> "1d"
                else -> "5m"
            }
            val url = BackendConfig.baseUrl.trimEnd('/') + "/v1/candles?symbol=" + symbol + "&timeframe=" + timeframe + "&limit=160"
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastError = "Server candle HTTP ${response.code}"
                    return emptyList()
                }
                val array = JSONArray(response.body?.string().orEmpty().ifBlank { "[]" })
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val time = item.getLong("time")
                        val o = item.getDouble("open")
                        val h = item.getDouble("high")
                        val l = item.getDouble("low")
                        val c = item.getDouble("close")
                        val volume = item.optDouble("volume", 0.0)
                        if (time > 0 && o.isFinite() && h.isFinite() && l.isFinite() && c.isFinite() && volume.isFinite() && h >= maxOf(o, c) && l <= minOf(o, c)) {
                            add(Candle(time, o, h, l, c, volume))
                        }
                    }
                }.takeLast(160)
            }
        } catch (e: Exception) {
            lastError = e.message?.take(120) ?: "Gagal mengambil candle"
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
