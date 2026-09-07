package com.iswan.trading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class MarketQuote(val price: Double, val changePercent: Double?, val marketTime: Long?)

object BackendConfig {
    @Volatile
    var baseUrl: String = ""
}

class MarketRepository {
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun getPrices(symbols: List<String>): Map<String, MarketQuote> = withContext(Dispatchers.IO) {
        val baseUrl = BackendConfig.baseUrl
        if (baseUrl.isBlank() || symbols.isEmpty()) return@withContext emptyMap()
        try {
            val url = baseUrl.trimEnd('/') + "/v1/quotes?symbols=" + symbols.joinToString(",")
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyMap()
                val array = JSONArray(response.body?.string() ?: "[]")
                buildMap {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        put(item.getString("symbol"), MarketQuote(
                            price = item.getDouble("price"),
                            changePercent = if (item.isNull("changePercent")) null else item.optDouble("changePercent"),
                            marketTime = if (item.isNull("marketTime")) null else item.optLong("marketTime")
                        ))
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }
}
