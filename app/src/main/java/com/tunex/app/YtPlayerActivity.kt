package com.tunex.app

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.tunex.app.databinding.ActivityYtBinding

/** Plays a YouTube video with the official embedded player. Needs internet; screen stays on. */
class YtPlayerActivity : AppCompatActivity() {

    companion object {
        @Volatile var running = false
    }

    private var takeRx: android.content.BroadcastReceiver? = null
    private var startSec = 0

    private lateinit var b: ActivityYtBinding
    private val handler = Handler(Looper.getMainLooper())
    private var sched: Schedule? = null
    private var loaded = false
    private var closing = false
    inner class Bridge {
        @JavascriptInterface
        fun onEnded() {
            runOnUiThread { finishPlayback() }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            runOnUiThread {
                b.ytStatus.text = "This video can't be played here (error $code)"
            }
        }
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

        b = ActivityYtBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ytStop.setOnClickListener { finishPlayback(true) }

        val w = b.ytWeb
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webChromeClient = WebChromeClient()
        w.addJavascriptInterface(Bridge(), "Android")

        if (!startFor(intent)) finish()
    }

    /**
     * Another schedule was started while this screen is already open (the screen is single-instance,
     * so Android hands the new schedule to us instead of making a second screen): switch to it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (closing) {
            // this screen is already on its way out: open a fresh one for the new schedule
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
            b.ytWeb.loadUrl("about:blank")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (!startFor(intent)) finish()
    }

    /** Sets everything up for the schedule in this intent. False = schedule not found / not a YouTube one. */
    private fun startFor(i: Intent): Boolean {
        val id = i.getIntExtra("id", -1)
        val s = Store.get(this, id)
        if (s == null || s.ytId.isBlank()) return false
        sched = s
        closing = false
        loaded = false
        running = true
        // only react while THIS schedule is the one on screen (a late "step aside" for an old one is ignored)
        takeRx = Takeover.listen(this, s.id) { if (sched?.id == s.id) stepAside() }
        startSec = if (i.getBooleanExtra("resume", false))
            ((Takeover.takePosition(this, s.id)?.second ?: 0L) / 1000L).toInt()
        else {
            Takeover.forgetPosition(this, s.id)
            0
        }
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(2000 + id)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        b.ytTitle.text = s.title()
        Scheduler.applyVolume(this, s.volume)

        val dl = Scheduler.stopDeadline(s)
        if (dl > 0L) {
            handler.postDelayed({ finishPlayback() }, (dl - System.currentTimeMillis()).coerceAtLeast(1000L))
        }
        tryStart()
        return true
    }

    private fun tryStart() {
        if (loaded || closing) return
        val s = sched ?: return
        if (Yt.online(this)) {
            loaded = true
            b.ytStatus.text = "Playing…"
            if (intent.getBooleanExtra("alarm", false)) {
                getSharedPreferences("tunex_ui", MODE_PRIVATE).edit()
                    .putLong("lastStart", System.currentTimeMillis()).apply()
            }
            val loopJs = if (s.loop) "true" else "false"
            val vid = s.ytId
            val html = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;background:#000}#player{position:absolute;top:0;left:0;width:100%;height:100%}</style></head><body><div id="player"></div><script>var LOOP=$loopJs;var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';document.head.appendChild(tag);var player;function onYouTubeIframeAPIReady(){player=new YT.Player('player',{width:'100%',height:'100%',videoId:'$vid',playerVars:{autoplay:1,playsinline:1,rel:0,start:$startSec,origin:'https://tunex.app'},events:{onReady:function(e){e.target.playVideo();},onStateChange:function(e){if(e.data===0){if(LOOP){player.seekTo(0);player.playVideo();}else{Android.onEnded();}}},onError:function(e){Android.onError(e.data);}}});}</script></body></html>"""
            b.ytWeb.loadDataWithBaseURL("https://tunex.app", html, "text/html", "utf-8", null)
        } else {
            b.ytStatus.text = "Waiting for internet…"
            handler.postDelayed({ tryStart() }, 4000)
        }
    }

    /**
     * A newer schedule took over: silence the video now and close shortly after - unless that newer
     * schedule is a YouTube one too and takes this very screen over first (onNewIntent cancels the close).
     */
    private fun stepAside() {
        if (closing) return
        try {
            b.ytWeb.evaluateJavascript("try{player.pauseVideo();}catch(e){}", null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        handler.postDelayed({
            if (closing) return@postDelayed
            closing = true
            try {
                b.ytWeb.loadUrl("about:blank")
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
            b.ytWeb.loadUrl("about:blank")
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
            b.ytWeb.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}

