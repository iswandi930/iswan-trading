package com.iswan.trading

import android.os.Bundle
import android.content.Context
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private data class Market(val symbol: String, val name: String, val category: String, val price: Double? = null, val change: Double? = null)
private enum class Signal { BUY, SELL, NEUTRAL }
private data class AnalysisResult(val signal: Signal, val confidence: Int, val rsi: Double?, val trend: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { IswanTradingApp() } }
}

private fun analyze(c: List<Candle>): AnalysisResult {
    if (c.size < 30) return AnalysisResult(Signal.NEUTRAL, 0, null, "Insufficient data")
    val closes = c.map { it.close }
    val sma20 = closes.takeLast(20).average()
    val ema9 = ema(closes, 9)
    val ema21 = ema(closes, 21)
    val rsi = rsi(closes, 14)
    var score = 0
    if (ema9 > ema21) score++ else score--
    if (closes.last() > sma20) score++ else score--
    if (rsi != null) { if (rsi < 35) score++ else if (rsi > 65) score-- }
    val signal = when { score >= 2 -> Signal.BUY; score <= -2 -> Signal.SELL; else -> Signal.NEUTRAL }
    return AnalysisResult(signal, (50 + kotlin.math.abs(score) * 12).coerceAtMost(86), rsi, if (ema9 >= ema21) "Bullish" else "Bearish")
}

private fun ema(values: List<Double>, period: Int): Double {
    val k = 2.0 / (period + 1)
    var result = values.take(period).average()
    values.drop(period).forEach { result = it * k + result * (1 - k) }
    return result
}

private fun rsi(values: List<Double>, period: Int): Double? {
    if (values.size <= period) return null
    var gain = 0.0; var loss = 0.0
    for (i in values.size - period until values.size) { val d = values[i] - values[i - 1]; if (d >= 0) gain += d else loss -= d }
    if (loss == 0.0) return 100.0
    return 100.0 - 100.0 / (1.0 + gain / loss)
}

@Composable
fun IswanTradingApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selected by remember { mutableStateOf("Markets") }
    var selectedMarket by remember { mutableStateOf<Market?>(null) }
    var prices by remember { mutableStateOf<Map<String, MarketQuote>>(emptyMap()) }
    var watchlist by remember { mutableStateOf(loadWatchlist(context)) }
    val repository = remember { MarketRepository() }
    val candleRepository = remember { CandleRepository() }
    val markets = remember { listOf(
        Market("XAUUSD", "Gold / US Dollar", "Metals"), Market("EURUSD", "Euro / US Dollar", "Forex"),
        Market("GBPUSD", "Pound / US Dollar", "Forex"), Market("USDJPY", "US Dollar / Yen", "Forex"),
        Market("BTCUSDT", "Bitcoin / USDT", "Crypto"), Market("ETHUSDT", "Ethereum / USDT", "Crypto"),
        Market("NAS100", "Nasdaq 100", "Index"), Market("US30", "Dow Jones", "Index")
    ) }
    LaunchedEffect(Unit) {
        while (true) { prices = repository.getPrices(markets.map { it.symbol }); delay(1_000) }
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0F14), surface = Color(0xFF111820))) {
        Scaffold(bottomBar = { NavigationBar { listOf("Markets", "Watchlist", "Analysis", "Settings").forEach { item -> NavigationBarItem(selected = selected == item, onClick = { selected = item; selectedMarket = null }, icon = { Text(item.take(1)) }, label = { Text(item) }) } } }) { pad ->
            Column(Modifier.fillMaxSize().background(Color(0xFF0B0F14)).padding(pad)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("ISWAN TRADING", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Market terminal", color = Color.Gray) }
                    Text(if (prices.isNotEmpty()) "● LIVE" else "● CONNECTING", color = if (prices.isNotEmpty()) Color(0xFF66BB6A) else Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall)
                }
                when (selected) {
                    "Markets" -> if (selectedMarket == null) MarketList(markets.map { it.copy(price = prices[it.symbol]?.price, change = prices[it.symbol]?.changePercent) }, watchlist, { s -> watchlist = toggleWatch(context, watchlist, s) }) { selectedMarket = it } else MarketDetail(selectedMarket!!.copy(price = prices[selectedMarket!!.symbol]?.price, change = prices[selectedMarket!!.symbol]?.changePercent), candleRepository, watchlist.contains(selectedMarket!!.symbol), { watchlist = toggleWatch(context, watchlist, selectedMarket!!.symbol) }) { selectedMarket = null }
                    "Watchlist" -> MarketList(markets.filter { watchlist.contains(it.symbol) }.map { it.copy(price = prices[it.symbol]?.price, change = prices[it.symbol]?.changePercent) }, watchlist, { s -> watchlist = toggleWatch(context, watchlist, s) }) { selectedMarket = it }
                    "Analysis" -> AnalysisScreen(markets, candleRepository)
                    else -> SettingsScreen()
                }
            }
        }
    }
}

