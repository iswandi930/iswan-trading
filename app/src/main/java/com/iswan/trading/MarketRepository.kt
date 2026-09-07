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

    @Volatile
    var lastError: String? = null
        private set

    suspend fun getPrices(symbols: List<String>): Map<String, MarketQuote> = withContext(Dispatchers.IO) {
        val baseUrl = BackendConfig.baseUrl
        if (symbols.isEmpty()) {
            lastError = null
            return@withContext emptyMap()
        }
        if (baseUrl.isBlank()) {
            lastError = "Backend URL belum dikonfigurasi"
            return@withContext emptyMap()
        }
        try {
            val url = baseUrl.trimEnd('/') + "/v1/quotes?symbols=" + symbols.joinToString(",")
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastError = "Server harga HTTP ${response.code}"
                    return@withContext emptyMap()
                }
                val body = response.body?.string().orEmpty()
                val array = JSONArray(body.ifBlank { "[]" })
                val result = buildMap {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val price = item.getDouble("price")
                        if (!price.isFinite()) continue
                        put(item.getString("symbol"), MarketQuote(
                            price = price,
                            changePercent = if (item.isNull("changePercent")) null else item.optDouble("changePercent"),
                            marketTime = if (item.isNull("marketTime")) null else item.optLong("marketTime")
                        ))
                    }
                }
                lastError = if (result.isEmpty()) "Server harga tidak mengembalikan data" else null
                return@withContext result
            }
        } catch (e: Exception) {
            lastError = e.message?.take(120) ?: "Gagal mengambil harga"
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
