package com.halil.perpsbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon yeniden açılınca (veya uygulama güncellenince) bot açıksa kaldığı yerden devam eder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (BotService.prefs(ctx).getBoolean("want", false)) {
            try { BotService.start(ctx) } catch (_: Exception) {}
        }
    }
}