@Composable private fun MarketList(markets: List<Market>, watchlist: Set<String>, onWatch: (String) -> Unit, onClick: (Market) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("MARKETS", color = Color.Gray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(4.dp)) }
        items(markets) { m -> Card(Modifier.fillMaxWidth().clickable { onClick(m) }) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (watchlist.contains(m.symbol)) "★" else "☆", modifier = Modifier.clickable { onWatch(m.symbol) }.padding(end = 12.dp), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f)) { Text(m.symbol, fontWeight = FontWeight.Bold); Text(m.name, color = Color.Gray) }
            Column(horizontalAlignment = Alignment.End) { Text(m.price?.let { "%.5f".format(it) } ?: "—"); Text(m.change?.let { "%+.2f%%".format(it) } ?: "Waiting", color = if ((m.change ?: 0.0) >= 0) Color(0xFF66BB6A) else Color(0xFFEF5350), style = MaterialTheme.typography.labelSmall) }
        } } }
    }
}

@Composable private fun MarketDetail(m: Market, repo: CandleRepository, starred: Boolean, onStar: () -> Unit, back: () -> Unit) {
    var candles by remember(m.symbol) { mutableStateOf<List<Candle>>(emptyList()) }
    var range by remember { mutableStateOf("1d") }
    LaunchedEffect(m.symbol, range) { candles = repo.getCandles(m.symbol, range, "5m") }
    val result = remember(candles) { analyze(candles) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("‹  Kembali", modifier = Modifier.clickable { back() }, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.weight(1f)); Text(if (starred) "★" else "☆", modifier = Modifier.clickable { onStar() }, style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.height(12.dp)); Text(m.symbol, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(m.name, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) { Text(m.price?.let { "%.5f".format(it) } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.width(12.dp)); Text(m.change?.let { "%+.2f%%".format(it) } ?: "—") }
        Spacer(Modifier.height(12.dp)); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("1d", "5d", "1mo").forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r) }) } }
        Spacer(Modifier.height(10.dp)); Card(Modifier.fillMaxWidth().height(300.dp)) { if (candles.isNotEmpty()) CandleChart(candles) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Memuat candle…", color = Color.Gray) } }
        Spacer(Modifier.height(12.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("SIGNAL", fontWeight = FontWeight.Bold); Text(result.signal.name, fontWeight = FontWeight.Bold); Text("${result.confidence}%") }
        Text("Trend: ${result.trend}   RSI: ${result.rsi?.let { "%.1f".format(it) } ?: "—"}", color = Color.Gray)
        Text("Sinyal adalah skor teknikal, bukan jaminan profit. Akurasi harus divalidasi dengan backtest.", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun CandleChart(candles: List<Candle>) {
    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
        val visible = candles.takeLast(80); val min = visible.minOf { it.low }; val max = visible.maxOf { it.high }; val span = (max - min).coerceAtLeast(1e-9); val step = size.width / visible.size
        fun y(v: Double) = size.height - ((v - min) / span * size.height).toFloat()
        visible.forEachIndexed { i, c -> val x = step * (i + .5f); drawLine(Offset(x, y(c.high)), Offset(x, y(c.low)), strokeWidth = 1.5f); val top = y(maxOf(c.open, c.close)); val bottom = y(minOf(c.open, c.close)); drawRect(Color.White, Offset(x - step * .3f, top), androidx.compose.ui.geometry.Size(step * .6f, (bottom - top).coerceAtLeast(2f))) }
    }
}

@Composable private fun AnalysisScreen(markets: List<Market>, repo: CandleRepository) {
    var data by remember { mutableStateOf<Map<String, AnalysisResult>>(emptyMap()) }
    LaunchedEffect(Unit) { data = repo.getCandles(markets.map { it.symbol }).mapValues { analyze(it.value) } }
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(markets) { m -> val a = data[m.symbol]; Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(m.symbol, fontWeight = FontWeight.Bold); Text("Trend ${a?.trend ?: "Loading"} • RSI ${a?.rsi?.let { "%.1f".format(it) } ?: "—"}", color = Color.Gray) }; Text(a?.let { "${it.signal} ${it.confidence}%" } ?: "…", fontWeight = FontWeight.Bold) } } } } }
}

@Composable private fun SettingsScreen() { Column(Modifier.fillMaxSize().padding(20.dp)) { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(14.dp)); Text("Refresh harga: 1 detik", fontWeight = FontWeight.Bold); Text("Feed saat ini menggunakan data publik. Integrasi feed broker MT4 membutuhkan API/bridge broker.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp)); Spacer(Modifier.height(16.dp)); Text("Versi: 0.2", color = Color.Gray) } }

private fun loadWatchlist(context: Context): Set<String> = context.getSharedPreferences("iswan_trading", Context.MODE_PRIVATE).getStringSet("watchlist", emptySet()) ?: emptySet()
private fun toggleWatch(context: Context, current: Set<String>, symbol: String): Set<String> { val next = current.toMutableSet().apply { if (!add(symbol)) remove(symbol) }; context.getSharedPreferences("iswan_trading", Context.MODE_PRIVATE).edit().putStringSet("watchlist", next).apply(); return next }
