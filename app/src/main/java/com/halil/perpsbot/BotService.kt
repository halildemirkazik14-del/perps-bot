package com.halil.perpsbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Arka planda sürekli çalışan bot servisi (kalıcı bildirimle). */
class BotService : Service() {

    companion object {
        const val CH = "bot"
        const val NID = 7
        const val ACTION_STOP = "stop"
        @Volatile var running = false

        fun start(ctx: Context) {
            prefs(ctx).edit().putBoolean("want", true).apply()
            val i = Intent(ctx, BotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            prefs(ctx).edit().putBoolean("want", false).apply()
            ctx.startService(Intent(ctx, BotService::class.java).setAction(ACTION_STOP))
        }

        fun prefs(ctx: Context) = ctx.getSharedPreferences("bot", Context.MODE_PRIVATE)
    }

    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            worker?.interrupt()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val n = buildNotification("Başlatılıyor…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, n)
        }

        if (worker?.isAlive != true) {
            running = true
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "perpsbot:loop").apply {
                    setReferenceCounted(false); acquire()
                }
            Engine.load(this)
            Engine.log("Bot başladı (kağıt mod)")
            worker = Thread { loop() }.apply { name = "bot-loop"; start() }
        }
        return START_STICKY
    }

    private fun loop() {
        while (running) {
            try {
                Engine.tick(this)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Engine.lastError = e.message ?: e.javaClass.simpleName
                Engine.log("Bağlantı hatası: ${Engine.lastError} (tekrar denenecek)")
                try { Engine.save(this) } catch (_: Exception) {}
            }
            updateNotification()
            try { Thread.sleep(Cfg.TICK_MS) } catch (e: InterruptedException) { break }
        }
    }

    private fun updateNotification() {
        val eq = Engine.equity()
        val open = synchronized(Engine) { Engine.positions.values.joinToString(", ") { "${it.coin} ${Engine.tr(it.side)}" } }
        val text = "Bakiye ${Engine.fmt(eq)}$ · " + (if (open.isEmpty()) "Pozisyon yok, setup bekliyor" else "Açık: $open")
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "Bot durumu", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Perps Bot çalışıyor · kağıt mod")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try { wake?.release() } catch (_: Exception) {}
        Engine.log("Bot durdu")
        try { Engine.save(this) } catch (_: Exception) {}
        super.onDestroy()
    }
}
