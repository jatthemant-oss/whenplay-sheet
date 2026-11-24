package com.tunex.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_KEY = "com.tunex.app.KEY"
        const val KEY_END = -1
        const val KEY_IG_STOP = -2
        const val KEY_VERIFY = -3
        const val KEY_AUDIO_VERIFY = -4
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        // Delayed "press play / pause" for Spotify & JioSaavn
        if (intent.action == ACTION_KEY) {
            val key = intent.getIntExtra("key", KeyEvent.KEYCODE_MEDIA_PLAY)
            val pkg = intent.getStringExtra("pkg")
            val sid = intent.getIntExtra("id", -1)
            if (key == KEY_IG_STOP) {
                // Instagram reel: it has ended once (Repeat off) or the stop time came
                val sc = Store.get(ctx, sid)
                val due = ctx.getSharedPreferences("tunex_ui", Context.MODE_PRIVATE).getLong("igEnd_$sid", Long.MAX_VALUE)
                if (sc != null && sc.extApp == Ext.INSTAGRAM && System.currentTimeMillis() >= due - 1500L && System.currentTimeMillis() < due + 60_000L) {
                    Ext.pauseInstagram(ctx)
                    Takeover.onEnded(ctx, sid)
                }
                return
            }
            if (key == KEY_AUDIO_VERIFY) {
                // Phone audio: the alarm fired but nothing is playing yet (phone was asleep): wake it and start again
                val sc = Store.get(ctx, sid)
                val a = Takeover.active(ctx)
                if (sc != null && a != null && a.id == sid && sc.extApp.isBlank() && sc.ytId.isBlank() &&
                    !PlayerService.isPlaying && !VideoPlayerActivity.running
                ) {
                    Handoff.wakeScreen(ctx)
                    startAudio(ctx, sid, false)
                }
                return
            }
            if (key == KEY_VERIFY) {
                // Repeat check a few seconds after a restart: is OUR song really playing again?
                val sc = Store.get(ctx, sid)
                val pr = ctx.getSharedPreferences("tunex_ui", Context.MODE_PRIVATE)
                val runEnd = pr.getLong("runEnd_$sid", Long.MAX_VALUE)
                if (sc != null && pkg != null && sc.loop && Ext.listenerEnabled(ctx) &&
                    System.currentTimeMillis() < runEnd - 2000L
                ) {
                    val saved = pr.getString("fp_$sid", "") ?: ""
                    val same = saved.isEmpty() || Ext.fingerprint(ctx, pkg) == saved
                    val tries = pr.getInt("vc_$sid", 0)
                    if ((!same || !Ext.isPlayingNow(ctx, pkg)) && tries < 3) {
                        // the app moved on to another song (or stopped): bring ours back
                        pr.edit().putInt("vc_$sid", tries + 1).apply()
                        Ext.resetRun(sid)
                        Ext.startTrack(ctx, sc, true)
                        keyAlarm(ctx, sid, 7800, System.currentTimeMillis() + 4000L, KEY_VERIFY, pkg)
                    } else {
                        pr.edit().putInt("vc_$sid", 0).apply()
                        scheduleEnd(ctx, sc, 0L)
                    }
                }
                return
            }
            if (key == KEY_END) {
                // the track is about to finish: repeat it, or pause so the app does not auto-play the next one
                val sc = Store.get(ctx, sid)
                val runEnd = ctx.getSharedPreferences("tunex_ui", Context.MODE_PRIVATE).getLong("runEnd_$sid", Long.MAX_VALUE)
                if (sc != null && pkg != null && System.currentTimeMillis() < runEnd - 500L) {
                    if (sc.loop && System.currentTimeMillis() < runEnd - 2000L) {
                        if (Ext.repeatTrack(ctx, sc)) {
                            val d = Ext.durMs(ctx, pkg)
                            if (d > 0L) keyAlarm(ctx, sc.id, 7400, System.currentTimeMillis() + (d - 400L).coerceAtLeast(3000L), KEY_END, pkg)
                            keyAlarm(ctx, sc.id, 7800, System.currentTimeMillis() + 4000L, KEY_VERIFY, pkg)
                        }
                    } else {
                        Ext.sendKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE, pkg)
                        Takeover.onEnded(ctx, sid)
                    }
                }
                return
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                val sc = Store.get(ctx, sid)
                if (sc != null && Ext.listenerEnabled(ctx)) {
                    // play OUR song once; if the app isn't ready yet, a later nudge retries
                    val r = Ext.startTrack(ctx, sc)
                    if (r == 2) {
                        val pr = ctx.getSharedPreferences("tunex_ui", Context.MODE_PRIVATE)
                        if ((pr.getString("fp_$sid", "") ?: "").isEmpty()) {
                            pr.edit().putString("fp_$sid", Ext.fingerprint(ctx, Ext.pkg(sc.extApp))).apply()
                        }
                        scheduleEnd(ctx, sc, 0L)
                    }
                    // app opened the song but never started it (no media session yet): press play for it
                    else if (r == 0) Ext.sendKey(ctx, key, pkg)
                } else {
                    Ext.sendKey(ctx, key, pkg)
                }
            } else {
                Ext.sendKey(ctx, key, pkg)
                if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) Takeover.onEnded(ctx, sid)
            }
            return
        }

        // Keep CPU awake until playback has taken over
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TuneX:fire").acquire(60_000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val id = intent.getIntExtra("id", -1)
        val s = Store.get(ctx, id) ?: return
        val resume = intent.getBooleanExtra("resume", false)
        if (!resume && !s.enabled) return

        if (!resume) {
            ctx.getSharedPreferences("tunex_ui", Context.MODE_PRIVATE).edit()
                .putLong("lastFire", System.currentTimeMillis())
                .putLong("lastStart", 0L)
                .commit()

            // Re-arm first so the next occurrence is never lost
            if (s.days == 0) {
                s.enabled = false
                Store.upsert(ctx, s)
            } else {
                Scheduler.arm(ctx, s)
            }
        }

        // a newer schedule has priority over one that is still running
        Takeover.onStart(ctx, s)

        if (s.extApp.isNotBlank()) {
            launchExternal(ctx, s)
            return
        }
        if (s.ytId.isNotBlank()) {
            val i = Intent(ctx, YtPlayerActivity::class.java)
                .putExtra("id", s.id)
                .putExtra("alarm", true)
                .putExtra("resume", resume)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            openScreen(ctx, s, i, "Tap to play YouTube video")
            return
        }

        if (s.uris.isNotEmpty() && Ext.isVideo(ctx, s.uris[0])) {
            val v = Intent(ctx, VideoPlayerActivity::class.java)
                .putExtra("id", s.id)
                .putExtra("alarm", true)
                .putExtra("resume", resume)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            openScreen(ctx, s, v, "Tap to play video")
            return
        }

        // Phone audio: wake the phone first (it often sleeps through the alarm until the power button is pressed),
        // start the player, and check again a few seconds later in case the system held the start back.
        Handoff.wakeScreen(ctx)
        startAudio(ctx, id, resume)
        val now = System.currentTimeMillis()
        keyAlarm(ctx, id, 8000, now + 6_000L, KEY_AUDIO_VERIFY, "")
        keyAlarm(ctx, id, 8100, now + 14_000L, KEY_AUDIO_VERIFY, "")
    }

    private fun startAudio(ctx: Context, id: Int, resume: Boolean) {
        val svc = Intent(ctx, PlayerService::class.java)
            .putExtra("id", id)
            .putExtra("alarm", true)
            .putExtra("resume", resume)
        try {
            ContextCompat.startForegroundService(ctx, svc)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                ctx.startService(svc)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    private fun launchExternal(ctx: Context, s: Schedule) {
        // wake the screen (works even when the phone is locked / screen off)
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "TuneX:wake"
            ).acquire(40_000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val i = Intent(ctx, LockLaunchActivity::class.java)
            .putExtra("id", s.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        openScreen(ctx, s, i, "Tap to open " + Ext.title(s.extApp))
        val now = System.currentTimeMillis()
        ctx.getSharedPreferences("tunex_ui", Context.MODE_PRIVATE).edit()
            .putLong("lastStart", now).apply()
        Ext.resetRun(s.id)
        ctx.getSharedPreferences("tunex_ui", Context.MODE_PRIVATE).edit()
            .remove("fp_${s.id}").remove("vc_${s.id}").apply()
        val stop0 = Scheduler.stopDeadline(s)
        ctx.getSharedPreferences("tunex_ui", Context.MODE_PRIVATE).edit()
            .putLong("runEnd_${s.id}", if (stop0 > 0L) stop0 else Long.MAX_VALUE).apply()
        if (s.extApp == Ext.INSTAGRAM) {
            // The reel starts by itself and loops by itself. No play-key nudges (they could wake
            // another music app). Reels loop by themselves: with a stop time it runs until then;
            // without one it plays once (reel length + app start-up) and WhenPlay leaves.
            // (Older schedules saved with Repeat ON and no stop time keep running until stopped.)
            val at = when {
                stop0 > 0L -> stop0
                !s.loop && s.trackSec > 0 -> now + (s.trackSec + 6) * 1000L
                else -> 0L
            }
            if (at > 0L) {
                ctx.getSharedPreferences("tunex_ui", Context.MODE_PRIVATE).edit()
                    .putLong("igEnd_${s.id}", at).apply()
                keyAlarm(ctx, s.id, 7700, at, KEY_IG_STOP, Ext.pkg(s.extApp))
                // second try a few seconds later, in case the first one was missed
                keyAlarm(ctx, s.id, 7900, at + 4000L, KEY_IG_STOP, Ext.pkg(s.extApp))
            }
            return
        }
        // nudge "play" several times (play is idempotent) once the app is up
        val pkg = Ext.pkg(s.extApp)
        keyAlarm(ctx, s.id, 7300, now + 5000L, KeyEvent.KEYCODE_MEDIA_PLAY, pkg)
        keyAlarm(ctx, s.id, 7000, now + 9000L, KeyEvent.KEYCODE_MEDIA_PLAY, pkg)
        keyAlarm(ctx, s.id, 7100, now + 15000L, KeyEvent.KEYCODE_MEDIA_PLAY, pkg)
        keyAlarm(ctx, s.id, 7200, now + 24000L, KeyEvent.KEYCODE_MEDIA_PLAY, pkg)
        // optional stop time -> "pause"
        val stop = Scheduler.stopDeadline(s)
        if (stop > 0L) {
            keyAlarm(ctx, s.id, 7500, stop, KeyEvent.KEYCODE_MEDIA_PAUSE, pkg)
            keyAlarm(ctx, s.id, 7600, stop + 4000L, KeyEvent.KEYCODE_MEDIA_PAUSE, pkg)
        }
    }

    /** Alarm just before the current track ends (repeat / pause). */
    private fun scheduleEnd(ctx: Context, s: Schedule, extraMs: Long) {
        val pkg = Ext.pkg(s.extApp)
        var left = Ext.msLeft(ctx, pkg)
        if (left < 0L) return
        left += extraMs
        keyAlarm(ctx, s.id, 7400, System.currentTimeMillis() + (left - 400L).coerceAtLeast(1500L), KEY_END, pkg)
    }

    private fun keyAlarm(ctx: Context, id: Int, base: Int, at: Long, key: Int, pkg: String) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, base + id,
                Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_KEY).putExtra("key", key).putExtra("pkg", pkg).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Open a screen at alarm time: direct start (some phones) + full-screen notification (lockscreen). */
    private fun openScreen(ctx: Context, s: Schedule, i: Intent, text: String) {
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
            val pi = PendingIntent.getActivity(
                ctx, 100 + s.id, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(ctx, "tunex_alarm")
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle("WhenPlay • " + s.title())
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
            nm.notify(2000 + s.id, n)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

