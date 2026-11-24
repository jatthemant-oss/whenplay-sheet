package com.tunex.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import java.util.Calendar

object Scheduler {

    private val SUN_FIRST = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    fun nextTrigger(s: Schedule, now: Long = System.currentTimeMillis()): Long {
        if (s.days == 0 && s.onceAt > now) return s.onceAt
        for (d in 0..7) {
            val c = Calendar.getInstance()
            c.timeInMillis = now
            c.add(Calendar.DAY_OF_YEAR, d)
            c.set(Calendar.HOUR_OF_DAY, s.hour)
            c.set(Calendar.MINUTE, s.minute)
            c.set(Calendar.SECOND, s.second)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now) continue
            val bit = (s.days shr (c.get(Calendar.DAY_OF_WEEK) - 1)) and 1
            if (s.days == 0 || bit == 1) return c.timeInMillis
        }
        return now + 24L * 3600L * 1000L
    }

    private fun firePi(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        i.action = "com.tunex.app.FIRE"
        i.putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showPi(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Uses setAlarmClock: exact, works in Doze, exempt from battery optimisation. */
    fun arm(ctx: Context, s: Schedule) {
        val t = nextTrigger(s)
        if (s.days == 0) {
            s.onceAt = t
            Store.upsert(ctx, s)
        }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(AlarmManager.AlarmClockInfo(t, showPi(ctx)), firePi(ctx, s.id))
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePi(ctx, id))
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for (s in Store.all(ctx)) {
            if (!s.enabled) continue
            if (s.days == 0 && s.onceAt in 1..now) {
                s.enabled = false // missed one-time schedule
                Store.upsert(ctx, s)
                continue
            }
            arm(ctx, s)
        }
    }

    /** Next time (after "from") the stop-time occurs, or 0 if stop-time is off. */
    fun stopDeadline(s: Schedule, from: Long = System.currentTimeMillis()): Long {
        if (!s.stopEnabled) return 0L
        val c = Calendar.getInstance()
        c.timeInMillis = from
        c.set(Calendar.HOUR_OF_DAY, s.stopHour)
        c.set(Calendar.MINUTE, s.stopMinute)
        c.set(Calendar.SECOND, s.stopSecond)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun applyVolume(ctx: Context, percent: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100).coerceIn(1, max), 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val durCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun fileMs(ctx: Context, uri: String): Long = durCache.getOrPut(uri) {
        try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(ctx, android.net.Uri.parse(uri))
            val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            r.release()
            d?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** Length of one local file in ms (0 = unknown). Can be slow the first time, so call it off the main thread. */
    fun trackMs(ctx: Context, uri: String): Long = fileMs(ctx, uri)

    /** Total play length in seconds of local audio/video files (0 = unknown). Run off the main thread. */
    fun localSec(ctx: Context, uris: List<String>): Int {
        var total = 0L
        for (u in uris) {
            val ms = fileMs(ctx, u)
            if (ms <= 0L) return 0
            total += ms
        }
        return (total / 1000L).toInt()
    }

    /** Minutes between start and stop (overnight aware). Equal times = 24h. */
    fun span(h1: Int, m1: Int, h2: Int, m2: Int): Int {
        val d = ((h2 * 60 + m2) - (h1 * 60 + m1) + 1440) % 1440
        return if (d == 0) 1440 else d
    }

    /** Known play length in minutes, or null when it can't be known (YouTube/Spotify/JioSaavn without stop time, loops). */
    fun durationMin(ctx: Context, s: Schedule): Int? {
        val ts = s.trackSec
        if (ts > 0 && (s.extApp.isNotBlank() || s.ytId.isNotBlank())) {
            val songMin = ((ts + 59) / 60).coerceAtLeast(1)
            return when {
                s.stopEnabled -> {
                    val sp = span(s.hour, s.minute, s.stopHour, s.stopMinute)
                    if (s.loop || s.extApp == Ext.INSTAGRAM) sp else minOf(sp, songMin) // a reel loops until its stop time
                }
                s.loop -> null
                else -> songMin
            }
        }
        if (s.stopEnabled) return span(s.hour, s.minute, s.stopHour, s.stopMinute)
        if (s.loop || s.extApp.isNotBlank() || s.ytId.isNotBlank() || s.uris.isEmpty()) return null
        var total = 0L
        for (u in s.uris) {
            val ms = fileMs(ctx, u)
            if (ms <= 0L) return null
            total += ms
        }
        return ((total + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }

    fun endMinutes(ctx: Context, s: Schedule): Int =
        (s.hour * 60 + s.minute + (durationMin(ctx, s) ?: 1)) % 1440

    fun fmtDur(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} hr"
        else -> "${min / 60} hr ${min % 60} min"
    }

    private fun slots(ctx: Context, s: Schedule): List<Triple<Int, Int, Int>> {
        val dur = if (openEnded(s)) 1440 else (durationMin(ctx, s) ?: 1)
        val st = s.hour * 60 + s.minute
        val en = st + dur
        val dayList = if (s.days == 0)
            listOf(Calendar.getInstance().apply { timeInMillis = nextTrigger(s) }.get(Calendar.DAY_OF_WEEK) - 1)
        else (0..6).filter { (s.days shr it) and 1 == 1 }
        val out = mutableListOf<Triple<Int, Int, Int>>()
        for (d in dayList) {
            out.add(Triple(d, st, minOf(en, 1440)))
            if (en > 1440) out.add(Triple((d + 1) % 7, 0, en - 1440))
        }
        return out
    }

    /** Repeat mode with no stop time = plays until somebody stops it. */
    fun openEnded(s: Schedule): Boolean = s.loop && !s.stopEnabled

    data class Clash(val other: Schedule, val otherOpen: Boolean, val candOpen: Boolean)

    /** Another enabled schedule whose play window overlaps this one, or null. */
    fun conflict(ctx: Context, cand: Schedule): Clash? {
        val mine = slots(ctx, cand)
        for (o in Store.all(ctx)) {
            if (o.id == cand.id || !o.enabled) continue
            val theirs = slots(ctx, o)
            for (a in mine) for (b in theirs)
                if (a.first == b.first && a.second < b.third && b.second < a.third)
                    return Clash(o, openEnded(o), openEnded(cand))
        }
        return null
    }

    fun fmt12(h: Int, m: Int): Pair<String, String> {
        val hh = if (h % 12 == 0) 12 else h % 12
        return Pair(String.format("%d:%02d", hh, m), if (h < 12) "AM" else "PM")
    }

    /** "4:42" + "PM"; seconds are shown only when they are not 00 ("4:42:30"). */
    fun fmt12s(h: Int, m: Int, sec: Int): Pair<String, String> {
        val hh = if (h % 12 == 0) 12 else h % 12
        val t = if (sec != 0) String.format("%d:%02d:%02d", hh, m, sec) else String.format("%d:%02d", hh, m)
        return Pair(t, if (h < 12) "AM" else "PM")
    }

    fun dayLabel(days: Int): String {
        if (days == 0) return "Once"
        if (days == 127) return "Every day"
        if (days == 62) return "Mon–Fri"
        val order = intArrayOf(1, 2, 3, 4, 5, 6, 0)
        return order.filter { (days shr it) and 1 == 1 }.joinToString(", ") { SUN_FIRST[it] }
    }

    /** Repeat text for the schedule screens: "Once", "Daily", "Mon \u2013 Fri", "Sat, Sun". */
    fun repeatText(days: Int): String = when (days) {
        0 -> "Once"
        127 -> "Daily"
        else -> dayLabel(days).replace("\u2013", " \u2013 ")
    }

    fun untilText(ms: Long): String {
        val m = ms / 60000
        return when {
            m < 1 -> "in under a minute"
            m < 60 -> "in ${m}m"
            m < 1440 -> "in ${m / 60}h ${m % 60}m"
            else -> "in ${m / 1440}d ${(m % 1440) / 60}h"
        }
    }
}

