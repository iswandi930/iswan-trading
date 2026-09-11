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
    TimeframeOption("1M", "1m"), TimeframeOption("5M", "5m"), TimeframeOption("15M", "15m"),
    TimeframeOption("30M", "30m"), TimeframeOption("1H", "1h"), TimeframeOption("4H", "4h"), TimeframeOption("1D", "1d")
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
    var marketError by remember { mutableStateOf<String?>(null) }
    var watchlist by remember { mutableStateOf(loadWatchlist(context)) }
    var pythonBaseUrl by remember { mutableStateOf(loadPythonBaseUrl(context)) }
    val markets = remember { MarketCatalog.markets }

    LaunchedEffect(Unit) {
        while (isActive) {
            val next = repository.getPrices(markets.map { it.symbol })
            if (next.isNotEmpty()) prices = next
            marketError = repository.lastError
            delay(1000)
        }
    }

    MaterialTheme(darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                if (selected == null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("ISWAN TRADING", fontWeight = FontWeight.Bold)
                        Text(when {
                            marketError != null -> "● DATA ERROR"
                            prices.isNotEmpty() -> "● MARKET • UPDATE 1s"
                            else -> "● CONNECTING"
                        }, color = if (marketError != null) Color.Red else Color.Gray)
                    }
                    if (marketError != null) Text("Data: $marketError", color = Color.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
                    when (screen) {
                        "Markets" -> MarketsScreen(markets, prices, watchlist, { selected = it }) { symbol -> watchlist = toggleWatchlist(context, watchlist, symbol) }
                        "Watchlist" -> MarketsScreen(markets.filter { watchlist.contains(it.symbol) }, prices, watchlist, { selected = it }) { symbol -> watchlist = toggleWatchlist(context, watchlist, symbol) }
                        "Analysis" -> AnalysisScreen(markets, candleRepository, pythonBaseUrl)
                        else -> SettingsScreen(pythonBaseUrl) { url -> pythonBaseUrl = url; savePythonBaseUrl(context, url) }
                    }
                    NavigationBar {
                        listOf("Markets", "Watchlist", "Analysis", "Settings").forEach { item ->
                            NavigationBarItem(selected = screen == item, onClick = { screen = item }, icon = { Text(item.take(1)) }, label = { Text(item) })
                        }
                    }
                } else MarketDetailScreen(selected!!, prices[selected], candleRepository) { selected = null }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { repository.close(); candleRepository.close() } }
}

@Composable
private fun MarketsScreen(markets: List<Market>, prices: Map<String, MarketQuote>, watchlist: Set<String>, onSelect: (String) -> Unit, onToggleWatchlist: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(markets, key = { it.symbol }) { market ->
            val quote = prices[market.symbol]
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onSelect(market.symbol) }) { Text(market.symbol, fontWeight = FontWeight.Bold); Text(market.name, color = Color.Gray) }
                    Column(horizontalAlignment = Alignment.End) { Text(quote?.price?.let { "%.5f".format(it) } ?: "—", fontWeight = FontWeight.Bold); Text(quote?.changePercent?.let { "%+.2f%%".format(it) } ?: "—", color = Color.Gray) }
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
    var candleError by remember(symbol, timeframe) { mutableStateOf<String?>(null) }
    LaunchedEffect(symbol, timeframe) {
        while (isActive) {
            val next = repo.getCandles(symbol, "1d", timeframe.apiValue)
            if (next.isNotEmpty()) candles = next
            candleError = repo.lastError
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹  $symbol", fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onBack() })
            Text(quote?.price?.let { "%.5f".format(it) } ?: "—")
        }
        Text(quote?.changePercent?.let { "%+.2f%%".format(it) } ?: "—", color = Color.Gray)
        Text("Market data • TF ${timeframe.label} • refresh 1 detik", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        candleError?.let { Text("Data candle: $it", color = Color.Red, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TIMEFRAMES.forEach { option -> FilterChip(selected = timeframe.apiValue == option.apiValue, onClick = { timeframe = option }, label = { Text(option.label) }) }
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
        val minPrice = visible.minOf { it.low }; val maxPrice = visible.maxOf { it.high }; val span = (maxPrice - minPrice).coerceAtLeast(1e-9); val step = size.width / visible.size.toFloat()
        fun y(price: Double): Float = size.height - (((price - minPrice) / span) * size.height).toFloat()
        visible.forEachIndexed { index, candle ->
            val x = step * (index + 0.5f)
            drawLine(color = Color.White, start = Offset(x, y(candle.high)), end = Offset(x, y(candle.low)), strokeWidth = 1.5f)
            val top = y(maxOf(candle.open, candle.close)); val bottom = y(minOf(candle.open, candle.close))
            drawRect(color = Color.White, topLeft = Offset(x - step * 0.3f, top), size = Size(step * 0.6f, (bottom - top).coerceAtLeast(2f)))
        }
    }
}

@Composable
private fun AnalysisScreen(markets: List<Market>, repo: CandleRepository, pythonBaseUrl: String) {
    var data by remember { mutableStateOf<Map<String, AnalysisResult>>(emptyMap()) }
    var refreshing by remember { mutableStateOf(false) }
    val pythonClient = remember(pythonBaseUrl) { PythonAnalysisClient(pythonBaseUrl) }
    DisposableEffect(pythonBaseUrl) { onDispose { pythonClient.close() } }
    LaunchedEffect(pythonBaseUrl) {
        while (isActive) {
            refreshing = true
            val candles = repo.getCandles(markets.map { it.symbol }, "1d", "5m")
            if (candles.isNotEmpty()) {
                val results = mutableMapOf<String, AnalysisResult>()
                for (market in markets) {
                    val series = candles[market.symbol].orEmpty(); if (series.isEmpty()) continue
                    results[market.symbol] = pythonClient.analyze(market.symbol, series) ?: analyze(series)
                }
                if (results.isNotEmpty()) data = results
            }
            refreshing = false; delay(10000)
        }
    }
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(if (refreshing) "ANALYSIS • memperbarui…" else if (pythonBaseUrl.isBlank()) "ANALYSIS • lokal • refresh 10 detik" else "ANALYSIS • Python + fallback lokal • refresh 10 detik", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
        items(markets, key = { it.symbol }) { market ->
            val result = data[market.symbol]
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(market.symbol, fontWeight = FontWeight.Bold); Text(result?.let { "${it.signal} • ${it.confidence}% • ${it.trend}" } ?: "Menunggu data…", color = Color.Gray) } }
        }
    }
}

private fun loadWatchlist(context: Context): Set<String> = context.getSharedPreferences("iswan", Context.MODE_PRIVATE).getStringSet("watchlist", emptySet()) ?: emptySet()
private fun toggleWatchlist(context: Context, current: Set<String>, symbol: String): Set<String> { val next = current.toMutableSet().apply { if (!add(symbol)) remove(symbol) }; context.getSharedPreferences("iswan", Context.MODE_PRIVATE).edit().putStringSet("watchlist", next).apply(); return next }
private fun loadPythonBaseUrl(context: Context): String = context.getSharedPreferences("iswan", Context.MODE_PRIVATE).getString("python_url", "") ?: ""
private fun savePythonBaseUrl(context: Context, url: String) { context.getSharedPreferences("iswan", Context.MODE_PRIVATE).edit().putString("python_url", url.trim()).apply() }
