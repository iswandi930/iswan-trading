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

private const val DEFAULT_BACKEND_URL = "https://iswan-market-backend-production.up.railway.app"

private data class TimeframeOption(val label: String, val apiValue: String)

private val TIMEFRAMES = listOf(
    TimeframeOption("1M", "1m"),
    TimeframeOption("5M", "5m"),
    TimeframeOption("15M", "15m"),
    TimeframeOption("30M", "30m"),
    TimeframeOption("1H", "1h"),
    TimeframeOption("4H", "4h"),
    TimeframeOption("1D", "1d")
)

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
                        Text(
                            if (prices.isNotEmpty()) "● YAHOO • UPDATE 1s" else "● CONNECTING",
                            color = Color.Gray
                        )
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

    DisposableEffect(Unit) { onDispose { repository.close(); candleRepository.close() } }
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
    var timeframe by remember { mutableStateOf(TIMEFRAMES[1]) }
    var candles by remember(symbol, timeframe) { mutableStateOf<List<Candle>>(emptyList()) }

    LaunchedEffect(symbol, timeframe) {
        while (isActive) {
            val next = repo.getCandles(symbol, "1d", timeframe.apiValue)
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
        Text("Yahoo Finance • TF ${timeframe.label} • refresh 1 detik", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TIMEFRAMES.forEach { option ->
                FilterChip(
                    selected = timeframe.apiValue == option.apiValue,
                    onClick = { timeframe = option },
                    label = { Text(option.label) }
                )
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) { CandleChart(candles) }
        Text(
            "Sinyal adalah skor teknikal, bukan jaminan profit. Akurasi harus divalidasi dengan backtest.",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
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
        Text("Sumber harga & candle: Yahoo Finance")
        Text("Refresh data aplikasi: 1 detik")
        Text("Timeframe chart: 1M • 5M • 15M • 30M • 1H • 4H • 1D")
        Text("Aplikasi mengambil quote dan candle dari backend yang memakai Yahoo Finance.")
        Text("Catatan: refresh 1 detik tidak mengubah feed Yahoo yang mungkin tertunda untuk instrumen tertentu.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Text("Python / Market Backend", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = draftUrl,
            onValueChange = { draftUrl = it; saved = false },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("URL server backend") },
            placeholder = { Text(DEFAULT_BACKEND_URL) }
        )
        Button(
            onClick = {
                val normalized = draftUrl.trim().trimEnd('/')
                draftUrl = normalized
                onSavePythonUrl(normalized)
                saved = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Simpan URL Backend") }
        Text(
            when {
                saved && draftUrl.isNotBlank() -> "URL backend tersimpan. Harga dan candle akan memakai server ini."
                draftUrl.isNotBlank() -> "Backend siap dikonfigurasi. Pastikan server dapat diakses dari internet oleh HP."
                else -> "Backend otomatis menggunakan server Iswan Trading."
            },
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall
        )
        Text("Jangan gunakan http://127.0.0.1 atau localhost di HP; alamat itu menunjuk ke HP sendiri, bukan server.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

private fun loadWatchlist(context: Context): Set<String> = context.getSharedPreferences("iswan", Context.MODE_PRIVATE).getStringSet("watchlist", emptySet())?.toSet() ?: emptySet()

private fun toggleWatchlist(context: Context, current: Set<String>, symbol: String): Set<String> {
    val updated = current.toMutableSet().apply { if (!add(symbol)) remove(symbol) }.toSet()
    context.getSharedPreferences("iswan", Context.MODE_PRIVATE).edit().putStringSet("watchlist", updated).apply()
    return updated
}

private fun loadPythonBaseUrl(context: Context): String {
    val url = context.getSharedPreferences("iswan", Context.MODE_PRIVATE).getString("python_base_url", DEFAULT_BACKEND_URL).orEmpty()
    val normalized = url.trim().trimEnd('/').ifBlank { DEFAULT_BACKEND_URL }
    BackendConfig.baseUrl = normalized
    return normalized
}

private fun savePythonBaseUrl(context: Context, url: String) {
    val normalized = url.trim().trimEnd('/').ifBlank { DEFAULT_BACKEND_URL }
    context.getSharedPreferences("iswan", Context.MODE_PRIVATE).edit().putString("python_base_url", normalized).apply()
    BackendConfig.baseUrl = normalized
}

private fun analyze(candles: List<Candle>): AnalysisResult {
    if (candles.size < 21) return AnalysisResult(Signal.NEUTRAL, 0, "N/A", null)
    val closes = candles.map { it.close }
    val ema9 = ema(closes, 9)
    val ema21 = ema(closes, 21)
    val sma20 = closes.takeLast(20).average()
    val rsi = rsi(closes, 14)
    val last = closes.last()
    val bullEma = ema9 > ema21
    val bearEma = ema9 < ema21
    val bullSma = last > sma20
    val bearSma = last < sma20
    val bullRsi = rsi?.let { it in 50.0..69.999 } ?: false
    val bearRsi = rsi?.let { it in 30.001..50.0 } ?: false
    val bullish = bullEma && bullSma && (rsi == null || rsi < 70)
    val bearish = bearEma && bearSma && (rsi == null || rsi > 30)
    val signal = when { bullish -> Signal.BUY; bearish -> Signal.SELL; else -> Signal.NEUTRAL }
    val bullScore = listOf(bullEma to 40, bullSma to 35, bullRsi to 25).sumOf { (ok, weight) -> if (ok) weight else 0 }
    val bearScore = listOf(bearEma to 40, bearSma to 35, bearRsi to 25).sumOf { (ok, weight) -> if (ok) weight else 0 }
    val confidence = when (signal) {
        Signal.BUY -> bullScore
        Signal.SELL -> bearScore
        Signal.NEUTRAL -> maxOf(bullScore, bearScore).coerceAtMost(49)
    }
    return AnalysisResult(signal, confidence, if (bullScore >= bearScore) "BULLISH" else "BEARISH", rsi)
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
