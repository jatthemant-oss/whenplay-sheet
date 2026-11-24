package com.tunex.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Yt {
    private val p1 = Regex("""(?:youtu\.be/|youtube\.com/(?:shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})""")
    private val p2 = Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
    private val p3 = Regex("""^[A-Za-z0-9_-]{11}$""")

    fun parseId(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        p1.find(s)?.let { return it.groupValues[1] }
        p2.find(s)?.let { return it.groupValues[1] }
        if (p3.matches(s)) return s
        return null
    }

    fun online(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return false
            val c = cm.getNetworkCapabilities(n) ?: return false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
}

