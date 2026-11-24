package com.tunex.app

import org.json.JSONArray
import org.json.JSONObject

/** days: bitmask, bit0 = Sunday ... bit6 = Saturday. 0 = play once. */
data class Schedule(
    val id: Int,
    var hour: Int,
    var minute: Int,
    var days: Int,
    var label: String,
    var uris: List<String>,
    var names: List<String>,
    var enabled: Boolean,
    var volume: Int,
    var onceAt: Long = 0L,
    var ytId: String = "",
    var ytUrl: String = "",
    var loop: Boolean = false,
    var stopEnabled: Boolean = false,
    var stopHour: Int = 0,
    var stopMinute: Int = 0,
    var extApp: String = "",
    var extUrl: String = "",
    var trackSec: Int = 0,
    var second: Int = 0,
    var stopSecond: Int = 0,
    var plays: Int = 1,
    var stopMode: Boolean = false
) {
    /** A name somebody typed (older schedules). The default "Schedule" counts as no name. */
    fun customName(): String =
        label.trim().let { if (it.isEmpty() || it.equals("Schedule", true)) "" else it }

    /** Name for notifications, player screens and dialogs: the typed name, otherwise what plays. */
    fun title(): String {
        val c = customName()
        if (c.isNotEmpty()) return c
        return when {
            ytId.isNotBlank() -> "YouTube"
            extApp == Ext.INSTAGRAM -> "Instagram reel"
            extApp.isNotBlank() -> Ext.title(extApp)
            names.isNotEmpty() -> {
                val first = names[0].substringBeforeLast('.').ifBlank { "The Music" }
                if (names.size > 1) first + " +" + (names.size - 1) else first
            }
            else -> "The Music"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("days", days)
        put("label", label)
        put("uris", JSONArray(uris))
        put("names", JSONArray(names))
        put("enabled", enabled)
        put("volume", volume)
        put("onceAt", onceAt)
        put("ytId", ytId)
        put("ytUrl", ytUrl)
        put("loop", loop)
        put("stopEnabled", stopEnabled)
        put("stopHour", stopHour)
        put("stopMinute", stopMinute)
        put("extApp", extApp)
        put("extUrl", extUrl)
        put("trackSec", trackSec)
        put("second", second)
        put("stopSecond", stopSecond)
        put("plays", plays)
        put("stopMode", stopMode)
    }

    companion object {
        fun fromJson(o: JSONObject): Schedule {
            val u = o.getJSONArray("uris")
            val n = o.getJSONArray("names")
            return Schedule(
                id = o.getInt("id"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                days = o.getInt("days"),
                label = o.getString("label"),
                uris = List(u.length()) { u.getString(it) },
                names = List(n.length()) { n.getString(it) },
                enabled = o.getBoolean("enabled"),
                volume = o.optInt("volume", 70),
                onceAt = o.optLong("onceAt", 0L),
                ytId = o.optString("ytId", ""),
                ytUrl = o.optString("ytUrl", ""),
                loop = o.optBoolean("loop", false),
                stopEnabled = o.optBoolean("stopEnabled", false),
                stopHour = o.optInt("stopHour", 0),
                stopMinute = o.optInt("stopMinute", 0),
                extApp = o.optString("extApp", ""),
                extUrl = o.optString("extUrl", ""),
                trackSec = o.optInt("trackSec", 0),
                second = o.optInt("second", 0),
                stopSecond = o.optInt("stopSecond", 0),
                plays = if (o.has("plays")) o.optInt("plays", 1) else if (o.optBoolean("loop", false) && !o.optBoolean("stopEnabled", false)) 0 else 1,
                stopMode = o.optBoolean("stopMode", o.optBoolean("stopEnabled", false))
            )
        }
    }
}

