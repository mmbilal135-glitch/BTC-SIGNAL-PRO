package com.iybrand.btc_signal_pro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

private const val BASE = "https://api.india.delta.exchange"
private const val CHANNEL = "btc_signal_alerts"

data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
data class Signal(val side: String, val entry: Double, val stop: Double, val target1: Double, val target2: Double, val reason: String)

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { SignalScreen(this) }
    }
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "BTC signal alerts", NotificationManager.IMPORTANCE_HIGH))
    }
}

@Composable
fun SignalScreen(context: Context) {
    var price by remember { mutableStateOf("Loading...") }
    var trend by remember { mutableStateOf("Analyzing 15M trend…") }
    var signal by remember { mutableStateOf<Signal?>(null) }
    var status by remember { mutableStateOf("Connecting to Delta Exchange public market data") }
    var lastAlert by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("தமிழ் + English") }
    val scope = rememberCoroutineScope()

    fun alert(s: Signal) {
        val key = "${s.side}-${s.entry}"
        if (key == lastAlert) return
        lastAlert = key
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("BTC SIGNAL PRO • ${s.side}")
            .setContentText("Entry ${fmt(s.entry)} | SL ${fmt(s.stop)} | T1 ${fmt(s.target1)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) nm.notify(911, n)
        try { (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) { }
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).startTone(ToneGenerator.TONE_PROP_BEEP, 350) } catch (_: Exception) { }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                val ticker = getJson("$BASE/v2/tickers/BTCUSD").getJSONObject("result")
                val live = ticker.optDouble("close", ticker.optDouble("mark_price", Double.NaN))
                if (live.isFinite()) price = "${fmt(live)} USD"
                val c5 = getCandles("5m", 180)
                val c15 = getCandles("15m", 180)
                val s15 = analyze(c15)
                val s5 = analyze(c5)
                trend = if (s15 == "BUY") "BULLISH • 15M" else if (s15 == "SELL") "BEARISH • 15M" else "SIDEWAYS / WAIT • 15M"
                val latest = c5.lastOrNull()
                val candidate = if (latest != null && s15 == s5 && s5 != "WAIT") {
                    val entry = latest.close
                    val atr = atr(c5).coerceAtLeast(entry * 0.001)
                    if (s5 == "BUY") Signal("BUY", entry, entry - atr * 1.5, entry + atr * 1.5, entry + atr * 2.5, "15M bullish + 5M confirmation")
                    else Signal("SELL", entry, entry + atr * 1.5, entry - atr * 1.5, entry - atr * 2.5, "15M bearish + 5M confirmation")
                } else null
                signal = candidate
                if (candidate != null) alert(candidate)
                status = "Live data updated • ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
            } catch (e: Exception) { status = "Data connection issue: ${e.message ?: "retrying"}" }
            delay(15_000)
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1220)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("₿ BTC SIGNAL PRO", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("DELTA EXCHANGE • BTCUSD", color = Color.LightGray)
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF172338))) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("LIVE BTC PRICE", color = Color.LightGray)
                        Text(price, style = MaterialTheme.typography.headlineLarge, color = Color.White)
                        Text(status, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF172338))) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("15M TREND / 15 நிமிட போக்கு", color = Color.LightGray)
                        Text(trend, style = MaterialTheme.typography.titleLarge, color = if (trend.startsWith("BULL")) Color(0xFF4ADE80) else if (trend.startsWith("BEAR")) Color(0xFFF87171) else Color.White)
                    }
                }
                val s = signal
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF172338))) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("5M ENTRY SIGNAL / நுழைவு சிக்னல்", color = Color.LightGray)
                        Text(s?.side ?: "WAIT • காத்திரு", style = MaterialTheme.typography.headlineLarge, color = when(s?.side) { "BUY" -> Color(0xFF4ADE80); "SELL" -> Color(0xFFF87171); else -> Color.White })
                        if (s != null) {
                            Metric("Entry / நுழைவு", s.entry); Metric("Stop Loss / இழப்பு வரம்பு", s.stop); Metric("Target 1", s.target1); Metric("Target 2", s.target2)
                            Text(s.reason, color = Color.LightGray)
                        } else Text("15M trend and 5M setup do not agree. No trade signal.", color = Color.LightGray)
                    }
                }
                Text("Indicators: EMA 9/21 • VWAP • Supertrend", color = Color.LightGray)
                Text("Prototype only. Signals are rule-based, not guaranteed. Verify in demo before risking money.", color = Color(0xFFFFD166), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun Metric(label: String, value: Double) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Color.LightGray); Text(fmt(value), color = Color.White) } }

