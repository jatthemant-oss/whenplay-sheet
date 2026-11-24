package com.tunex.app

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent

/** Spotify / JioSaavn launcher. Tries "play from search" (what voice assistants use) first. */
object Ext {
    const val SPOTIFY = "spotify"
    const val JIO = "jiosaavn"
    const val INSTAGRAM = "instagram"

    fun pkg(app: String): String = when (app) {
        SPOTIFY -> "com.spotify.music"
        INSTAGRAM -> "com.instagram.android"
        else -> "com.jio.media.jiobeats"
    }

    fun title(app: String): String = when (app) {
        SPOTIFY -> "Spotify"
        INSTAGRAM -> "Instagram"
        else -> "JioSaavn"
    }

    /** Pulls the link out of shared text ("Song - Artist https://...?si=xyz") and drops tracking params. */
    fun cleanLink(raw: String): String {
        val t = raw.trim()
        val m = Regex("(https?://\\S+|spotify:[A-Za-z]+:[A-Za-z0-9]+)").find(t) ?: return t
        val link = m.value.trimEnd('.', ',', ')', '"')
        // YouTube "watch" links carry the video id in ?v=  - keep it, drop everything else
        if (link.contains("youtube.com/watch", true)) {
            val v = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(link)
            if (v != null) return "https://www.youtube.com/watch?v=" + v.groupValues[1]
        }
        return link.substringBefore("?").substringBefore("#")
    }

    fun isLink(t: String): Boolean {
        val u = t.trim().lowercase()
        return u.startsWith("http") || u.startsWith("spotify:") || u.contains(".com/")
    }

    /** Accepts a link of the right app, or plain text (song name). */
    fun valid(app: String, text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return false
        if (!isLink(t)) return false
        val u = t.lowercase()
        return when (app) {
            SPOTIFY -> u.startsWith("spotify:") || u.contains("spotify.com/")
            INSTAGRAM -> (u.contains("instagram.com/") || u.contains("instagr.am/")) &&
                listOf("/reel/", "/reels/", "/p/", "/tv/").any { u.contains(it) }
            else -> u.contains("jiosaavn.com") || u.contains("saavn.com")
        }
    }

