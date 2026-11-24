package com.tunex.app

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.tunex.app.databinding.ActivityVideoBinding

/** Plays videos (and audio files) from the phone full-screen. Screen turns on and stays on. */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        @Volatile var running = false
    }

    private var takeRx: android.content.BroadcastReceiver? = null
    private var resumePos = 0

    private lateinit var b: ActivityVideoBinding
    private val handler = Handler(Looper.getMainLooper())
    private var sched: Schedule? = null
    private var list: List<Uri> = emptyList()
    private var idx = 0
    private var errors = 0
    private var closing = false
    private var userSeeking = false
    private var controlsShown = true
    private var playNo = 1

    private val hideRunnable = Runnable { hideControls() }

    /** Shows the top bar + controls, then hides them again after a few seconds. */
    private fun showControls() {
        if (closing) return
        controlsShown = true
        for (v in listOf(b.vidTop, b.vidControls)) {
            v.visibility = android.view.View.VISIBLE
            v.animate().alpha(1f).setDuration(180).start()
        }
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 3500)
    }

    private fun hideControls() {
        if (closing) return
        // keep them while dragging the seekbar or while paused
        if (userSeeking || !b.vidView.isPlaying) {
            handler.postDelayed(hideRunnable, 2000)
            return
        }
        controlsShown = false
        for (v in listOf(b.vidTop, b.vidControls)) {
            v.animate().alpha(0f).setDuration(220).withEndAction { v.visibility = android.view.View.INVISIBLE }.start()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private fun fmt(ms: Int): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun updateProgress() {
        if (closing) return
        val d = b.vidView.duration
        val p = b.vidView.currentPosition
        if (d > 0) {
            b.vidSeek.max = d
            b.vidDur.text = fmt(d)
            if (!userSeeking) b.vidSeek.progress = p
        }
        if (!userSeeking) b.vidPos.text = fmt(p)
        b.vidPlay.setImageResource(if (b.vidView.isPlaying) R.drawable.ic_pause_dark else R.drawable.ic_play_dark)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.vidStop.setOnClickListener { finishPlayback(true) }

        b.vidSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) b.vidPos.text = fmt(progress)
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {
                userSeeking = true
                showControls()
            }

            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                b.vidView.seekTo(sb?.progress ?: 0)
                userSeeking = false
                showControls()
            }
        })
        b.vidPlay.setOnClickListener {
            showControls()
            if (b.vidView.isPlaying) b.vidView.pause() else b.vidView.start()
            updateProgress()
        }
        b.vidBack.setOnClickListener {
            showControls()
            b.vidView.seekTo((b.vidView.currentPosition - 10_000).coerceAtLeast(0))
        }
        b.vidFwd.setOnClickListener {
            showControls()
            val d = b.vidView.duration
            b.vidView.seekTo((b.vidView.currentPosition + 10_000).let { if (d > 0) minOf(it, d - 500) else it })
        }
        b.vidPrev.setOnClickListener {
            showControls()
            if (b.vidView.currentPosition > 3000 || idx == 0) {
                b.vidView.seekTo(0)
            } else {
                idx--
                playCurrent()
            }
        }
        b.vidNext.setOnClickListener {
            showControls()
            next()
        }
        handler.post(ticker)
        // tap the video to show / hide the controls
        b.vidView.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_UP) {
                if (controlsShown) {
                    handler.removeCallbacks(hideRunnable)
                    hideControls()
                    if (!b.vidView.isPlaying) showControls()
                } else {
                    showControls()
                }
            }
            true
        }
        showControls()

        b.vidView.setOnPreparedListener {
            errors = 0
            b.vidStatus.text = "Playing " + (idx + 1) + " of " + list.size
            if (resumePos > 0) {
                it.seekTo(resumePos)
                resumePos = 0
            }
            it.start()
        }
        b.vidView.setOnCompletionListener { next() }
        b.vidView.setOnErrorListener { _, _, _ ->
            errors++
            if (errors >= list.size) {
                b.vidStatus.text = "This file can't be played"
                handler.postDelayed({ finishPlayback() }, 2500)
            } else {
                next()
            }
            true
        }
        if (!startFor(intent)) finish()
    }

    /**
     * Another schedule was started while this screen is already open (single-instance screen, so
     * Android hands the new schedule to us): switch to it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (closing) {
            try {
                startActivity(Intent(intent))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }
        if (intent.getIntExtra("id", -1) == sched?.id) return // same schedule fired again: keep playing
        handler.removeCallbacksAndMessages(null) // also drops a pending "step aside" for the old schedule
        try {
            takeRx?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        takeRx = null
        try {
            b.vidView.stopPlayback()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        handler.post(ticker) // removeCallbacksAndMessages above also stopped the progress ticker
        if (!startFor(intent)) {
            finish()
            return
        }
        showControls()
    }

    /** Sets everything up for the schedule in this intent. False = schedule not found / no files. */
    private fun startFor(i: Intent): Boolean {
        val id = i.getIntExtra("id", -1)
        val s = Store.get(this, id)
        if (s == null || s.uris.isEmpty()) return false
        sched = s
        list = s.uris.map { Uri.parse(it) }
        idx = 0
        errors = 0
        resumePos = 0
        userSeeking = false
        closing = false
        running = true
        // only react while THIS schedule is the one on screen (a late "step aside" for an old one is ignored)
        takeRx = Takeover.listen(this, s.id) { if (sched?.id == s.id) stepAside() }
        val rp = if (i.getBooleanExtra("resume", false)) Takeover.takePosition(this, s.id)
        else {
            Takeover.forgetPosition(this, s.id)
            null
        }
        if (rp != null) {
            idx = rp.first.coerceIn(0, list.size - 1)
            resumePos = rp.second.toInt()
        }
        // handed over from the audio screen: continue at the video that comes next
        playNo = i.getIntExtra("playNo", 1).coerceAtLeast(1)
        val si = i.getIntExtra("startIndex", -1)
        if (si >= 0) {
            idx = si.coerceIn(0, list.size - 1)
            resumePos = 0
        }
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(2000 + id)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (i.getBooleanExtra("alarm", false)) {
            getSharedPreferences("tunex_ui", MODE_PRIVATE).edit()
                .putLong("lastStart", System.currentTimeMillis()).apply()
        }

        b.vidTitle.text = s.title()
        Scheduler.applyVolume(this, s.volume)

        val dl = Scheduler.stopDeadline(s)
        if (dl > 0L) {
            handler.postDelayed({ finishPlayback() }, (dl - System.currentTimeMillis()).coerceAtLeast(1000L))
        }
        playCurrent()
        return true
    }

    private fun playCurrent() {
        if (closing) return
        // an audio file belongs on the audio screen: hand over, and come back for the next video
        if (!Ext.isVideo(this, list[idx].toString()) && handToAudio()) return
        b.vidStatus.text = "Loading…"
        try {
            b.vidView.setVideoURI(list[idx])
        } catch (e: Exception) {
            e.printStackTrace()
            next()
        }
    }

    private fun next() {
        if (closing) return
        idx++
        if (idx >= list.size) {
            if (sched?.loop == true) {
                idx = 0
                playNo++
            } else {
                finishPlayback()
                return
            }
        }
        playCurrent()
    }

    /** Starts the audio player at the current file and closes this video screen without ending the schedule. */
    private fun handToAudio(): Boolean {
        val id = sched?.id ?: return false
        return try {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                Intent(this, PlayerService::class.java)
                    .putExtra("id", id)
                    .putExtra("startIndex", idx)
                    .putExtra("playNo", playNo)
            )
            closing = true
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try { b.vidView.stopPlayback() } catch (e: Exception) { }
            finish()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * A newer schedule took over: pause now and close shortly after - unless that newer schedule is
     * a video one too and takes this very screen over first (onNewIntent cancels the close).
     */
    private fun stepAside() {
        if (closing) return
        try {
            b.vidView.pause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        handler.postDelayed({
            if (closing) return@postDelayed
            closing = true
            try {
                b.vidView.stopPlayback()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            finish()
        }, 700)
    }

    private fun finishPlayback(manual: Boolean = false) {
        if (closing) return
        closing = true
        val id = sched?.id ?: -1
        if (manual) Takeover.onManualStop(this, id) else Takeover.onEnded(this, id)
        try {
            b.vidView.stopPlayback()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finish()
    }

    override fun onDestroy() {
        running = false
        try {
            takeRx?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        handler.removeCallbacksAndMessages(null)
        try {
            b.vidView.stopPlayback()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}