private fun getJson(url: String): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10000; c.readTimeout = 10000; c.setRequestProperty("Accept", "application/json")
    return try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
}
private fun getCandles(res: String, count: Int): List<Candle> {
    val end = System.currentTimeMillis() / 1000
    val start = end - count * if (res == "5m") 300 else 900
    val j = getJson("$BASE/v2/history/candles?resolution=$res&symbol=BTCUSD&start=$start&end=$end")
    val a = j.getJSONArray("result")
    return (0 until a.length()).map { i -> val o = a.getJSONObject(i); Candle(o.optLong("time"), o.optDouble("open"), o.optDouble("high"), o.optDouble("low"), o.optDouble("close"), o.optDouble("volume")) }.sortedBy { it.time }
}
private fun ema(values: List<Double>, period: Int): Double { if (values.size < period) return Double.NaN; val k = 2.0 / (period + 1); var e = values.take(period).average(); for (v in values.drop(period)) e = v * k + e * (1-k); return e }
private fun analyze(c: List<Candle>): String {
    if (c.size < 30) return "WAIT"
    val closes = c.map { it.close }; val e9 = ema(closes, 9); val e21 = ema(closes, 21)
    val recent = c.takeLast(20); val pv = recent.sumOf { ((it.high + it.low + it.close)/3) * it.volume }; val vol = recent.sumOf { it.volume }; val vwap = if (vol > 0) pv/vol else closes.last()
    val a = atr(c); val last = closes.last(); val prior = closes[closes.size-2]
val stBull = supertrendBullish(c)
val up = e9 > e21 && last > vwap && last > prior && a > 0 && stBull
val down = e9 < e21 && last < vwap && last < prior && a > 0 && !stBull
    return if (up) "BUY" else if (down) "SELL" else "WAIT"
}
private fun atr(c: List<Candle>): Double { if (c.size < 15) return 0.0; return c.takeLast(14).mapIndexed { i, x -> val prev = c.getOrNull(c.size-14+i-1)?.close ?: x.close; maxOf(x.high-x.low, abs(x.high-prev), abs(x.low-prev)) }.average() }

private fun supertrendBullish(
    c: List<Candle>,
    period: Int = 10,
    multiplier: Double = 3.0
): Boolean {
    if (c.size < period + 2) return false

    var prevUpper = 0.0
    var prevLower = 0.0
    var bullish = true

    for (i in 1 until c.size) {
        val from = (i - period + 1).coerceAtLeast(1)
        val trs = (from..i).map { j ->
            val prevClose = c[j - 1].close
            maxOf(
                c[j].high - c[j].low,
                kotlin.math.abs(c[j].high - prevClose),
                kotlin.math.abs(c[j].low - prevClose)
            )
        }

        val atrValue = trs.average()
        val mid = (c[i].high + c[i].low) / 2.0
        val basicUpper = mid + multiplier * atrValue
        val basicLower = mid - multiplier * atrValue

        val upper = if (
            i == 1 || basicUpper < prevUpper ||
            c[i - 1].close > prevUpper
        ) basicUpper else prevUpper

        val lower = if (
            i == 1 || basicLower > prevLower ||
            c[i - 1].close < prevLower
        ) basicLower else prevLower

        bullish = if (bullish) {
            c[i].close >= lower
        } else {
            c[i].close > upper
        }

        prevUpper = upper
        prevLower = lower
    }

    return bullish
}

private fun fmt(x: Double) = String.format(java.util.Locale.US, "%,.2f", x)
