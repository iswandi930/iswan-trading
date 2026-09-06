package com.iswan.trading

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private data class Market(val symbol: String, val name: String, val category: String, val price: Double? = null, val change: String = "—")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { IswanTradingApp() } }
}

@Composable
fun IswanTradingApp() {
    var selected by remember { mutableStateOf("Markets") }
    var selectedMarket by remember { mutableStateOf<Market?>(null) }
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    val repository = remember { MarketRepository() }
    val markets = listOf(
        Market("XAUUSD", "Gold / US Dollar", "Metals"), Market("EURUSD", "Euro / US Dollar", "Forex"),
        Market("GBPUSD", "Pound / US Dollar", "Forex"), Market("USDJPY", "US Dollar / Yen", "Forex"),
        Market("BTCUSDT", "Bitcoin / USDT", "Crypto"), Market("ETHUSDT", "Ethereum / USDT", "Crypto"),
        Market("NAS100", "Nasdaq 100", "Index"), Market("US30", "Dow Jones", "Index")
    )
    LaunchedEffect(Unit) {
        while (true) { prices = repository.getPrices(markets.map { it.symbol }); delay(15_000) }
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0F14), surface = Color(0xFF111820))) {
        Scaffold(bottomBar = { NavigationBar { listOf("Markets", "Watchlist", "Analysis", "Settings").forEach { item -> NavigationBarItem(selected = selected == item, onClick = { selected = item; selectedMarket = null }, icon = {}, label = { Text(item) }) } } }) { pad ->
            Column(Modifier.fillMaxSize().background(Color(0xFF0B0F14)).padding(pad)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("ISWAN TRADING", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Market terminal", color = Color.Gray) }
                    Text(if (prices.isNotEmpty()) "● LIVE" else "● CONNECTING", color = if (prices.isNotEmpty()) Color(0xFF66BB6A) else Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall)
                }
                when (selected) {
                    "Markets" -> { val liveMarkets = markets.map { it.copy(price = prices[it.symbol]) }; if (selectedMarket == null) MarketList(liveMarkets) { selectedMarket = it } else MarketDetail(selectedMarket!!.copy(price = prices[selectedMarket!!.symbol])) { selectedMarket = null } }
                    "Watchlist" -> EmptyState("Watchlist", "Tambahkan simbol dari halaman Markets.")
                    "Analysis" -> EmptyState("Analysis", "Mesin analisis akan dihubungkan setelah data candle live tersedia.")
                    else -> EmptyState("Settings", "Pengaturan data feed, chart, dan notifikasi akan tersedia di sini.")
                }
            }
        }
    }
}

@Composable private fun MarketList(markets: List<Market>, onClick: (Market) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("MARKETS", color = Color.Gray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(4.dp)) }
        items(markets) { m -> Card(Modifier.fillMaxWidth().clickable { onClick(m) }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(m.symbol, fontWeight = FontWeight.Bold); Text(m.name, color = Color.Gray) }; Column(horizontalAlignment = Alignment.End) { Text(m.price?.let { "%.5f".format(it) } ?: "—"); Text(if (m.price != null) "LIVE" else "Waiting", color = Color.Gray, style = MaterialTheme.typography.labelSmall) } } } }
    }
}

@Composable private fun MarketDetail(m: Market, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("‹  Kembali", modifier = Modifier.clickable { back() }, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(18.dp)); Text(m.symbol, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(m.name, color = Color.Gray)
        Spacer(Modifier.height(10.dp)); Text(m.price?.let { "%.5f".format(it) } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp)); Card(Modifier.fillMaxWidth().height(300.dp)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("CHART\n\nCandle data: tahap berikutnya", color = Color.Gray) } }
        Spacer(Modifier.height(14.dp)); Text("LIVE PRICE FEED", fontWeight = FontWeight.Bold); Text("Harga diperbarui berkala dari provider data publik. Untuk trading-grade feed, provider broker/exchange akan dipasang pada tahap berikutnya.", color = Color.Gray)
    }
}

@Composable private fun EmptyState(title: String, text: String) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(text, color = Color.Gray) } }
