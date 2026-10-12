package com.halil.perpsbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * KURALLAR (kodun anayasası):
 *  1. TP ve SL pozisyon AÇILMADAN önce hesaplanır. Hesaplanamazsa işlem yok.
 *  2. Taraf hareketten seçilir: dump = short, pump = long.
 *  3. Boyut MEVCUT bakiyeden hesaplanır.
 *  4. Kaldıraç coine ve hedefe olan mesafeye göre ayarlanır.
 *  5. Kaybeden hızlı kesilir; açık pozisyona asla ekleme / ortalama yok.
 *
 * Bu sürüm KAĞIT MOD: gerçek Hyperliquid fiyatları, sanal bakiye.
 */

object Cfg {
    const val API = "https://api.hyperliquid.xyz/info"
    val COINS = listOf("BTC", "ETH", "SOL", "AVAX", "SUI")
    const val INTERVAL = "15m"
    const val INTERVAL_MS = 15 * 60 * 1000L

    // coin -> (kaldıraç tavanı, hareket eşiği ATR katı)
    val PROFILE = mapOf(
        "BTC" to Pair(20, 1.6),
        "ETH" to Pair(15, 1.7),
        "SOL" to Pair(10, 1.8),
        "AVAX" to Pair(8, 1.9),
        "SUI" to Pair(6, 2.0),
    )

    const val ATR_LEN = 14
    const val VOL_LEN = 20
    const val VOL_MULT = 1.3
    const val TP_ATR = 2.0
    const val SL_ATR = 1.0
    const val RISK_PCT = 0.01
    const val TARGET_ROE = 0.30
    const val MAX_MARGIN_PCT = 0.20
    const val MAX_OPEN = 3
    const val TIME_STOP_BARS = 8
    const val DAILY_LOSS_CAP = 0.04
    const val FEE_RATE = 0.00045
    const val START_BALANCE = 1000.0
    const val TICK_MS = 30_000L
}

class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

class Position(
    val coin: String, val side: String, val entry: Double, val tp: Double, val sl: Double,
    val size: Double, val lev: Int, val margin: Double, val riskUsd: Double, val rr: Double,
    val openedBar: Long, val openedAt: Long,
) {
    val isLong get() = side == "long"
    fun pnlAt(px: Double): Double {
        val d = if (isLong) 1 else -1
        return d * (px - entry) * size - Cfg.FEE_RATE * size * (entry + px)
    }
    fun toJson() = JSONObject().apply {
        put("coin", coin); put("side", side); put("entry", entry); put("tp", tp); put("sl", sl)
        put("size", size); put("lev", lev); put("margin", margin); put("riskUsd", riskUsd)
        put("rr", rr); put("openedBar", openedBar); put("openedAt", openedAt)
    }
    companion object {
        fun from(j: JSONObject) = Position(
            j.getString("coin"), j.getString("side"), j.getDouble("entry"), j.getDouble("tp"),
            j.getDouble("sl"), j.getDouble("size"), j.getInt("lev"), j.getDouble("margin"),
            j.getDouble("riskUsd"), j.getDouble("rr"), j.getLong("openedBar"), j.getLong("openedAt"),
        )
    }
}

class Trade(val coin: String, val side: String, val reason: String, val pnl: Double,
            val entry: Double, val exit: Double, val time: String) {
    fun toJson() = JSONObject().apply {
        put("coin", coin); put("side", side); put("reason", reason); put("pnl", pnl)
        put("entry", entry); put("exit", exit); put("time", time)
    }
    companion object {
        fun from(j: JSONObject) = Trade(j.getString("coin"), j.getString("side"), j.getString("reason"),
            j.getDouble("pnl"), j.getDouble("entry"), j.getDouble("exit"), j.getString("time"))
    }
}

object Engine {
    var balance = Cfg.START_BALANCE
    val positions = LinkedHashMap<String, Position>()
    val seenBars = HashMap<String, Long>()     // sinyal alınmış mum (aynı mumla iki kez girme)
    val checkedBars = HashMap<String, Long>()  // kontrol edilmiş son kapanmış mum (gereksiz istek yok)
    val trades = ArrayList<Trade>()
    val logs = ArrayList<String>()
    val prices = HashMap<String, Double>()
    var day = ""
    var dayStartBalance = Cfg.START_BALANCE
    var lastTick = 0L
    var lastError: String? = null
    private var loaded = false
    private val szDec = HashMap<String, Int>()

    private val tf = SimpleDateFormat("dd.MM HH:mm", Locale("tr"))
    private val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun now() = tf.format(Date())

