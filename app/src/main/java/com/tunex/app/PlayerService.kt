package com.tunex.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat

class PlayerService : Service() {

    companion object {
        const val ACTION_STOP = "com.tunex.app.STOP"
        const val ACTION_TOGGLE = "com.tunex.app.TOGGLE"
        const val ACTION_NEXT = "com.tunex.app.NEXT"
        const val ACTION_PREV = "com.tunex.app.PREV"
        const val CHANNEL = "tunex_play"
        const val MODE_MANUAL = 0
        const val MODE_AUTO = 1
        const val MODE_QUIET = 2
        @Volatile var instance: PlayerService? = null
        @Volatile var isPlaying = false
        @Volatile var nowTitle = ""
        @Volatile var nowTrack = ""
        /** Uri of the song that is playing now, so the screen can read its cover art. */
        @Volatile var nowUri = ""
        /** Which schedule is playing, which play of its loop we are on, and its exact stop time (0 = none). */
        @Volatile var nowId = -1
        @Volatile var nowPlayNo = 1
        @Volatile var nowStopAt = 0L
    }

    private var mp: MediaPlayer? = null
    private var uris: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var index = 0
    private var label = "The Music"
    private var loop = false
    private var ready = false
    private var userPaused = false
    private var pausedByFocus = false
    private var fromAlarm = false
    private var errorStreak = 0
    private var wl: PowerManager.WakeLock? = null
    private var afr: AudioFocusRequest? = null
    private var session: MediaSession? = null
    private var stopPi: PendingIntent? = null
    private val main = Handler(Looper.getMainLooper())
    private var curId = -1
    private var takeRx: android.content.BroadcastReceiver? = null
    private var resumeMs = 0
    private var durs: LongArray = LongArray(0)
    private var skipVideo = false
    private var waitingVideo = false

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        try {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    val p = mp
                    if (p != null && ready && p.isPlaying) {
                        p.pause()
                        pausedByFocus = true
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (pausedByFocus && !userPaused) {
                        mp?.start()
                    }
                    pausedByFocus = false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------- public controls (used by UI, notification, lockscreen) ----------

    fun position(): Int = try {
        if (ready) (mp?.currentPosition ?: 0) else 0
    } catch (e: Exception) { 0 }

    fun duration(): Int = try {
        if (ready) (mp?.duration ?: 0) else 0
    } catch (e: Exception) { 0 }

    fun isPaused(): Boolean = userPaused

    /** Milliseconds until the last song ends if nothing loops. -1 = not known (loop, or a file length is missing). */
    fun remainingMs(): Long {
        if (loop) return -1L
        val d = durs
        if (d.size != uris.size || d.isEmpty() || d.any { it <= 0L }) return -1L
        val i = index.coerceIn(0, d.size - 1)
        var r = (d[i] - position()).coerceAtLeast(0L)
        for (k in i + 1 until d.size) r += d[k]
        return r
    }

    fun seekTo(ms: Int) {
        try {
            if (!ready) return
            mp?.seekTo(ms.coerceAtLeast(0))
            updateState()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekBy(delta: Int) {
        val d = duration()
        val target = position() + delta
        seekTo(if (d > 0) target.coerceIn(0, d - 500) else target)
    }

    fun togglePause() {
        try {
            val p = mp ?: return
            if (!ready) return
            if (userPaused) {
                p.start()
                userPaused = false
            } else if (p.isPlaying) {
                p.pause()
                userPaused = true
            }
            updateState()
            updateNotif()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun next() {
        userPaused = false
        advance()
    }

    fun prev() {
        userPaused = false
        if (position() > 3000 || index == 0) {
            seekTo(0)
            if (!userPaused) try { mp?.start() } catch (e: Exception) { }
            updateState()
            updateNotif()
        } else {
            index--
            playCurrent()
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        instance = this
        acquireLock() // grab CPU wake lock as early as possible
        try {
            session = MediaSession(this, "TuneX").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { if (userPaused) togglePause() }
                    override fun onPause() { if (!userPaused) togglePause() }
                    override fun onSkipToNext() { next() }
                    override fun onSkipToPrevious() { prev() }
                    override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                    override fun onStop() { stopAll() }
                })
                isActive = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // the stop-time alarm adds "auto"; the notification / app Stop button does not
                stopAll(if (intent?.getBooleanExtra("auto", false) == true) MODE_AUTO else MODE_MANUAL)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> { if (isPlaying) togglePause() else stopSelf(); return START_NOT_STICKY }
            ACTION_NEXT -> { if (isPlaying) next() else stopSelf(); return START_NOT_STICKY }
            ACTION_PREV -> { if (isPlaying) prev() else stopSelf(); return START_NOT_STICKY }
        }

        createChannel()
        userPaused = false
        errorStreak = 0
        val s = Store.get(this, intent?.getIntExtra("id", -1) ?: -1)
        label = s?.title() ?: "The Music"
        loop = s?.loop ?: false
        fromAlarm = intent?.getBooleanExtra("alarm", false) ?: false
        nowTrack = ""
        nowUri = ""
        ServiceCompat.startForeground(this, 1, buildNotif(), fgType())

        if (s == null || s.uris.isEmpty()) {
            stopAll(MODE_AUTO)
            return START_NOT_STICKY
        }
        uris = s.uris
        names = s.names
        index = 0
        curId = s.id
        resumeMs = 0
        val rp = if (intent?.getBooleanExtra("resume", false) == true) Takeover.takePosition(this, s.id)
        else {
            Takeover.forgetPosition(this, s.id)
            null
        }
        if (rp != null) {
            index = rp.first.coerceIn(0, uris.size - 1)
            resumeMs = rp.second.toInt()
        }
        // handed over from the video screen: continue at the audio file that comes next
        val si = intent?.getIntExtra("startIndex", -1) ?: -1
        if (si >= 0) {
            index = si.coerceIn(0, uris.size - 1)
            resumeMs = 0
        }
        skipVideo = false
        waitingVideo = false
        try {
            takeRx?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        takeRx = Takeover.listen(this, s.id) {
            stopAll(MODE_QUIET)
        }
        acquireLock()
        Scheduler.applyVolume(this, s.volume)
        requestFocus()
        scheduleStopAt(s)
        nowId = s.id
        nowPlayNo = (intent?.getIntExtra("playNo", 1) ?: 1).coerceAtLeast(1)
        nowStopAt = Scheduler.stopDeadline(s)
        durs = LongArray(0)
        val ul = uris
        val appCtx = applicationContext
        Thread {
            try {
                val d = LongArray(ul.size) { Scheduler.trackMs(appCtx, ul[it]) }
                if (uris === ul) durs = d
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
        isPlaying = true
        nowTitle = label
        playCurrent()
        return START_NOT_STICKY
    }

    private fun fgType(): Int =
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

    private fun acquireLock() {
        try {
            wl?.let { if (it.isHeld) it.release() }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TuneX:play").apply {
                setReferenceCounted(false)
                acquire(24L * 60L * 60L * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            afr = r
            am.requestAudioFocus(r)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Optional auto-stop at a clock time */
    private fun scheduleStopAt(s: Schedule) {
        val t = Scheduler.stopDeadline(s)
        if (t == 0L) return
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, 900,
                Intent(this, PlayerService::class.java).setAction(ACTION_STOP).putExtra("auto", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            stopPi = pi
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        main.postDelayed({ stopAll(MODE_AUTO) }, (t - System.currentTimeMillis()).coerceAtLeast(1000L))
    }

    // ---------- playback ----------

    private fun advance() {
        index++
        if (index >= uris.size) {
            if (loop && uris.isNotEmpty()) {
                index = 0
                nowPlayNo++
            } else {
                stopAll(MODE_AUTO)
                return
            }
        }
        playCurrent()
    }

    private fun playCurrent() {
        releasePlayer()
        ready = false
        if (index < 0 || index >= uris.size) {
            stopAll(MODE_AUTO)
            return
        }
        // a video file belongs on the video screen: hand over, and come back for the next audio file
        if (Ext.isVideo(applicationContext, uris[index])) {
            if (!skipVideo && handToVideo(index)) return
            errorStreak++
            if (errorStreak >= uris.size) stopAll(MODE_AUTO) else advance()
            return
        }
        nowTrack = names.getOrElse(index) { "Track ${index + 1}" }
        nowUri = uris[index]
        updateState()
        updateNotif()
        try {
            val p = MediaPlayer()
            mp = p
            p.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            p.setAudioAttributes(attrs)
            p.setDataSource(applicationContext, Uri.parse(uris[index]))
            val first = fromAlarm && index == 0
            p.setOnPreparedListener {
                errorStreak = 0
                ready = true
                if (resumeMs > 0) {
                    it.seekTo(resumeMs)
                    resumeMs = 0
                }
                if (!userPaused) it.start()
                if (first) {
                    getSharedPreferences("tunex_ui", MODE_PRIVATE).edit()
                        .putLong("lastStart", System.currentTimeMillis()).apply()
                }
                updateMeta()
                updateState()
                updateNotif()
            }
            p.setOnCompletionListener { main.post { advance() } }
            p.setOnErrorListener { _, _, _ ->
                main.post {
                    errorStreak++
                    if (errorStreak >= uris.size) stopAll(MODE_AUTO) else advance()
                }
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
            errorStreak++
            if (errorStreak >= uris.size) stopAll(MODE_AUTO) else advance()
        }
    }

    /** Opens the video screen for the video at [i]. If it doesn't open in time, videos are skipped here. */
    private fun handToVideo(i: Int): Boolean {
        return try {
            val vi = Intent(this, VideoPlayerActivity::class.java)
                .putExtra("id", curId)
                .putExtra("startIndex", i)
                .putExtra("playNo", nowPlayNo)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            waitingVideo = true
            Handoff.wakeScreen(this)
            Handoff.openScreen(this, curId, vi, "Tap to play video")
            var tries = 0
            val check = object : Runnable {
                override fun run() {
                    if (!waitingVideo) return
                    tries++
                    if (VideoPlayerActivity.running) {
                        // video screen has it now: leave quietly (the schedule goes on over there)
                        waitingVideo = false
                        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
                        stopSelf()
                    } else if (tries >= 8) {
                        waitingVideo = false
                        skipVideo = true
                        errorStreak++
                        if (errorStreak >= uris.size) stopAll(MODE_AUTO) else advance()
                    } else main.postDelayed(this, 500)
                }
            }
            main.postDelayed(check, 500)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun releasePlayer() {
        val p = mp ?: return
        mp = null
        ready = false
        try {
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.setOnPreparedListener(null)
            p.reset()
            p.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------- media session + notification ----------

    private fun updateMeta() {
        try {
            val md = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, nowTrack)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, label)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration().toLong())
                .build()
            session?.setMetadata(md)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateState() {
        try {
            val st = when {
                userPaused -> PlaybackState.STATE_PAUSED
                ready -> PlaybackState.STATE_PLAYING
                else -> PlaybackState.STATE_BUFFERING
            }
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP
            val ps = PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    st, position().toLong(),
                    if (st == PlaybackState.STATE_PLAYING) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
            session?.setPlaybackState(ps)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "WhenPlay playback", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    private fun act(icon: Int, title: String, action: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req,
            Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val total = uris.size
        val track = if (nowTrack.isBlank()) "Starting…" else nowTrack
        val sub = if (total > 1) "${index + 1}/$total  •  $label" else label

        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }

        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(track)
            .setContentText(sub)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(act(R.drawable.ic_prev, "Previous", ACTION_PREV, 3))
            .addAction(
                if (userPaused) act(R.drawable.ic_play, "Play", ACTION_TOGGLE, 2)
                else act(R.drawable.ic_pause, "Pause", ACTION_TOGGLE, 2)
            )
            .addAction(act(R.drawable.ic_next, "Next", ACTION_NEXT, 4))
            .addAction(act(R.drawable.ic_close, "Stop", ACTION_STOP, 1))
            .setStyle(style)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun updateNotif() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotif())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // App swiped from recents: keep playing (foreground service survives this)
    override fun onTaskRemoved(rootIntent: Intent?) {
        // intentionally do nothing
    }

    /** MODE_MANUAL: user pressed Stop. MODE_AUTO: song/stop time ended it. MODE_QUIET: another schedule took over. */
    fun stopAll(mode: Int = MODE_MANUAL) {
        val id = curId
        if (mode == MODE_AUTO) Takeover.onEnded(applicationContext, id)
        else if (mode == MODE_MANUAL) Takeover.onManualStop(applicationContext, id)
        releasePlayer()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    override fun onDestroy() {
        try {
            takeRx?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        instance = null
        isPlaying = false
        nowTrack = ""
        nowUri = ""
        nowId = -1
        nowPlayNo = 1
        nowStopAt = 0L
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            afr?.let { am.abandonAudioFocusRequest(it) }
            stopPi?.let {
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(it)
            }
            session?.isActive = false
            session?.release()
            wl?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}

