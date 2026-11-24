package com.tunex.app

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Invisible helper: wakes the screen, shows over the lock screen, asks the system to
 * dismiss the keyguard, and only then opens Spotify / JioSaavn (so they really start).
 */
class LockLaunchActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val s = Store.get(this, intent.getIntExtra("id", -1))
        if (s == null) {
            finish()
            return
        }
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= 26 && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = go(s)
                override fun onDismissCancelled() = go(s)
                override fun onDismissError() = go(s)
            })
            // never wait forever (secure lock / no answer)
            handler.postDelayed({ go(s) }, 2500)
        } else {
            go(s)
        }
    }

    private fun go(s: Schedule) {
        if (started) return
        started = true
        try {
            startActivity(Ext.intent(this, s))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        dropNotification(s)
        handler.postDelayed({
            dropNotification(s)
            finish()
        }, 2000)
    }

    /** The alarm's "Tap to open ..." notification has done its job; tapping it later would restart the song. */
    private fun dropNotification(s: Schedule) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(2000 + s.id)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

