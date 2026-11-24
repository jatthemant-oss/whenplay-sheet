package com.tunex.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** Small helpers shared by the alarm and the two players (audio screen / video screen). */
object Handoff {

    /** Turns the screen on for a few seconds, like pressing the power button. */
    fun wakeScreen(ctx: Context, ms: Long = 10_000L) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "TuneX:wakeScreen"
            ).acquire(ms)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Opens a screen from the background: direct start + full-screen notification as a fallback. */
    fun openScreen(ctx: Context, id: Int, i: Intent, text: String) {
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("tunex_alarm", "TuneX alarms", NotificationManager.IMPORTANCE_HIGH)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
            val pi = PendingIntent.getActivity(ctx, 100 + id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, "tunex_alarm")
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle("WhenPlay")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
            nm.notify(2000 + id, n)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

