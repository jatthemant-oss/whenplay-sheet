package com.tunex.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.media.MediaMetadataRetriever
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tunex.app.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    private var userSeeking = false
    private var peek = false          // playing: tap the header line to peek at the full list
    private var wasPlaying = false
    private var shownId = -2
    private var pulse: ObjectAnimator? = null
    private var coverFor = ""                       // song whose cover is on screen
    private val covers = LruCache<String, Bitmap>(8)
    private val noCover = HashSet<String>()         // songs that have no picture inside

    private val adapter = ScheduleAdapter(
        onToggle = { s, on -> onToggle(s, on) },
        onEdit = { s ->
            startActivity(Intent(this, EditActivity::class.java).putExtra("id", s.id))
        },
        onDelete = { s -> onDelete(s) }
    )

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            showBatteryPrompt()
        }

    private val tick = object : Runnable {
        override fun run() {
            refreshNow()
            ticks++
            if (ticks % 60 == 0) refreshList()
            handler.postDelayed(this, 500)
        }
    }

    private fun applyLayout() {
        val dp = resources.displayMetrics.density
        val pills = LayoutStore.isPills(this)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
        adapter.style = if (pills) 1 else 0

        val acc = ThemeStore.attrColor(this, R.attr.txAccent, 0xFF7C5CFF.toInt())
        val on = ThemeStore.attrColor(this, R.attr.txOnAccent, 0xFFFFFFFF.toInt())
        val ink = ThemeStore.attrColor(this, R.attr.txText, 0xFFFFFFFF.toInt())
        val inkOn = ThemeStore.attrColor(this, R.attr.txBg, 0xFF000000.toInt())
        val muted = ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8B92B5.toInt())
        fun a(c: Int, alpha: Int) = androidx.core.graphics.ColorUtils.setAlphaComponent(c, alpha)

        if (pills) {
            b.headerBox.background = null
            b.hdrTitle.setTextColor(ink)
            b.hdrSub.setTextColor(muted)
            b.btnMenu.setBackgroundResource(R.drawable.input_bg)
            b.btnMenu.imageTintList = null
        } else {
            val r = 34 * dp
            val hd = android.graphics.drawable.GradientDrawable()
            hd.setColor(acc)
            hd.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            b.headerBox.background = hd
            b.hdrTitle.setTextColor(on)
            b.hdrSub.setTextColor(a(on, 0xB3))
            val mb = android.graphics.drawable.GradientDrawable()
            mb.shape = android.graphics.drawable.GradientDrawable.OVAL
            mb.setColor(a(on, 0x2E))
            mb.setStroke(dp.toInt().coerceAtLeast(1), a(on, 0x45))
            b.btnMenu.background = mb
            b.btnMenu.imageTintList = android.content.res.ColorStateList.valueOf(on)
        }

        // "New schedule" button: the accent pill (UI 8)
        val pillBg = acc
        val pillFg = on
        b.fab.backgroundTintList = android.content.res.ColorStateList.valueOf(pillBg)
        b.fab.setTextColor(pillFg)
        b.fab.iconTint = android.content.res.ColorStateList.valueOf(pillFg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ThemeStore.styleBars(this)

        b.list.adapter = adapter
        applyLayout()
        styleNow()
        b.hdrSub.setOnClickListener {
            if (PlayerService.isPlaying) {
                peek = !peek
                refreshNow()
                refreshList()
            }
        }

        b.fab.setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java))
        }
        b.btnStop.setOnClickListener {
            startService(Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_STOP))
        }
        b.btnPlay.setOnClickListener { PlayerService.instance?.togglePause() }
        b.btnPrev.setOnClickListener { PlayerService.instance?.prev() }
        b.btnNext.setOnClickListener { PlayerService.instance?.next() }
        b.btnBack.setOnClickListener { PlayerService.instance?.seekBy(-10000) }
        b.btnFwd.setOnClickListener { PlayerService.instance?.seekBy(10000) }
        b.nowSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) b.nowPos.text = fmtMs(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                PlayerService.instance?.seekTo(sb?.progress ?: 0)
                userSeeking = false
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showBatteryPrompt()
        }

        // Make sure every enabled schedule is armed
        Scheduler.rescheduleAll(this)
    }

    private fun showBatteryPrompt() {
        val prefs = getSharedPreferences("tunex_ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("asked", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("Make playback rock-solid")
            .setMessage("One time only: tap Allow on the next screen so Android never limits WhenPlay in the background.")
            .setPositiveButton("Continue") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        peek = false   // opening the app (e.g. from the notification) always lands on the playing view
        refreshList()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        stopPulse()
    }

    private fun refreshList() {
        val all = Store.all(this).sortedWith(compareBy({ it.hour }, { it.minute }, { it.second }))
        val active = all.count { it.enabled }
        val playing = PlayerService.isPlaying
        b.hdrSub.text = when {
            all.isEmpty() -> "Schedule your music"
            playing && !peek -> countsText(
                all.count { it.enabled && it.id != PlayerService.nowId },
                all.count { !it.enabled }
            )
            playing -> "Back to now playing"
            else -> all.size.toString() + (if (all.size == 1) " schedule" else " schedules") + " \u00B7 " + active + " active"
        }
        adapter.submit(all)
        b.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        paintNextUp(all)
    }

    /** Sheet list: the big card on top shows whichever schedule plays next. */
    private fun paintNextUp(all: List<Schedule>) {
        val next = all.filter { it.enabled }.minByOrNull { Scheduler.nextTrigger(it) }
        if (next == null || (PlayerService.isPlaying && !peek)) {
            b.nuCard.visibility = View.GONE
            return
        }
        b.nuCard.visibility = View.VISIBLE
        val sr = ScheduleAdapter.src(this, next)
        val dp = resources.displayMetrics.density
        b.nuHero.background = ScheduleAdapter.thumb(this, sr.color, 0f)
        b.nuMark.setImageResource(ScheduleAdapter.iconRes(next))
        b.nuMark.imageTintList = ColorStateList.valueOf(0x2EFFFFFF)
        val ms = Scheduler.nextTrigger(next) - System.currentTimeMillis()
        b.nuTag.text = "UP NEXT \u00B7 " + Scheduler.untilText(ms)
        b.nuTitle.text = next.title()
        val (t, ap) = Scheduler.fmt12(next.hour, next.minute)
        b.nuSub.text = "$t $ap \u00B7 " + Scheduler.repeatText(next.days)
        b.nuCard.elevation = 6 * dp
    }

    /** "● 3 pending   ● 15 off": only mentioned, the list itself stays hidden while something plays. */
    private fun countsText(pending: Int, off: Int): CharSequence {
        val sb = SpannableStringBuilder()
        fun dot(col: Int) {
            val st = sb.length
            sb.append("\u25CF ")
            sb.setSpan(ForegroundColorSpan(col), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        dot(0xFFFFD27A.toInt())
        sb.append("$pending pending      ")
        dot(0x99B8BCD0.toInt())
        sb.append("$off off")
        return sb
    }

    private fun fmtMs(ms: Int): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun refreshNow() {
        val svc = PlayerService.instance
        val playing = PlayerService.isPlaying && svc != null
        if (playing != wasPlaying) {
            wasPlaying = playing
            if (!playing) peek = false
            shownId = -2
            refreshList()
        }
        val show = playing && !peek
        b.playScroll.visibility = if (show) View.VISIBLE else View.GONE
        b.list.visibility = if (show) View.GONE else View.VISIBLE
        b.nuCard.visibility = if (show) View.GONE else View.VISIBLE
        if (!show || svc == null) {
            stopPulse()
            return
        }
        startPulse()
        val dp = resources.displayMetrics.density
        val acc2 = ThemeStore.attrColor(this, R.attr.txAccent2, ThemeStore.attrColor(this, R.attr.txAccent, 0xFF7C5CFF.toInt()))
        val now = System.currentTimeMillis()
        val s = Store.get(this, PlayerService.nowId)

        // the playing schedule, drawn like a row of the list
        if (shownId != PlayerService.nowId) {
            shownId = PlayerService.nowId
            if (s != null) {
                val sr = ScheduleAdapter.src(this, s)
                b.pcAvatar.text = sr.ini
                b.pcAvatar.background = ScheduleAdapter.squircle(sr.color, 14 * dp)
                val (t, ap) = Scheduler.fmt12s(s.hour, s.minute, s.second)
                b.pcTime.text = t
                b.pcAmpm.text = ap
                b.pcSub.text = ScheduleAdapter.subText(this, s)
            } else {
                b.pcAvatar.text = "Ph"
                b.pcAvatar.background = ScheduleAdapter.squircle(acc2, 14 * dp)
                b.pcTime.text = PlayerService.nowTitle
                b.pcAmpm.text = ""
                b.pcSub.text = ""
            }
        }

        // Time left / Ends at: the exact stop time, or when the songs finish by themselves
        val stopAt = PlayerService.nowStopAt
        val rem = svc.remainingMs()
        var endAt = 0L
        var endSub = "No end time"
        if (stopAt > 0L) {
            endAt = stopAt
            endSub = "Exact stop"
        }
        if (rem >= 0L) {
            val nat = now + rem
            if (endAt == 0L || nat < endAt) {
                endAt = nat
                endSub = "Last song ends"
            }
        }
        if (endAt > 0L) {
            b.bxLeftVal.text = leftText(endAt - now)
            b.bxLeftSub.text = if (svc.isPaused()) "Paused" else "Counting down"
            b.bxEndVal.text = clockText(endAt, acc2)
            b.bxEndSub.text = endSub
        } else {
            b.bxLeftVal.text = "\u221E"
            b.bxLeftSub.text = "Until you stop"
            b.bxEndVal.text = "\u2014"
            b.bxEndSub.text = endSub
        }

        // Loop: which play we are on
        val plays = s?.plays ?: 1
        val no = PlayerService.nowPlayNo
        if (s != null && s.stopMode && s.loop) {
            b.bxLoopVal.text = "Play $no"
            b.bxLoopSub.text = "Until stop time"
        } else if (plays == 0) {
            b.bxLoopVal.text = "$no of \u221E"
            b.bxLoopSub.text = "Repeat active"
        } else if (plays > 1) {
            b.bxLoopVal.text = "${minOf(no, plays)} of $plays"
            b.bxLoopSub.text = "Repeat active"
        } else {
            b.bxLoopVal.text = "1 of 1"
            b.bxLoopSub.text = "Plays once"
        }

        // compact player
        val tr = PlayerService.nowTrack.ifBlank { "Starting\u2026" }
        val ext = tr.substringAfterLast('.', "")
        b.nowTrack.text = if (ext.isNotEmpty() && ext.length <= 4 && !ext.contains(' ')) tr.substringBeforeLast('.') else tr
        b.nowLabel.text = PlayerService.nowTitle
        val d = svc.duration()
        val p = svc.position()
        if (!userSeeking) {
            b.nowSeek.max = if (d > 0) d else 1
            b.nowSeek.progress = p
            b.nowPos.text = fmtMs(p)
        }
        b.nowDur.text = fmtMs(d)
        b.btnPlay.setImageResource(if (svc.isPaused()) R.drawable.ic_play_dark else R.drawable.ic_pause_dark)
        updateCover(PlayerService.nowUri)
    }

    /** Song with a picture inside (ID3 / album art): show it. No picture: the music note. */
    private fun updateCover(uri: String) {
        if (uri == coverFor) return
        coverFor = uri
        val have = covers.get(uri)
        if (have != null) {
            showCover(have)
            return
        }
        showCover(null)
        if (uri.isEmpty() || noCover.contains(uri)) return
        val ctx = applicationContext
        Thread {
            var bmp: Bitmap? = null
            try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, Uri.parse(uri))
                    val bytes = r.embeddedPicture
                    if (bytes != null && bytes.isNotEmpty()) {
                        val o = BitmapFactory.Options()
                        o.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                        var sample = 1
                        while (o.outWidth / sample > 512 && o.outHeight / sample > 512) sample *= 2
                        val o2 = BitmapFactory.Options()
                        o2.inSampleSize = sample
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
                    }
                } finally {
                    r.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val got = bmp
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (got != null) {
                    covers.put(uri, got)
                    if (coverFor == uri) showCover(got)
                } else {
                    noCover.add(uri)
                }
            }
        }.start()
    }

    private fun showCover(bmp: Bitmap?) {
        val dp = resources.displayMetrics.density
        if (bmp != null) {
            b.mpArt.setPadding(0, 0, 0, 0)
            b.mpArt.scaleType = ImageView.ScaleType.CENTER_CROP
            b.mpArt.setImageBitmap(bmp)
        } else {
            val pad = (13 * dp).toInt()
            b.mpArt.setPadding(pad, pad, pad, pad)
            b.mpArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.mpArt.setImageResource(R.drawable.ic_stat_music)
        }
    }

    /** "4m 08s", or "1h 04m" once it is an hour or more away. */
    private fun leftText(ms: Long): String {
        val t = (ms / 1000L).coerceAtLeast(0L)
        val h = t / 3600
        val m = (t % 3600) / 60
        val sec = t % 60
        return if (h > 0) String.format("%dh %02dm", h, m) else String.format("%dm %02ds", m, sec)
    }

    /** "12:33:34 PM" on one line, AM/PM a little smaller. */
    private fun clockText(ms: Long, accent: Int): CharSequence {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        val h = c.get(Calendar.HOUR_OF_DAY)
        val hh = if (h % 12 == 0) 12 else h % 12
        val t = String.format("%d:%02d:%02d", hh, c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
        val ap = if (h < 12) "AM" else "PM"
        val sp = SpannableString("$t $ap")
        val st = t.length + 1
        sp.setSpan(RelativeSizeSpan(0.66f), st, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(ForegroundColorSpan(accent), st, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ObjectAnimator.ofFloat(b.pcDot, "alpha", 1f, 0.35f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        b.pcDot.alpha = 1f
    }

    /** Colours of the playing view. Everything comes from the theme so it fits every theme. */
    private fun styleNow() {
        val dp = resources.displayMetrics.density
        val acc = ThemeStore.attrColor(this, R.attr.txAccent, 0xFF7C5CFF.toInt())
        val acc2 = ThemeStore.attrColor(this, R.attr.txAccent2, acc)
        val ink = ThemeStore.attrColor(this, R.attr.txText, 0xFFFFFFFF.toInt())
        val muted = ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8B92B5.toInt())
        val bg = ThemeStore.attrColor(this, R.attr.txBg, 0xFF000000.toInt())
        fun a(c: Int, alpha: Int) = ColorUtils.setAlphaComponent(c, alpha)

        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(0xFF2EE08A.toInt())
        dot.setStroke((3 * dp).toInt(), bg)
        b.pcDot.background = dot

        for (v in listOf(b.bxLeft, b.bxEnd)) {
            val g = GradientDrawable()
            g.cornerRadius = 16 * dp
            g.setColor(a(acc, 0x33))
            v.background = g
        }
        b.bxLoop.background = ScheduleAdapter.glass(this, 16 * dp, true)
        listOf(b.bxLeftLbl, b.bxEndLbl).forEach { it.setTextColor(acc2) }
        b.bxLoopLbl.setTextColor(muted)
        listOf(b.bxLeftSub, b.bxLoopSub, b.bxEndSub).forEach { it.setTextColor(muted) }
        listOf(b.bxLeftVal, b.bxLoopVal, b.bxEndVal).forEach { it.setTextColor(ink) }

        b.btnStop.backgroundTintList = ColorStateList.valueOf(a(ink, 0x1F))
        b.btnStop.setTextColor(ink)

        b.mpCard.background = ScheduleAdapter.glass(this, 20 * dp, true)
        b.mpArt.background = ScheduleAdapter.squircle(acc, 13 * dp)
        b.mpArt.clipToOutline = true
        b.mpArt.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 13 * dp)
            }
        }
        b.nowTrack.setTextColor(ink)
        b.nowLabel.setTextColor(acc2)
        b.nowPos.setTextColor(muted)
        b.nowDur.setTextColor(muted)
        b.nowSeek.progressTintList = ColorStateList.valueOf(acc2)
        b.nowSeek.progressBackgroundTintList = ColorStateList.valueOf(a(ink, 0x26))
        b.nowSeek.thumbTintList = ColorStateList.valueOf(acc2)
        for (v in listOf(b.btnPrev, b.btnBack, b.btnFwd, b.btnNext)) {
            v.background = ScheduleAdapter.glass(this, 15 * dp, true)
            v.imageTintList = ColorStateList.valueOf(ink)
        }
        val on = ThemeStore.attrColor(this, R.attr.txOnAccent, 0xFFFFFFFF.toInt())
        val pb = GradientDrawable()
        pb.cornerRadius = 19 * dp
        pb.setColor(acc)
        b.btnPlay.background = pb
        b.btnPlay.imageTintList = ColorStateList.valueOf(on)
    }

    private fun onToggle(s: Schedule, on: Boolean) {
        s.enabled = on
        if (on) {
            s.onceAt = 0L
            Scheduler.arm(this, s)
            Toast.makeText(
                this,
                "Starts " + Scheduler.untilText(Scheduler.nextTrigger(s) - System.currentTimeMillis()),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Scheduler.cancel(this, s.id)
        }
        Store.upsert(this, s)
        handler.post { refreshList() }
    }

    /** Delete: the same rounded sheet as the rest of the app, one question, two pills. */
    private fun onDelete(s: Schedule) {
        val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sb = com.tunex.app.databinding.SheetConfirmBinding.inflate(layoutInflater)
        dlg.setContentView(sb.root)
        (sb.root.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        sb.cfTitle.setText(R.string.s_delete_title)
        sb.cfBody.setText(R.string.s_delete_body)
        sb.cfKeep.setText(R.string.s_keep)
        sb.cfDelete.setText(R.string.s_delete_yes)
        val danger = ThemeStore.attrColor(this, R.attr.txDanger, 0xFFFF6B81.toInt())
        sb.cfIcon.imageTintList = android.content.res.ColorStateList.valueOf(danger)
        sb.cfName.text = s.title()
        sb.cfKeep.setOnClickListener { dlg.dismiss() }
        sb.cfDelete.setOnClickListener {
            dlg.dismiss()
            Scheduler.cancel(this, s.id)
            Store.delete(this, s.id)
            refreshList()
        }
        dlg.show()
        dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
    }
}

