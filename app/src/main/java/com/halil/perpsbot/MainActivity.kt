package com.halil.perpsbot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0E1116")
    private val CARD = Color.parseColor("#171B22")
    private val LINE = Color.parseColor("#232934")
    private val TEXT = Color.parseColor("#E8ECF1")
    private val MUTED = Color.parseColor("#8A94A3")
    private val GREEN = Color.parseColor("#2FBF71")
    private val RED = Color.parseColor("#E5484D")
    private val GOLD = Color.parseColor("#F0B90B")

    private lateinit var status: TextView
    private lateinit var equity: TextView
    private lateinit var change: TextView
    private lateinit var updated: TextView
    private lateinit var toggle: Button
    private lateinit var stats: TextView
    private lateinit var positionsBox: LinearLayout
    private lateinit var tradesBox: LinearLayout
    private lateinit var logBox: TextView
    private lateinit var batteryBtn: Button

    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 2000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG); fitsSystemWindows = true; addView(root)
        }

        // Başlık
        root.addView(text("Perps Bot", 26, TEXT, bold = true))
        root.addView(text("BTC · ETH · SOL · AVAX · SUI   |   KAĞIT MOD (sanal para)", 12, GOLD).also { it.setPadding(0, dp(2), 0, dp(14)) })

        // Bakiye kartı
        val hero = card()
        status = text("", 13, MUTED)
        equity = text("", 38, TEXT, bold = true).also { it.setPadding(0, dp(6), 0, 0) }
        change = text("", 15, GREEN)
        updated = text("", 12, MUTED).also { it.setPadding(0, dp(6), 0, 0) }
        hero.addView(status); hero.addView(equity); hero.addView(change); hero.addView(updated)
        root.addView(hero)

        // Başlat / durdur
        toggle = Button(this).apply {
            textSize = 18f; setTypeface(typeface, Typeface.BOLD); isAllCaps = false
            setTextColor(Color.BLACK)
            setOnClickListener { onToggle() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(12) })

        batteryBtn = flatButton("⚠ Pil kısıtlamasını kaldır (botun kapanmaması için)") { askBattery() }
        batteryBtn.setTextColor(GOLD)
        root.addView(batteryBtn)

        // İstatistik
        stats = text("", 14, TEXT)
        root.addView(card().also { it.addView(stats) })

        root.addView(section("Açık pozisyonlar"))
        positionsBox = card(); root.addView(positionsBox)

        root.addView(section("Son işlemler"))
        tradesBox = card(); root.addView(tradesBox)

        root.addView(section("Kayıt"))
        logBox = text("", 11, MUTED).apply { typeface = Typeface.MONOSPACE }
        root.addView(card().also { it.addView(logBox) })

        root.addView(text(
            "Kurallar: TP/SL girişten önce hesaplanır · dump = short, pump = long · " +
            "risk = mevcut bakiyenin %1'i · kaldıraç coine ve hedefe göre · ortalama yok, kaybeden hızlı kesilir.",
            12, MUTED).also { it.setPadding(0, dp(18), 0, dp(6)) })

        root.addView(flatButton("Sanal bakiyeyi sıfırla (1000$)") { confirmReset() }.also { it.setTextColor(RED) })

        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Bot açık olmalıyken servis ölmüşse yeniden kaldır
        if (BotService.prefs(this).getBoolean("want", false) && !BotService.running) BotService.start(this)
    }

    override fun onResume() { super.onResume(); ui.post(refresher) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresher) }

    private fun onToggle() {
        if (BotService.running) {
            BotService.stop(this)
        } else {
            BotService.start(this)
            if (!batteryOk()) askBattery()
        }
        ui.postDelayed({ render() }, 400)
    }

    private fun batteryOk(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Sıfırlansın mı?")
            .setMessage("Sanal bakiye 1000$ olur, açık pozisyonlar ve geçmiş silinir.")
            .setPositiveButton("Sıfırla") { _, _ -> Engine.reset(this); render() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun render() {
        val running = BotService.running
        status.text = if (running) "● Çalışıyor" else "● Durdu"
        status.setTextColor(if (running) GREEN else RED)
        toggle.text = if (running) "DURDUR" else "BAŞLAT"
        toggle.background = round(if (running) Color.parseColor("#3A4150") else GOLD, 14)
        toggle.setTextColor(if (running) TEXT else Color.BLACK)
        batteryBtn.visibility = if (batteryOk()) View.GONE else View.VISIBLE

        synchronized(Engine) {
            val eq = Engine.equity()
            val diff = eq - Cfg.START_BALANCE
            equity.text = "${Engine.fmt(eq)} $"
            change.text = "${Engine.sign(diff)} $  (${Engine.sign(diff / Cfg.START_BALANCE * 100)}%)  ·  başlangıç 1000 $"
            change.setTextColor(if (diff >= 0) GREEN else RED)
            updated.text = when {
                Engine.lastError != null -> "Bağlantı sorunu: ${Engine.lastError}"
                Engine.lastTick > 0 -> "Son kontrol: " + ago(Engine.lastTick)
                else -> "Henüz kontrol yapılmadı"
            }

            val t = Engine.trades
            val wins = t.count { it.pnl > 0 }
            val total = t.sumOf { it.pnl }
            val rate = if (t.isEmpty()) "—" else "%${(wins * 100 / t.size)}"
            stats.text = "İşlem: ${t.size}    Kazanan: $wins    Oran: $rate\nToplam K/Z: ${Engine.sign(total)} $"

            positionsBox.removeAllViews()
            if (Engine.positions.isEmpty()) {
                positionsBox.addView(text("Pozisyon yok. Bot net bir pump ya da dump bekliyor.", 13, MUTED))
            }
            for (p in Engine.positions.values) {
                val px = Engine.prices[p.coin]
                val pnl = px?.let { p.pnlAt(it) } ?: 0.0
                val head = text("${p.coin}  ${Engine.tr(p.side)}  x${p.lev}      ${Engine.sign(pnl)} $", 16,
                    if (pnl >= 0) GREEN else RED, bold = true)
                val body = text(
                    "Giriş ${Engine.px(p.entry)} · Şu an ${px?.let { Engine.px(it) } ?: "—"}\n" +
                    "Kâr al ${Engine.px(p.tp)} · Zarar kes ${Engine.px(p.sl)}\n" +
                    "Risk ${Engine.fmt(p.riskUsd)} $ · Marjin ${Engine.fmt(p.margin)} $ · R:R ${p.rr}", 13, MUTED)
                positionsBox.addView(head); positionsBox.addView(body.also { it.setPadding(0, dp(2), 0, dp(10)) })
            }

            tradesBox.removeAllViews()
            if (t.isEmpty()) tradesBox.addView(text("Henüz kapanmış işlem yok.", 13, MUTED))
            for (tr in t.takeLast(15).reversed()) {
                tradesBox.addView(text(
                    "${tr.time}  ${tr.coin} ${Engine.tr(tr.side)}  ${tr.reason}   ${Engine.sign(tr.pnl)} $",
                    13, if (tr.pnl >= 0) GREEN else RED).also { it.setPadding(0, dp(3), 0, dp(3)) })
            }

            logBox.text = Engine.logs.takeLast(25).reversed().joinToString("\n").ifEmpty { "Kayıt yok." }
        }
    }

    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return if (s < 60) "$s sn önce" else "${s / 60} dk önce"
    }

    // ───────── küçük görünüm yardımcıları ─────────
    private fun text(s: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(s: String) = text(s, 15, TEXT, bold = true).also { it.setPadding(dp(2), dp(20), 0, dp(8)) }

    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(CARD); cornerRadius = dp(16).toFloat(); setStroke(dp(1), LINE)
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun flatButton(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 13f; setTextColor(MUTED)
        setBackgroundColor(Color.TRANSPARENT); gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
}
