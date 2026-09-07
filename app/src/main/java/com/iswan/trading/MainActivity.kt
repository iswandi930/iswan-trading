package com.iswan.trading

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IswanTradingApp(applicationContext) }
    }
}

@Composable
private fun IswanTradingApp(context: Context) {
    val repository = remember { MarketRepository() }
    val candleRepository = remember { CandleRepository() }
    var screen by remember { mutableStateOf("Markets") }
    var selected by remember { mutableStateOf<String?>(null) }
    var prices by remember { mutableStateOf<Map<String, MarketQuote>>(emptyMap()) }
    var watchlist by remember { mutableStateOf(loadWatchlist(context)) }
    var pythonBaseUrl by remember { mutableStateOf(loadPythonBaseUrl(context)) }
    val markets = remember { MarketCatalog.markets }

    LaunchedEffect(Unit) {
        while (isActive) {
            val next = repository.getPrices(markets.map { it.symbol })
            if (next.isNotEmpty()) prices = next
            delay(1000)
        }
    }

    MaterialTheme(darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                if (selected == null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ISWAN TRADING", fontWeight = FontWeight.Bold)
                        Text(if (prices.isNotEmpty()) "● DATA LIVE" else "● CONNECTING", color = Color.Gray)
                    }
                    when (screen) {
                        "Markets" -> MarketsScreen(markets, prices, watchlist, { selected = it }) { symbol ->
                            watchlist = toggleWatchlist(context, watchlist, symbol)
                        }
                        "Watchlist" -> MarketsScreen(markets.filter { watchlist.contains(it.symbol) }, prices, watchlist, { selected = it }) { symbol ->
                            watchlist = toggleWatchlist(context, watchlist, symbol)
                        }
                        "Analysis" -> AnalysisScreen(markets, candleRepository, pythonBaseUrl)
                        else -> SettingsScreen(pythonBaseUrl) { url ->
                            pythonBaseUrl = url
                            savePythonBaseUrl(context, url)
                        }
                    }
                    NavigationBar {
                        listOf("Markets", "Watchlist", "Analysis", "Settings").forEach { item ->
                            NavigationBarItem(selected = screen == item, onClick = { screen = item }, icon = { Text(item.take(1)) }, label = { Text(item) })
                        }
                    }
                } else {
                    MarketDetailScreen(selected!!, prices[selected], candleRepository) { selected = null }
                }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { repository.close() } }
}

