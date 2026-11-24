package com.tunex.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Priority between schedules.
 * When a schedule starts while another one is still running, the NEWER one takes over:
 * the older one is paused/stopped and remembered, and it comes back automatically when the newer one
 * finishes - as long as the older one's own stop time has not passed yet.
 */
object Takeover {
    const val ACTION = "com.tunex.app.TAKEOVER"
    private const val P = "tunex_run"

    /** Instagram can't report if a reel is still playing; a no-stop-time reel counts as running this long. */
    private const val IG_OPEN_RUN_MS = 30L * 60L * 1000L

    data class Run(val id: Int, val until: Long, val since: Long = 0L)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun active(ctx: Context): Run? {
        val id = sp(ctx).getInt("aid", -1)
        return if (id < 0) null else Run(id, sp(ctx).getLong("aend", Long.MAX_VALUE), sp(ctx).getLong("astart", 0L))
    }

    private fun setActive(ctx: Context, id: Int, until: Long) {
        sp(ctx).edit().putInt("aid", id).putLong("aend", until).putLong("astart", System.currentTimeMillis()).apply()
    }

    private fun clearActive(ctx: Context) {
        sp(ctx).edit().remove("aid").remove("aend").remove("astart").apply()
    }

    private fun stack(ctx: Context): MutableList<Run> {
        val out = mutableListOf<Run>()
        try {
            val a = JSONArray(sp(ctx).getString("stack", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Run(o.getInt("id"), o.getLong("until")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    private fun saveStack(ctx: Context, st: List<Run>) {
        val a = JSONArray()
        for (r in st) a.put(JSONObject().put("id", r.id).put("until", r.until))
        sp(ctx).edit().putString("stack", a.toString()).apply()
    }

    /** The clock time this schedule is told to stop (Long.MAX_VALUE = no stop time). */
    fun endOf(s: Schedule): Long {
        val t = Scheduler.stopDeadline(s)
        return if (t > 0L) t else Long.MAX_VALUE
    }

    private fun isRunning(ctx: Context, a: Run): Boolean {
        val s = Store.get(ctx, a.id) ?: return false
        return when {
            s.ytId.isNotBlank() -> YtPlayerActivity.running
            // Instagram gives no playback info: with a stop time it is running until then,
            // without one only for a limited time (an old, forgotten reel must not pull WhenPlay forward)
            s.extApp == Ext.INSTAGRAM ->
                a.until != Long.MAX_VALUE || System.currentTimeMillis() - a.since < IG_OPEN_RUN_MS
            // Spotify / JioSaavn: only when the app is really playing (without Notification access we can't tell)
            s.extApp.isNotBlank() -> !Ext.listenerEnabled(ctx) || Ext.isPlayingNow(ctx, Ext.pkg(s.extApp))
            Ext.hasVideo(ctx, s) -> VideoPlayerActivity.running || (PlayerService.instance != null && PlayerService.nowId == s.id)
            else -> PlayerService.instance != null
        }
    }

    /**
     * A schedule is starting. If another schedule is still running, it is overshadowed:
     * it is stopped for good and does NOT come back after the new one finishes.
     */
    fun onStart(ctx: Context, s: Schedule) {
        val now = System.currentTimeMillis()
        val a = active(ctx)
        if (a != null && a.id != s.id && a.until > now + 3000L && isRunning(ctx, a)) {
            suspend(ctx, a.id, s)
        }
        setActive(ctx, s.id, endOf(s))
    }

    /** The running schedule finished by itself. Nothing is resumed. */
    fun onEnded(ctx: Context, id: Int) {
        val a = active(ctx) ?: return
        if (a.id == id) clearActive(ctx)
    }

    /** The user pressed Stop. */
    fun onManualStop(ctx: Context, id: Int) {
        val a = active(ctx)
        if (a != null && a.id == id) clearActive(ctx)
    }

    private fun suspend(ctx: Context, id: Int, next: Schedule) {
        val s = Store.get(ctx, id) ?: return
        if (s.extApp.isNotBlank()) {
            cancelKeyAlarms(ctx, id)
            if (s.extApp == Ext.INSTAGRAM) {
                // a new schedule that opens its own screen already pushes Instagram to the background;
                // only a screen-less (phone music) schedule needs WhenPlay brought forward
                val opensScreen = next.ytId.isNotBlank() || next.extApp.isNotBlank() || Ext.hasVideo(ctx, next)
                Ext.pauseInstagram(ctx, !opensScreen)
            } else Ext.stopApp(ctx, Ext.pkg(s.extApp))
        } else {
            // our own players: they save where they were and close quietly
            ctx.sendBroadcast(Intent(ACTION).setPackage(ctx.packageName).putExtra("id", id))
        }
    }

    private fun cancelKeyAlarms(ctx: Context, id: Int) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (base in intArrayOf(7000, 7100, 7200, 7300, 7400, 7500, 7600, 7700, 7800, 7900)) {
                val pi = PendingIntent.getBroadcast(
                    ctx, base + id,
                    Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_KEY),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) {
                    am.cancel(pi)
                    pi.cancel()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---- saved position of a stepped-aside schedule (used by our own players) ----
    fun savePosition(ctx: Context, id: Int, index: Int, posMs: Long) {
        sp(ctx).edit().putInt("ri_$id", index).putLong("rp_$id", posMs).apply()
    }

    /** Returns (index, positionMs) and forgets it. */
    fun takePosition(ctx: Context, id: Int): Pair<Int, Long>? {
        val p = sp(ctx)
        if (!p.contains("rp_$id")) return null
        val r = Pair(p.getInt("ri_$id", 0), p.getLong("rp_$id", 0L))
        p.edit().remove("ri_$id").remove("rp_$id").apply()
        return r
    }

    fun forgetPosition(ctx: Context, id: Int) {
        sp(ctx).edit().remove("ri_$id").remove("rp_$id").apply()
    }

    /** Lets a player react when it is asked to step aside. */
    fun listen(ctx: Context, id: Int, onTake: () -> Unit): BroadcastReceiver {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.getIntExtra("id", -1) == id) onTake()
            }
        }
        ctx.registerReceiver(rx, IntentFilter(ACTION))
        return rx
    }
}