    // ───────── kalıcılık ─────────
    private fun file(ctx: Context) = File(ctx.filesDir, "state.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) { day = df.format(Date()); return }
        try {
            val j = JSONObject(f.readText())
            balance = j.getDouble("balance")
            day = j.optString("day", df.format(Date()))
            dayStartBalance = j.optDouble("dayStart", balance)
            j.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) Position.from(a.getJSONObject(i)).also { positions[it.coin] = it } }
            j.optJSONArray("trades")?.let { a -> for (i in 0 until a.length()) trades.add(Trade.from(a.getJSONObject(i))) }
            j.optJSONArray("logs")?.let { a -> for (i in 0 until a.length()) logs.add(a.getString(i)) }
            j.optJSONObject("seen")?.let { o -> o.keys().forEach { k -> seenBars[k] = o.getLong(k) } }
        } catch (e: Exception) {
            log("Kayıt okunamadı, sıfırdan başlıyor: ${e.message}")
        }
    }

    @Synchronized
    fun save(ctx: Context) {
        val j = JSONObject()
        j.put("balance", balance); j.put("day", day); j.put("dayStart", dayStartBalance)
        j.put("positions", JSONArray().apply { positions.values.forEach { put(it.toJson()) } })
        j.put("trades", JSONArray().apply { trades.takeLast(300).forEach { put(it.toJson()) } })
        j.put("logs", JSONArray().apply { logs.takeLast(80).forEach { put(it) } })
        j.put("seen", JSONObject().apply { seenBars.forEach { (k, v) -> put(k, v) } })
        val tmp = File(ctx.filesDir, "state.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(file(ctx))
    }

    @Synchronized
    fun reset(ctx: Context) {
        balance = Cfg.START_BALANCE; dayStartBalance = balance; day = df.format(Date())
        positions.clear(); seenBars.clear(); checkedBars.clear(); trades.clear(); logs.clear()
        log("Sıfırlandı. Sanal bakiye ${fmt(balance)}$")
        save(ctx)
    }

    @Synchronized
    fun log(msg: String) {
        logs.add("${now()}  $msg")
        while (logs.size > 80) logs.removeAt(0)
    }

    /** Açık pozisyonların anlık kâr/zararı dahil bakiye */
    @Synchronized
    fun equity(): Double = balance + positions.values.sumOf { p -> prices[p.coin]?.let { p.pnlAt(it) } ?: 0.0 }

    // ───────── ağ ─────────
    private fun post(body: JSONObject): String {
        val c = URL(Cfg.API).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun fetchMids(): Map<String, Double> {
        val j = JSONObject(post(JSONObject().put("type", "allMids")))
        return Cfg.COINS.filter { j.has(it) }.associateWith { j.getString(it).toDouble() }
    }

    private fun fetchCandles(coin: String, n: Int = 60): List<Candle> {
        val end = System.currentTimeMillis()
        val req = JSONObject().put("coin", coin).put("interval", Cfg.INTERVAL)
            .put("startTime", end - n * Cfg.INTERVAL_MS).put("endTime", end)
        val a = JSONArray(post(JSONObject().put("type", "candleSnapshot").put("req", req)))
        val out = ArrayList<Candle>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Candle(o.getLong("t"), o.getString("o").toDouble(), o.getString("h").toDouble(),
                o.getString("l").toDouble(), o.getString("c").toDouble(), o.getString("v").toDouble()))
        }
        // sadece kapanmış mumlar
        if (out.isNotEmpty() && out.last().t + Cfg.INTERVAL_MS > end) out.removeAt(out.size - 1)
        return out
    }

    private fun szDecimals(coin: String): Int {
        if (szDec.isEmpty()) {
            val u = JSONObject(post(JSONObject().put("type", "meta"))).getJSONArray("universe")
            for (i in 0 until u.length()) u.getJSONObject(i).let { szDec[it.getString("name")] = it.getInt("szDecimals") }
        }
        return szDec[coin] ?: 2
    }

    // ───────── strateji ─────────
    private fun atr(cs: List<Candle>, n: Int = Cfg.ATR_LEN): Double? {
        if (cs.size < n + 1) return null
        val trs = (1 until cs.size).map { i ->
            val h = cs[i].h; val l = cs[i].l; val pc = cs[i - 1].c
            max(h - l, max(abs(h - pc), abs(l - pc)))
        }
        return trs.takeLast(n).average()
    }

    class Signal(val side: String, val atr: Double, val barT: Long)

    /** Kural 2: dump → short, pump → long. Anlamlı hareket yoksa null. */
    private fun signal(coin: String, cs: List<Candle>): Signal? {
        if (cs.size < max(Cfg.ATR_LEN, Cfg.VOL_LEN) + 2) return null
        val a = atr(cs.dropLast(1)) ?: return null       // hareket kendini ölçmesin
        if (a <= 0) return null
        val last = cs.last()
        val body = last.c - last.o
        val avgVol = cs.subList(cs.size - 1 - Cfg.VOL_LEN, cs.size - 1).map { it.v }.average()
        val k = Cfg.PROFILE[coin]!!.second
        if (abs(body) < k * a || last.v < Cfg.VOL_MULT * avgVol) return null
        return Signal(if (body > 0) "long" else "short", a, last.t)
    }

    /** Kural 1, 3, 4: her şey açılmadan önce kesinleşir. */
    private fun buildPlan(coin: String, sig: Signal, price: Double, bal: Double): Position? {
        val long = sig.side == "long"
        val tp = if (long) price + Cfg.TP_ATR * sig.atr else price - Cfg.TP_ATR * sig.atr
        val sl = if (long) price - Cfg.SL_ATR * sig.atr else price + Cfg.SL_ATR * sig.atr
        if (tp <= 0 || sl <= 0) return null

        val slDist = abs(price - sl) / price
        val tpDist = abs(tp - price) / price

        var notional = bal * Cfg.RISK_PCT / slDist                       // Kural 3
        var lev = max(1, min(Cfg.PROFILE[coin]!!.first, floor(Cfg.TARGET_ROE / tpDist).toInt()))  // Kural 4

        val maxMargin = bal * Cfg.MAX_MARGIN_PCT
        if (notional / lev > maxMargin) notional = maxMargin * lev

        // Tasfiye stop'tan önce gelmesin
        if (1.0 / lev <= slDist * 1.5) {
            lev = max(1, floor(1.0 / (slDist * 1.5)).toInt())
            if (notional / lev > maxMargin) notional = maxMargin * lev
        }

        val dec = szDecimals(coin)
        val m = 10.0.pow(dec)
        val size = floor(notional / price * m) / m
        if (size <= 0 || size * price < 10) return null

        return Position(coin, sig.side, price, tp, sl, size, lev,
            round2(size * price / lev), round2(size * price * slDist), round2(tpDist / slDist),
            sig.barT, System.currentTimeMillis())
    }

    // ───────── döngü ─────────
    fun tick(ctx: Context) {
        load(ctx)
        val mids = fetchMids()   // ağ çağrıları kilit dışında

        // Kapanmış yeni mum varsa ve o coinde pozisyon yoksa mumları çek
        val lastClosedBar = (System.currentTimeMillis() / Cfg.INTERVAL_MS) * Cfg.INTERVAL_MS - Cfg.INTERVAL_MS
        val candleMap = HashMap<String, List<Candle>>()
        val busy = synchronized(this) { positions.keys.toSet() }
        for (coin in Cfg.COINS) {
            if (coin in busy || checkedBars[coin] == lastClosedBar) continue
            try {
                val cs = fetchCandles(coin)
                if (cs.isNotEmpty() && cs.last().t >= lastClosedBar) {
                    checkedBars[coin] = lastClosedBar
                    candleMap[coin] = cs
                }
            } catch (e: Exception) { log("$coin mum verisi alınamadı: ${e.message}") }
        }
        if (candleMap.isNotEmpty()) szDecimals("BTC")  // meta'yı kilit dışında önceden yükle

        synchronized(this) {
            prices.putAll(mids)
            manageOpen()
            lookForEntries(candleMap)
            lastTick = System.currentTimeMillis()
            lastError = null
        }
        save(ctx)
    }

    private fun manageOpen() {
        for ((coin, p) in positions.entries.toList()) {
            val px = prices[coin] ?: continue
            var exitPx = px
            val reason = when {
                (p.isLong && px >= p.tp) || (!p.isLong && px <= p.tp) -> { exitPx = p.tp; "Kâr al" }
                (p.isLong && px <= p.sl) || (!p.isLong && px >= p.sl) -> { exitPx = p.sl; "Zarar kes" }
                System.currentTimeMillis() - p.openedAt > Cfg.TIME_STOP_BARS * Cfg.INTERVAL_MS -> "Zaman stopu"
                else -> null
            } ?: continue
            val r = p.pnlAt(exitPx)
            balance += r
            trades.add(Trade(coin, p.side, reason, r, p.entry, exitPx, now()))
            positions.remove(coin)
            log("KAPANDI $coin ${tr(p.side)} · $reason · ${sign(r)}$")
        }
    }

    private fun lookForEntries(candleMap: Map<String, List<Candle>>) {
        val today = df.format(Date())
        if (day != today) { day = today; dayStartBalance = balance }
        if (balance < dayStartBalance * (1 - Cfg.DAILY_LOSS_CAP)) {
            if (candleMap.isNotEmpty()) log("Günlük kayıp sınırı doldu, bugün yeni işlem yok")
            return
        }
        for (coin in Cfg.COINS) {
            if (positions.size >= Cfg.MAX_OPEN) break
            if (coin in positions) continue                      // Kural 5: ortalama yok
            val cs = candleMap[coin] ?: continue
            val sig = signal(coin, cs) ?: continue
            if (seenBars[coin] == sig.barT) continue
            seenBars[coin] = sig.barT
            val price = prices[coin] ?: continue
            val p = buildPlan(coin, sig, price, balance) ?: continue
            positions[coin] = p
            log("AÇILDI $coin ${tr(p.side)} x${p.lev} @ ${px(p.entry)} · TP ${px(p.tp)} · SL ${px(p.sl)} · risk ${fmt(p.riskUsd)}$")
        }
    }

    fun tr(side: String) = if (side == "long") "LONG" else "SHORT"
    fun round2(x: Double) = Math.round(x * 100) / 100.0
    fun fmt(x: Double) = String.format(Locale.US, "%.2f", x)
    fun sign(x: Double) = (if (x >= 0) "+" else "") + fmt(x)
    fun px(x: Double): String = when {
        x >= 1000 -> String.format(Locale.US, "%.1f", x)
        x >= 10 -> String.format(Locale.US, "%.2f", x)
        else -> String.format(Locale.US, "%.4f", x)
    }
}