@Composable
private fun MarketsScreen(
    markets: List<Market>,
    prices: Map<String, MarketQuote>,
    watchlist: Set<String>,
    onSelect: (String) -> Unit,
    onToggleWatchlist: (String) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(markets, key = { it.symbol }) { market ->
            val quote = prices[market.symbol]
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onSelect(market.symbol) }) {
                        Text(market.symbol, fontWeight = FontWeight.Bold)
                        Text(market.name, color = Color.Gray)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(quote?.price?.let { "%.5f".format(it) } ?: "—", fontWeight = FontWeight.Bold)
                        Text(quote?.changePercent?.let { "%+.2f%%".format(it) } ?: "—", color = Color.Gray)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(if (watchlist.contains(market.symbol)) "★" else "☆", modifier = Modifier.clickable { onToggleWatchlist(market.symbol) }, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MarketDetailScreen(symbol: String, quote: MarketQuote?, repo: CandleRepository, onBack: () -> Unit) {
    var range by remember { mutableStateOf("1d") }
    var candles by remember(symbol, range) { mutableStateOf<List<Candle>>(emptyList()) }
    LaunchedEffect(symbol, range) {
        while (isActive) {
            val next = repo.getCandles(listOf(symbol), range)[symbol].orEmpty()
            if (next.isNotEmpty()) candles = next
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹  $symbol", fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onBack() })
            Text(quote?.price?.let { "%.5f".format(it) } ?: "—")
        }
        Text(quote?.changePercent?.let { "%+.2f%%".format(it) } ?: "—", color = Color.Gray)
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1d", "5d", "1mo").forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r) }) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) { CandleChart(candles) }
        Text("Sinyal adalah skor teknikal, bukan jaminan profit. Akurasi harus divalidasi dengan backtest.", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CandleChart(candles: List<Candle>) {
    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
        val visible = candles.takeLast(80)
        if (visible.isEmpty()) return@Canvas
        val minPrice = visible.minOf { it.low }
        val maxPrice = visible.maxOf { it.high }
        val span = (maxPrice - minPrice).coerceAtLeast(1e-9)
        val step = size.width / visible.size.toFloat()
        fun y(price: Double): Float = size.height - (((price - minPrice) / span) * size.height).toFloat()
        visible.forEachIndexed { index, candle ->
            val x = step * (index + 0.5f)
            drawLine(color = Color.White, start = Offset(x, y(candle.high)), end = Offset(x, y(candle.low)), strokeWidth = 1.5f)
            val top = y(maxOf(candle.open, candle.close))
            val bottom = y(minOf(candle.open, candle.close))
            drawRect(color = Color.White, topLeft = Offset(x - step * 0.3f, top), size = Size(step * 0.6f, (bottom - top).coerceAtLeast(2f)))
        }
    }
}

@Composable
private fun AnalysisScreen(markets: List<Market>, repo: CandleRepository, pythonBaseUrl: String) {
    var data by remember { mutableStateOf<Map<String, AnalysisResult>>(emptyMap()) }
    var refreshing by remember { mutableStateOf(false) }
    val pythonClient = remember(pythonBaseUrl) { PythonAnalysisClient(pythonBaseUrl) }

    DisposableEffect(pythonBaseUrl) {
        onDispose { pythonClient.close() }
    }

    LaunchedEffect(pythonBaseUrl) {
        while (isActive) {
            refreshing = true
            val candles = repo.getCandles(markets.map { it.symbol }, "1d", "5m")
            if (candles.isNotEmpty()) {
                val results = mutableMapOf<String, AnalysisResult>()
                for (market in markets) {
                    val series = candles[market.symbol].orEmpty()
                    if (series.isEmpty()) continue
                    val pythonResult = pythonClient.analyze(market.symbol, series)
                    results[market.symbol] = pythonResult ?: analyze(series)
                }
                if (results.isNotEmpty()) data = results
            }
            refreshing = false
            delay(10000)
        }
    }

    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                if (refreshing) "ANALYSIS • memperbarui…"
                else if (pythonBaseUrl.isBlank()) "ANALYSIS • lokal • refresh 10 detik"
                else "ANALYSIS • Python + fallback lokal • refresh 10 detik",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(markets, key = { it.symbol }) { market ->
            val result = data[market.symbol]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(market.symbol, fontWeight = FontWeight.Bold)
                    Text(result?.let { "${it.signal.name}  ${it.confidence}%" } ?: "Mengambil data…")
                    Text(result?.let { "Trend: ${it.trend}   RSI: ${it.rsi?.let { v -> "%.1f".format(v) } ?: "—"}" } ?: "", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(pythonBaseUrl: String, onSavePythonUrl: (String) -> Unit) {
    var draftUrl by remember(pythonBaseUrl) { mutableStateOf(pythonBaseUrl) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Refresh harga: 1 detik")
        Text("Data pasar berasal dari feed publik internet. Waktu dan ketersediaan harga dapat berbeda menurut instrumen/provider.")
        Text("Python Analysis Engine", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = draftUrl,
            onValueChange = { draftUrl = it; saved = false },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("URL server Python") },
            placeholder = { Text("https://alamat-server-kamu") }
        )
        Button(
            onClick = {
                val normalized = draftUrl.trim().trimEnd('/')
                draftUrl = normalized
                onSavePythonUrl(normalized)
                saved = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Simpan URL Python") }
        Text(
            when {
                saved && draftUrl.isNotBlank() -> "URL Python tersimpan. Analysis akan mencoba Python setiap 10 detik."
                draftUrl.isNotBlank() -> "Python siap dikonfigurasi. Pastikan server dapat diakses dari internet oleh HP."
                else -> "Python belum terhubung. Analysis tetap berjalan memakai mesin teknikal lokal."
            },
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall
        )
        Text("Jangan gunakan http://127.0.0.1 atau localhost di HP; alamat itu menunjuk ke HP sendiri, bukan server Python.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Text("Integrasi MT4 broker memerlukan API/bridge broker yang sesuai.", color = Color.Gray)
    }
}

private fun loadWatchlist(context: Context): Set<String> = context.getSharedPreferences("iswan", Context.MODE_PRIVATE).getStringSet("watchlist", emptySet())?.toSet() ?: emptySet()

private fun toggleWatchlist(context: Context, current: Set<String>, symbol: String): Set<String> {
    val updated = current.toMutableSet().apply { if (!add(symbol)) remove(symbol) }.toSet()
    context.getSharedPreferences("iswan", Context.MODE_PRIVATE).edit().putStringSet("watchlist", updated).apply()
    return updated
}

private fun loadPythonBaseUrl(context: Context): String =
    context.getSharedPreferences("iswan", Context.MODE_PRIVATE).getString("python_base_url", "") ?: ""

private fun savePythonBaseUrl(context: Context, url: String) {
    context.getSharedPreferences("iswan", Context.MODE_PRIVATE).edit().putString("python_base_url", url).apply()
}

private fun analyze(candles: List<Candle>): AnalysisResult {
    if (candles.isEmpty()) return AnalysisResult(Signal.NEUTRAL, 0, "N/A", null)
    val closes = candles.map { it.close }
    val ema9 = ema(closes, 9)
    val ema21 = ema(closes, 21)
    val sma20 = closes.takeLast(20).average()
    val rsi = rsi(closes, 14)
    val bullish = ema9 > ema21 && closes.last() > sma20 && (rsi == null || rsi < 70)
    val bearish = ema9 < ema21 && closes.last() < sma20 && (rsi == null || rsi > 30)
    val signal = when { bullish -> Signal.BUY; bearish -> Signal.SELL; else -> Signal.NEUTRAL }
    val confidence = listOf(ema9 > ema21, closes.last() > sma20, rsi != null && rsi > 50).count { it } * 25
    return AnalysisResult(signal, confidence, if (ema9 >= ema21) "BULLISH" else "BEARISH", rsi)
}

private fun ema(values: List<Double>, period: Int): Double {
    val k = 2.0 / (period + 1)
    var e = values.take(period).average()
    for (v in values.drop(period)) e = v * k + e * (1 - k)
    return e
}

private fun rsi(values: List<Double>, period: Int): Double? {
    if (values.size <= period) return null
    var gain = 0.0
    var loss = 0.0
    for (i in 1..period) {
        val d = values[i] - values[i - 1]
        if (d >= 0) gain += d else loss -= d
    }
    gain /= period
    loss /= period
    for (i in period + 1 until values.size) {
        val d = values[i] - values[i - 1]
        gain = (gain * (period - 1) + maxOf(d, 0.0)) / period
        loss = (loss * (period - 1) + maxOf(-d, 0.0)) / period
    }
    return if (loss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + gain / loss)
}

enum class Signal { BUY, SELL, NEUTRAL }
data class AnalysisResult(val signal: Signal, val confidence: Int, val trend: String, val rsi: Double?)