    /**
     * Instagram can only be paused from outside if it exposes a media session. Try that first;
     * otherwise bring WhenPlay to the front, so the reel goes to the background and pauses.
     */
    fun pauseInstagram(ctx: Context, bringFront: Boolean = true) {
        if (control(ctx, pkg(INSTAGRAM), KeyEvent.KEYCODE_MEDIA_PAUSE)) return
        if (!bringFront) return
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** open.spotify.com/track/ID -> spotify:track:ID so the app opens that exact item. */
    private fun spotifyUri(raw: String): Uri {
        if (raw.lowercase().startsWith("spotify:")) return Uri.parse(raw)
        return try {
            val u = Uri.parse(raw)
            val seg = u.pathSegments.filter { !it.startsWith("intl-") }
            if (seg.size >= 2) Uri.parse("spotify:" + seg[0] + ":" + seg[1]) else u
        } catch (e: Exception) {
            Uri.parse(raw)
        }
    }

    fun intent(ctx: Context, s: Schedule): Intent {
        val raw = s.extUrl.trim()
        val pkg = pkg(s.extApp)
        val pm = ctx.packageManager

        // 1) A pasted link: open EXACTLY that link in the app (never re-search it).
        if (isLink(raw)) {
            val uri = if (s.extApp == SPOTIFY) spotifyUri(raw) else Uri.parse(raw)
            val v = Intent(Intent.ACTION_VIEW, uri)
            v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setPackage(pkg)
            if (v.resolveActivity(pm) == null) v.setPackage(null)
            return v
        }

        // 2) Plain song name: ask for a SONG (not radio / artist / playlist)
        if (raw.isNotBlank()) {
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            i.setPackage(pkg)
            i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            i.putExtra(MediaStore.EXTRA_MEDIA_TITLE, raw)
            i.putExtra(SearchManager.QUERY, raw)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(pm) != null) return i
        }
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return launch
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(raw)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    data class Hit(val title: String, val sub: String, val url: String)

    private fun clean(t: String): String =
        android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun norm(t: String): String = words(clean(t).replace(Regex("[(\\[].*?[)\\]]"), " ")).joinToString(" ")

    /**
     * JioSaavn: find songs whose TITLE is exactly what was typed (not albums / radios).
     * Must run off the main thread. Empty list = nothing found / no network.
     */
    fun searchJio(query: String): List<Hit> {
        return try {
            val u = java.net.URL(
                "https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&includeMetaTags=1&query=" +
                    java.net.URLEncoder.encode(query, "UTF-8")
            )
            val c = u.openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = org.json.JSONObject(body).getJSONObject("songs").getJSONArray("data")
            val q = norm(query)
            val exact = ArrayList<Hit>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val link = o.optString("url").ifBlank { o.optString("perma_url") }
                if (link.isBlank()) continue
                val title = clean(o.optString("title"))
                if (norm(title) != q) continue
                val sub = clean(o.optString("album").ifBlank { o.optString("description") })
                exact.add(Hit(title, sub, link))
            }
            exact
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun httpGet(url: String): String? = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    /** Video / song title from the link (best effort, "" = unknown). Run off the main thread. */
    fun fetchTitle(kind: String, link: String): String {
        return try {
            if (!link.startsWith("http")) return ""
            val api = when (kind) {
                "youtube" -> "https://www.youtube.com/oembed?format=json&url="
                SPOTIFY -> "https://open.spotify.com/oembed?url="
                else -> return ""
            }
            val body = httpGet(api + java.net.URLEncoder.encode(link, "UTF-8")) ?: return ""
            org.json.JSONObject(body).optString("title", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** Song length in seconds from the link (best effort, 0 = unknown). Run off the main thread. */
    fun fetchSec(kind: String, link: String): Int {
        return try {
            when (kind) {
                SPOTIFY -> {
                    val u = spotifyUri(link).toString().split(":")
                    if (u.size < 3 || u[1] != "track") return 0
                    val body = httpGet("https://open.spotify.com/embed/track/" + u[2]) ?: return 0
                    val ms = Regex("\"duration\":(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return 0
                    (if (ms > 10_000) ms / 1000 else ms).toInt()
                }
                JIO -> {
                    val token = Uri.parse(link).pathSegments.lastOrNull() ?: return 0
                    val body = httpGet(
                        "https://www.jiosaavn.com/api.php?__call=webapi.get&token=" + token +
                            "&type=song&includeMetaTags=0&ctx=web6dot0&api_version=4&_format=json&_marker=0"
                    ) ?: return 0
                    Regex("\"duration\":\"?(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                INSTAGRAM -> {
                    val code = Regex("""instagram\.com/(?:reels?|p|tv)/([A-Za-z0-9_-]+)""").find(link)?.groupValues?.get(1)
                        ?: Regex("""instagr\.am/(?:reels?|p|tv)/([A-Za-z0-9_-]+)""").find(link)?.groupValues?.get(1)
                        ?: return 0
                    val body = httpGet("https://www.instagram.com/reel/" + code + "/embed/") ?: return 0
                    val d = Regex("""video_duration\\*"\s*:\s*([0-9]+(?:\.[0-9]+)?)""").find(body)
                        ?.groupValues?.get(1)?.toDoubleOrNull() ?: return 0
                    Math.ceil(d).toInt()
                }
                "youtube" -> {
                    val body = httpGet(link) ?: return 0
                    Regex("\"lengthSeconds\":\"(\\d+)\"").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    data class Song(val title: String, val artist: String, val album: String, val sec: Int, val url: String = "")

    /** Spotify has no key-less search, so use Apple's open catalogue to find songs with EXACTLY this title. */
    fun searchSongs(query: String): List<Song> {
        return try {
            val body = httpGet(
                "https://itunes.apple.com/search?media=music&entity=song&limit=40&country=IN&term=" +
                    java.net.URLEncoder.encode(query, "UTF-8")
            ) ?: return emptyList()
            val arr = org.json.JSONObject(body).getJSONArray("results")
            val q = norm(query)
            val out = ArrayList<Song>()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.optString("trackName")
                if (norm(t) != q) continue
                val artist = o.optString("artistName")
                if (!seen.add(norm(t) + "|" + artist.lowercase())) continue
                out.add(Song(t, artist, o.optString("collectionName"), (o.optLong("trackTimeMillis") / 1000).toInt(), o.optString("trackViewUrl")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** True when the file is a video (by MIME type, falling back to the file extension). */
    fun isVideo(ctx: Context, uri: String): Boolean {
        val t = try {
            ctx.contentResolver.getType(Uri.parse(uri))
        } catch (e: Exception) {
            null
        }
        if (t != null && t != "application/octet-stream") return t.startsWith("video/")
        val l = uri.lowercase()
        return listOf(".mp4", ".mkv", ".webm", ".3gp", ".mov", ".avi", ".m4v").any { l.contains(it) }
    }

    fun hasVideo(ctx: Context, s: Schedule): Boolean = s.uris.any { isVideo(ctx, it) }

    /** Finds the exact Spotify track link for a song (via its Apple Music page + song.link). Null if not found. */
    fun spotifyLink(appleUrl: String): String? {
        if (appleUrl.isBlank()) return null
        return try {
            val body = httpGet(
                "https://api.song.link/v1-alpha.1/links?userCountry=IN&url=" + java.net.URLEncoder.encode(appleUrl, "UTF-8")
            ) ?: return null
            val u = org.json.JSONObject(body).getJSONObject("linksByPlatform").getJSONObject("spotify").getString("url")
            cleanLink(u).takeIf { it.contains("/track/") }
        } catch (e: Exception) {
            null
        }
    }

    fun listenerEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** Direct play/pause on the app's own media session (needs Notification access). */
    fun control(ctx: Context, pkg: String, key: Int): Boolean {
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(ctx, TuneXListener::class.java))
            val c = list.firstOrNull { it.packageName == pkg } ?: return false
            when (key) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> c.transportControls.pause()
                KeyEvent.KEYCODE_MEDIA_STOP -> c.transportControls.stop()
                else -> c.transportControls.play()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun session(ctx: Context, pkg: String): MediaController? = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(ctx, TuneXListener::class.java)).firstOrNull { it.packageName == pkg }
    } catch (e: Exception) {
        null
    }

    private fun words(t: String): Set<String> =
        t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(" ").filter { it.isNotBlank() }.toSet()

    /** Song title we expect (from JioSaavn link slug or typed name). Blank = unknown. */
    private fun wantedTitle(s: Schedule): String {
        val raw = s.extUrl.trim()
        if (!isLink(raw)) return if (raw.startsWith("spotify:", true)) "" else raw
        return try {
            // /song/<title>/<id>  and  /s/song/<lang>/<album>/<title>/<id>  -> title is the 2nd-last part
            val seg = Uri.parse(raw).pathSegments
            if (s.extApp != SPOTIFY && seg.contains("song") && seg.size >= 3) seg[seg.size - 2].replace("-", " ") else ""
        } catch (e: Exception) {
            ""
        }
    }

    private val cmdCount = HashMap<Int, Int>()
    private val cmdAt = HashMap<Int, Long>()

    fun resetRun(id: Int) {
        cmdCount.remove(id)
        cmdAt.remove(id)
    }

    /**
     * Start OUR song, never just resume whatever was paused before, and never restart it again
     * once it is playing.  0 = app not ready, 1 = asked / waiting, 2 = our song is playing.
     */
    fun startTrack(ctx: Context, s: Schedule, force: Boolean = false): Int {
        val pkg = pkg(s.extApp)
        val c = session(ctx, pkg) ?: return 0
        val raw = s.extUrl.trim()
        val want = wantedTitle(s)
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val mediaId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
        val isSpotifyLink = s.extApp == SPOTIFY && isLink(raw)
        val wantUri = if (isSpotifyLink) spotifyUri(raw).toString() else ""
        val matches = when {
            isSpotifyLink -> mediaId.equals(wantUri, true)
            want.isNotBlank() && title.isNotBlank() -> {
                val w = words(want)
                val t = words(title)
                w.isNotEmpty() && t.isNotEmpty() &&
                    (w.containsAll(t) || w.intersect(t).size >= Math.max(1, Math.ceil(w.size * 0.6).toInt()))
            }
            else -> false
        }
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val n = cmdCount[s.id] ?: 0
        val now = System.currentTimeMillis()
        val mine = matches || (want.isBlank() && !isSpotifyLink && playing)
        if (mine) {
            if (!playing) c.transportControls.play()
            return if (playing) 2 else 1
        }
        // we already asked once and something is playing: trust it, NEVER restart the song
        if (playing && n >= 1 && !force) return 2
        // JioSaavn song link: the app opens and plays it by itself. Give it time, never override it.
        if (isLink(raw) && !isSpotifyLink && !force) {
            if (playing) return 2
            if (cmdAt[s.id] == null) {
                cmdAt[s.id] = now
                return 1
            }
        }
        if (n >= 2 || now - (cmdAt[s.id] ?: 0L) < 10_000L) return 1
        cmdCount[s.id] = n + 1
        cmdAt[s.id] = now
        val tc = c.transportControls
        if (isSpotifyLink) {
            tc.playFromUri(Uri.parse(wantUri), null)
        } else {
            val q = if (want.isNotBlank()) want else raw
            val ex = android.os.Bundle()
            ex.putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            ex.putString(MediaStore.EXTRA_MEDIA_TITLE, q)
            tc.playFromSearch(q, ex)
        }
        return 1
    }

    /** Milliseconds left in the current track of that app, or -1. */
    fun msLeft(ctx: Context, pkg: String): Long {
        val c = session(ctx, pkg) ?: return -1L
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val st = c.playbackState ?: return -1L
        if (dur <= 0L || st.state != PlaybackState.STATE_PLAYING) return -1L
        val pos = st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        return (dur - pos).coerceAtLeast(0L)
    }

    fun durMs(ctx: Context, pkg: String): Long =
        session(ctx, pkg)?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    /** Identity of the track the app is on right now (title | id | length). */
    fun fingerprint(ctx: Context, pkg: String): String {
        val md = session(ctx, pkg)?.metadata ?: return ""
        return (md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "") + "|" +
            (md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "") + "|" +
            md.getLong(MediaMetadata.METADATA_KEY_DURATION)
    }

    fun isPlayingNow(ctx: Context, pkg: String): Boolean =
        session(ctx, pkg)?.playbackState?.state == PlaybackState.STATE_PLAYING

    /**
     * Repeat: play OUR song again. Spotify ignores "seek to 0" on free accounts, so ask for the exact
     * track again; for other apps jump back to 0:00.
     */
    fun repeatTrack(ctx: Context, s: Schedule): Boolean {
        val pkg = pkg(s.extApp)
        val c = session(ctx, pkg) ?: return false
        val raw = s.extUrl.trim()
        if (s.extApp == SPOTIFY && isLink(raw)) {
            c.transportControls.playFromUri(spotifyUri(raw), null)
        } else {
            c.transportControls.seekTo(0)
            c.transportControls.play()
        }
        return true
    }

    /** Stops the app's playback completely (used when a newer schedule overshadows this one). */
    fun stopApp(ctx: Context, pkg: String) {
        try {
            session(ctx, pkg)?.transportControls?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // also send the hardware-style STOP key, in case the app ignores the session command
        sendKey(ctx, KeyEvent.KEYCODE_MEDIA_STOP, pkg)
    }

    /** Repeat (old name): jump back to 0:00 and keep playing. */
    fun restartTrack(ctx: Context, pkg: String): Boolean {
        val c = session(ctx, pkg) ?: return false
        c.transportControls.seekTo(0)
        c.transportControls.play()
        return true
    }

    fun sendKey(ctx: Context, key: Int, pkg: String? = null) {
        if (!pkg.isNullOrBlank()) control(ctx, pkg, key)
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // also hand the key straight to that app's own media-button receiver
        if (!pkg.isNullOrBlank()) {
            try {
                for (act in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val i = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                    i.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(act, key))
                    ctx.sendBroadcast(i)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

