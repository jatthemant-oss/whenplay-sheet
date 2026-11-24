package com.tunex.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tunex.app.databinding.ActivityEditBinding
import java.util.Calendar

class EditActivity : AppCompatActivity() {

    private lateinit var b: ActivityEditBinding
    private var hour = 6
    private var minute = 0
    private var second = 0
    private var stopSecond = 0
    private var stopMode = false   // user chose a Stop time (otherwise Auto: plays decide)
    private var plays = 1          // 0 = repeat without end, 1..6 = how many plays
    private var baseMin = 0        // Start pill steps from here (+0, +5, +15 ...)
    private var editId = -1
    private var startChosen = false   // new schedule: the Set button shows just "Set" until a Start time is picked
    private var ytId = ""
    private var ytUrl = ""
    private var extApp = ""
    private var extUrl = ""
    private var trackSec = 0
    private var clashOk = false
    private var stopEnabled = false
    private var stopHour = 7
    private var stopMinute = 0
    private var label = "" // optional name: the Name box appears once a source is set
    private var srcKey: String? = null
    private var autoTitle = ""
    private var nameAuto = ""   // the name WhenPlay filled in by itself
    private var clearedNote = false
    private val dayHost: android.view.ViewGroup get() = b.chipDays
    private val pills by lazy { LayoutStore.isPills(this) }
    // source chosen in the avatar row: "phone", "yt", or one of Ext.SPOTIFY / Ext.JIO / Ext.INSTAGRAM
    private var pendingSrc: String? = null
    private val uris = mutableListOf<String>()
    private val names = mutableListOf<String>()
    private val dayOrder = intArrayOf(1, 2, 3, 4, 5, 6, 0)
    private val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    // System file picker: works with any folder, SD card, Downloads, WhatsApp, Drive, etc.
    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list ->
            if (list.isNotEmpty()) {
                ytId = ""; ytUrl = ""; extApp = ""; extUrl = ""
            }
            for (u in list) {
                try {
                    contentResolver.takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                val s = u.toString()
                if (s !in uris) {
                    uris.add(s)
                    names.add(displayName(u))
                }
            }
            renderSources()
            if (list.isNotEmpty()) scheduleTuck()
        }

    private fun displayName(u: android.net.Uri): String {
        return try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: "Track"
        } catch (e: Exception) {
            "Track"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeStore.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.nameInput.setHintTextColor(
            androidx.core.graphics.ColorUtils.setAlphaComponent(ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8B92B5.toInt()), 0x80)
        )
        applyStyleEdit()
        ThemeStore.styleBars(this)

        val cal = Calendar.getInstance()
        hour = cal.get(Calendar.HOUR_OF_DAY)
        minute = cal.get(Calendar.MINUTE)
        stopHour = (hour + 1) % 24
        stopMinute = minute
        var days = 0
        var vol = 70
        var loop = false

        editId = intent.getIntExtra("id", -1)
        startChosen = editId > 0
        val existing = Store.get(this, editId)
        // a new schedule starts at the phone's current media volume
        if (existing == null) vol = sysVolPercent().coerceIn(10, 100)
        if (existing != null) {
            hour = existing.hour
            minute = existing.minute
            second = existing.second
            days = existing.days
            vol = existing.volume
            loop = existing.loop
            stopEnabled = existing.stopEnabled
            if (existing.stopEnabled) {
                stopHour = existing.stopHour
                stopMinute = existing.stopMinute
                stopSecond = existing.stopSecond
            }
            stopMode = existing.stopMode
            plays = existing.plays
            ytId = existing.ytId
            ytUrl = existing.ytUrl
            extApp = existing.extApp
            extUrl = existing.extUrl
            trackSec = existing.trackSec
            label = existing.customName()
            uris.addAll(existing.uris)
            names.addAll(existing.names)
        }

        baseMin = hour * 60 + minute
        for (i in dayOrder) {
            val chip = layoutInflater.inflate(R.layout.chip_day, b.chipDays, false) as android.widget.TextView
            chip.tag = i
            chip.isSelected = ((days shr i) and 1) == 1
            chip.text = dayNames[i]
            chip.setOnClickListener { it.isSelected = !it.isSelected; clashOk = false; renderSetLabel() }
            b.chipDays.addView(chip)
        }

        b.switchLoop.isChecked = loop
        b.switchLoop.setOnCheckedChangeListener { _, c ->
            if (c && extApp == Ext.INSTAGRAM) {
                b.switchLoop.isChecked = false
                showLoopLocked()
            } else {
                clashOk = false
                renderDur()
            }
            renderLoopValue()
        }

        b.cellRepeat.setOnClickListener {
            if (gateOn()) return@setOnClickListener
            val open = b.daysCard.visibility != View.VISIBLE
            b.daysCard.visibility = if (open) View.VISIBLE else View.GONE
            if (open) tuckAnimated(true, 460) else nameDown()
        }

        b.volSeek.progress = vol
        b.volText.text = "$vol%"
        b.volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.volText.text = "$p%"
                // moving the slider changes the phone's media volume too
                if (fromUser) setSysVolPercent(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        setupTimePanel()
        b.root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            b.root.getWindowVisibleDisplayFrame(r)
            val open = b.root.rootView.height - r.bottom > 140 * resources.displayMetrics.density
            if (kbIgnore) {
                if (!open) kbIgnore = false else return@addOnGlobalLayoutListener
            }
            if (open != kbOpen) {
                kbOpen = open
                if (!open) b.nameInput.clearFocus()
                applyDim()
            }
        }
        b.nameIcon.setOnClickListener {
            b.nameInput.requestFocus()
            b.nameInput.selectAll()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(b.nameInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        b.nameInput.setOnEditorActionListener { _, _, _ ->
            // keyboard tick: just close the keyboard (no automatic next step)
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(b.nameInput.windowToken, 0)
            b.nameInput.clearFocus()
            kbIgnore = true
            b.root.postDelayed({ kbIgnore = false }, 700)
            kbOpen = false
            applyDim()
            true
        }
        b.btnSave.maxLines = 1
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(b.btnSave, 10, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        applyDim()
        // START / PLAYS / STOP pills: a tap opens the editor directly
        b.timeCard.setOnClickListener { chooseStart() }
        b.cellLoop.setOnClickListener { cyclePlays() }
        b.stopRow.setOnClickListener { chooseStop() }
        b.btnPaste.setOnClickListener { onPasteChip() }
        b.linkRow.setOnClickListener { onLinkRowTap() }
        setupLinkInputs()
        setupBack()
        b.slideHandle.setOnClickListener { if (anyTucked()) revealAnimated() else tuckAnimated() }

        b.tilePhone.setOnClickListener { pickSrc("phone") }
        b.tileYt.setOnClickListener { pickSrc("yt") }
        b.tileIg.setOnClickListener { pickSrc(Ext.INSTAGRAM) }
        b.tileSp.setOnClickListener { pickSrc(Ext.SPOTIFY) }
        b.tileJio.setOnClickListener { pickSrc(Ext.JIO) }
        b.spotAct.setOnClickListener { onPasteChip() }
        b.clipUse.setOnClickListener { useCopied() }
        b.clipBox.setOnClickListener { useCopied() }
        b.spotIcon.setOnClickListener { pendingSrc?.let { pickSrc(it) } }

        b.btnSave.setOnClickListener {
            if (!startChosen && actualSrc() != null) {
                chooseStart()
                // shown in the line just above Set (same place as "Runs for 13 hr"), so nothing covers the ad area
                b.durText.visibility = View.VISIBLE
                b.durText.setTextColor(0xFFFFB74D.toInt())
                b.durText.text = "Choose a Start time first"
                return@setOnClickListener
            }
            val s = collect() ?: return@setOnClickListener
            if (!stopMode && plays > 1 && trackSec <= 0) {
                say("Length not found yet. Use Stop by > Time to set an end time.", true)
                return@setOnClickListener
            }
            if (uris.isNotEmpty() && trackSec <= 0) trackSec = Scheduler.localSec(this, uris.toList())
            // Stop time longer than the song needs Repeat, otherwise it could never reach that time.
            if (s.extApp != Ext.INSTAGRAM && stopEnabled && trackSec > 0 && !b.switchLoop.isChecked &&
                runSecs() > trackSec + 30
            ) {
                val (st, sap) = Scheduler.fmt12s(stopHour, stopMinute, stopSecond)
                MaterialAlertDialogBuilder(this)
                    .setTitle("Loop mode required")
                    .setMessage(
                        "The " + noun() + " is " + mmss(trackSec) + " long, but the stop time is $st $sap. " +
                            "Turn on Loop mode to play until the stop time, or choose an earlier stop time."
                    )
                    .setPositiveButton("Turn on Loop mode") { _, _ -> b.switchLoop.isChecked = true }
                    .setNegativeButton("Edit stop time", null)
                    .show()
                return@setOnClickListener
            }
            // Without a stop time a reel plays once, so WhenPlay must know its length
            if (s.extApp == Ext.INSTAGRAM && !stopEnabled && trackSec <= 0) {
                askReelLength(true)
                return@setOnClickListener
            }
            if (!checkClash(s)) return@setOnClickListener
            if (s.extApp.isNotBlank() && s.extApp != Ext.INSTAGRAM && !Ext.listenerEnabled(this)) {
                val needed = s.loop || s.stopEnabled
                if (needed || !listenerAsked) {
                    listenerAsked = true
                    val d = MaterialAlertDialogBuilder(this)
                        .setTitle("Allow notification access")
                        .setMessage(
                            if (needed)
                                "Loop mode and Stop time for Spotify and JioSaavn need Notification access, so WhenPlay can control playback. " +
                                    "Open settings, turn on WhenPlay, then come back and save."
                            else
                                "For Spotify and JioSaavn to start on their own, even when the phone is locked, WhenPlay needs Notification access. " +
                                    "Open settings, turn on WhenPlay, then come back and save."
                        )
                        .setPositiveButton("Open settings") { _, _ ->
                            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        .setNegativeButton("Cancel", null)
                    if (!needed) d.setNeutralButton("Save anyway") { _, _ -> b.btnSave.performClick() }
                    d.show()
                    return@setOnClickListener
                }
            }
            persist(s)
            Toast.makeText(
                this,
                "Scheduled • starts " + Scheduler.untilText(Scheduler.nextTrigger(s) - System.currentTimeMillis()),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }

        b.nameInput.setText(label)
        renderTime()
        renderStop()
        renderLoopValue()
        renderSources()
    }

    private val STO = intArrayOf(0, 5, 15, 30, 60)
    private val SPO = intArrayOf(5, 10, 20, 30, 60)

    private fun startMin() = hour * 60 + minute

    private fun setStartMin(v: Int) {
        val x = ((v % 1440) + 1440) % 1440
        hour = x / 60; minute = x % 60; second = 0
    }

    private fun setStopMin(v: Int) {
        val x = ((v % 1440) + 1440) % 1440
        stopHour = x / 60; stopMinute = x % 60; stopSecond = 0
    }

    /** Everything that shows the time / plays / stop, after one of them changed. */
    private fun refreshPills() {
        applyDim()
        clashOk = false
        renderTime()
        renderStop()
        renderLoopValue()
    }

    private fun cycleStart() {
        val cur = ((startMin() - baseMin) % 1440 + 1440) % 1440
        val i = if (second == 0) STO.indexOf(cur) else -1
        setStartMin(baseMin + STO[(i + 1) % STO.size])
        refreshPills()
        onTimeChosen()
    }

    /** PLAYS: each tap = next count, 1 2 3 4 5 6 and back to 1. Stop time is chosen separately on the STOP pill. */
    private fun cyclePlays() {
        if (gateOn() || tpStartOpen) return
        plays = if (plays in 1..5) plays + 1 else 1
        stopMode = false
        if (tpMode == "stop") closeTimePanel(true)
        markCell("plays")
        b.cellLoop.postDelayed({ if (b.timePanel.root.visibility != View.VISIBLE) markCell("") }, 450)
        refreshPills()
    }

    private fun cycleStop() {
        if (!stopMode) {
            stopMode = true
            setStopMin(startMin() + 5)
        } else {
            val d = Scheduler.span(hour, minute, stopHour, stopMinute)
            val i = if (second == 0 && stopSecond == 0) SPO.indexOf(d) else -1
            if (i == SPO.size - 1) stopMode = false else setStopMin(startMin() + SPO[i + 1])
        }
        refreshPills()
        onTimeChosen()
    }

    private var tpMode = ""
    private var tpStartOpen = false
    private var kbIgnore = false   // keyboard is sliding away after the tick key
    private var kbOpen = false   // soft keyboard really on screen (measured, not "name box has focus")
    private var stopPreview = false   // Stop picker open: Plays shows a dash until OK/Auto; closing without choosing restores it

    /** New schedule: Start and Plays stay dim until a file/source is chosen and while the name box is being typed. */
    private fun applyDim() {
        // the boxes stay fully lit; taps are simply ignored until a source is chosen (see gateOn)
        b.timeCard.alpha = 1f
        b.cellLoop.alpha = 1f
        b.cellRepeat.alpha = 1f
        b.stopRow.alpha = 1f
        // names (START / PLAYS / STOP / DAYS) stay lit; only the entries look dim until a source is chosen
        // new schedule: Start / Stop / Days entries stay dim until a source is set AND a Start time was chosen;
        // then Start, the default Stop and the default Days all light up together
        val ea = if (gateOn() || (editId <= 0 && !startChosen)) 0.4f else 1f
        b.timeText.alpha = ea
        b.ampmText.alpha = ea
        b.startSub.alpha = ea
        b.loopValue.alpha = ea
        b.stopTime.alpha = ea
        b.stopSub.alpha = ea
        b.repeatValue.alpha = ea
        b.daysSub.alpha = ea
    }

    /** New schedule with no source yet, or the name is being typed: Start / Plays / Stop / Days ignore taps. */
    private fun gateOn() = editId <= 0 && actualSrc() == null

    /** Accent border on the tapped Start / Plays / Stop box ("" clears all). */
    private fun markCell(which: String) {
        val acc = ThemeStore.attrColor(this, R.attr.txAccent, 0xFF7F5BFF.toInt())
        val w = (2 * resources.displayMetrics.density).toInt()
        listOf("start" to b.timeCard, "plays" to b.cellLoop, "stop" to b.stopRow).forEach { (k, c) ->
            if (c.background == null) return@forEach
            val bg = glass(16)
            if (k == which) bg.setStroke(w, acc)
            c.background = bg
        }
    }

    private fun chooseStart() {
        if (gateOn()) return
        if (stopPreview) { stopPreview = false; renderLoopValue() }
        tpMode = "start"
        tpStop = false
        tpStartOpen = true
        applyDim()
        markCell("start")
        openTimeEditor("Start time", hour, minute, second, null, null) { h, m, sc ->
            hour = h; minute = m; second = sc
            startChosen = true
            refreshPills()
        }
    }

    private fun chooseStop() {
        if (gateOn()) return
        tpMode = "stop"
        tpStartOpen = false
        tpStop = true
        applyDim()
        markCell("stop")
        val (h, m, sc) = if (!stopMode && plays >= 1 && trackSec > 0) stopFromPlays(plays) else Triple(stopHour, stopMinute, stopSecond)
        openTimeEditor("Stop by", h, m, sc, null, null) { hh, mm, ss ->
            stopHour = hh; stopMinute = mm; stopSecond = ss
            stopMode = true
            refreshPills()
        }
    }

    /** Stop time that N full plays of the file lead to. */
    private fun stopFromPlays(n: Int): Triple<Int, Int, Int> {
        val x = (hour * 3600 + minute * 60 + second + n * trackSec) % 86400
        return Triple(x / 3600, (x % 3600) / 60, x % 60)
    }

    private fun fullTime(h: Int, m: Int, sc: Int): String {
        val hh = if (h % 12 == 0) 12 else h % 12
        return String.format("%d:%02d:%02d %s", hh, m, sc, if (h < 12) "AM" else "PM")
    }

    private fun playsLabel(n: Int) = n.toString() + (if (n == 1) " play" else " plays")

    private fun buildPlaysWheel() {
        val dp = resources.displayMetrics.density
        val p = b.timePanel
        val ink = ThemeStore.attrColor(this, R.attr.txText, 0xFFF4F4FA.toInt())
        tpPlaysWheel?.let { p.tpWheelRow.removeView(it) }
        val labels = (1..30).map { n -> "$n\u00D7" }
        val w = WheelView(this, labels, (24 * dp).toInt(), ink, 15f)
        w.onChange = { liveUpdate() }
        p.tpWheelRow.addView(w, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        tpPlaysWheel = w
        val start = if (plays in 1..30) plays else 1
        w.post { w.select(start - 1) }
    }

    // ---- live preview: the Start / Stop boxes follow the wheels while they move ----

    private fun endsSmall(sh: Int, sm: Int, ssec: Int, n: Int): String {
        if (n >= 1 && trackSec > 0) {
            val x = (sh * 3600 + sm * 60 + ssec + n * trackSec) % 86400
            return "ends " + fullTime(x / 3600, (x % 3600) / 60, x % 60)
        }
        return if (n == 1) "plays once" else "length not found"
    }

    private fun playsSmall(run: Int): String {
        if (trackSec <= 0) return "stops at this time"
        val raw = run.toDouble() / trackSec
        val n = Math.max(1, Math.round(raw).toInt())
        return (if (Math.abs(raw - n) < 0.02) "" else "\u2248") + playsLabel(n)
    }

    private fun setStopTexts(big: CharSequence, small: String) {
        b.stopTime.text = big
        b.stopSub.text = small
    }

    private fun liveUpdate() {
        if (!tpLive || b.timePanel.root.visibility != View.VISIBLE) return
        val hh = (tpWheels[0].index() + 1) % 12 + tpWheels[3].index() * 12
        val mm = tpWheels[1].index()
        val ss = tpWheels[2].index()
        if (tpStartOpen) {
            val (t, ap) = Scheduler.fmt12s(hh, mm, ss)
            b.timeText.text = t
            b.ampmText.text = ap
            // Stop follows the new start
            if (stopMode) {
                val run = ((stopHour * 3600 + stopMinute * 60 + stopSecond) - (hh * 3600 + mm * 60 + ss) + 86400) % 86400
                setStopTexts(timeSpan(stopHour, stopMinute, stopSecond), playsSmall(if (run == 0) 86400 else run))
            } else {
                setStopTexts(if (plays == 0) "Repeat" else playsLabel(plays), endsSmall(hh, mm, ss, plays))
            }
            return
        }
        if (!tpStop) return
        if (tpPickPlays) {
            val n = (tpPlaysWheel?.index() ?: 0) + 1
            setStopTexts(playsLabel(n), endsSmall(hour, minute, second, n))
        } else {
            val run = (hh * 3600 + mm * 60 + ss - (hour * 3600 + minute * 60 + second) + 86400) % 86400
            setStopTexts(timeSpan(hh, mm, ss), playsSmall(if (run == 0) 86400 else run))
        }
    }

    /** Panel closed (or reopened): Start and Stop boxes show the real, saved values again. */
    private fun restoreBoxes() {
        val (t, ap) = Scheduler.fmt12s(hour, minute, second)
        b.timeText.text = t
        b.ampmText.text = ap
        renderCellTexts()
    }

    private fun selectTimeWheels(h: Int, m: Int, sc: Int) {
        tpWheels[0].select((if (h % 12 == 0) 12 else h % 12) - 1)
        tpWheels[1].select(m)
        tpWheels[2].select(sc)
        tpWheels[3].select(if (h < 12) 0 else 1)
    }

    private fun applyPickerMode() {
        val p = b.timePanel
        val pl = tpStop && tpPickPlays
        for (w in tpWheels) w.visibility = if (pl) View.GONE else View.VISIBLE
        tpPlaysWheel?.visibility = if (pl) View.VISIBLE else View.GONE
        val dp = resources.displayMetrics.density
        val ink = ThemeStore.attrColor(this, R.attr.txText, 0xFFF4F4FA.toInt())
        val acc = ThemeStore.attrColor(this, R.attr.txAccent, 0xFF7F5BFF.toInt())
        val acc2 = ThemeStore.attrColor(this, R.attr.txAccent2, 0xFFA78BFA.toInt())
        val on = ThemeStore.attrColor(this, R.attr.txOnAccent, 0xFFFFFFFF.toInt())
        fun tab(t: android.widget.TextView, sel: Boolean) {
            t.background = if (sel) BottomLine(ink, (2 * dp).toInt().coerceAtLeast(2)) else null
            t.setTextColor(if (sel) ink else withAlpha(ink, 0x8C))
        }
        tab(p.tpPlaysTab, pl)
        tab(p.tpTimeTab, !pl)
        p.tpPill.background = null
    }

    // ---- inline time editor: opens where the link and name boxes were, nothing below the Save button moves ----

    private val HOURS = (1..12).map { it.toString() }
    private val SIXTY = (0..59).map { String.format("%02d", it) }

    private fun setupTimePanel() {
        val dp = resources.displayMetrics.density
        val p = b.timePanel
        val ink = ThemeStore.attrColor(this, R.attr.txText, 0xFFF4F4FA.toInt())
        val acc = ThemeStore.attrColor(this, R.attr.txAccent, 0xFF7F5BFF.toInt())
        val acc2 = ThemeStore.attrColor(this, R.attr.txAccent2, 0xFFA78BFA.toInt())
        val itemH = (24 * dp).toInt()
        p.tpBox.background = glass(16)
        val band = android.graphics.drawable.GradientDrawable()
        band.cornerRadius = 14 * dp
        band.setColor(withAlpha(ScheduleAdapter.base(this), 0x14))
        p.tpBand.background = band
        tpWheels = listOf(
            WheelView(this, HOURS, itemH, ink, 15f),
            WheelView(this, SIXTY, itemH, ink, 15f),
            WheelView(this, SIXTY, itemH, ink, 15f),
            WheelView(this, listOf("AM", "PM"), itemH, ink, 15f)
        )
        for (w in tpWheels) p.tpWheelRow.addView(w, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        for (w in tpWheels) w.onChange = { liveUpdate() }
        p.tpHair.setBackgroundColor(withAlpha(ink, 0x17))
        val done = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(acc2, acc))
        done.cornerRadius = 8 * dp
        val autoBg = android.graphics.drawable.GradientDrawable()
        autoBg.cornerRadius = 8 * dp
        autoBg.setStroke((1 * dp).toInt().coerceAtLeast(1), withAlpha(ink, 0x40))
        p.tpAuto.background = autoBg
        p.tpDone.background = done
        p.tpPlaysTab.setOnClickListener {
            if (tpPickPlays) return@setOnClickListener
            if (trackSec > 0) {
                val run = (((tpWheels[0].index() + 1) % 12 + tpWheels[3].index() * 12) * 3600 + tpWheels[1].index() * 60 + tpWheels[2].index() -
                    (hour * 3600 + minute * 60 + second) + 86400) % 86400
                val n = Math.round((if (run == 0) 86400 else run).toDouble() / trackSec).toInt().coerceIn(1, 30)
                tpPlaysWheel?.select(n - 1)
            }
            tpPickPlays = true
            applyPickerMode()
            liveUpdate()
        }
        p.tpTimeTab.setOnClickListener {
            if (!tpPickPlays) return@setOnClickListener
            if (trackSec > 0) {
                val n = (tpPlaysWheel?.index() ?: 0) + 1
                val (h, m, sc) = stopFromPlays(n)
                selectTimeWheels(h, m, sc)
            }
            tpPickPlays = false
            applyPickerMode()
            liveUpdate()
        }
        p.tpDone.setOnClickListener {
            if (tpStop && tpPickPlays) {
                val n = (tpPlaysWheel?.index() ?: 0) + 1
                closeTimePanel(true)
                nameDown(true)
                plays = n
                stopMode = false
                refreshPills()
                return@setOnClickListener
            }
            val hh = (tpWheels[0].index() + 1) % 12 + tpWheels[3].index() * 12
            val m = tpWheels[1].index()
            val sc = tpWheels[2].index()
            val cb = tpOnDone
            closeTimePanel(true)
            nameDown(true)
            cb?.invoke(hh, m, sc)
        }
        p.tpAuto.setOnClickListener {
            val cb = tpOnAuto
            closeTimePanel(true)
            nameDown(true)
            cb?.invoke()
        }
    }

    /** Link and name boxes go up first, then the wheels open in their place. */
    private fun openTimeEditor(
        title: String, h: Int, m: Int, sc: Int, autoLabel: String?,
        onAuto: (() -> Unit)?, onDone: (Int, Int, Int) -> Unit
    ) {
        hideKeyboard()
        val show = { showTimePanel(title, h, m, sc, autoLabel, onAuto, onDone) }
        if (b.timePanel.root.visibility == View.VISIBLE) {
            show()
            return
        }
        afterTuck = show
        if (!tuckAnimated(true, 460)) {
            afterTuck = null
            show()
        }
    }

    private fun showTimePanel(
        title: String, h: Int, m: Int, sc: Int, autoLabel: String?,
        onAuto: (() -> Unit)?, onDone: (Int, Int, Int) -> Unit
    ) {
        val dp = resources.displayMetrics.density
        val p = b.timePanel
        p.tpTitle.text = title.uppercase()
        tpLive = false
        tpPickPlays = tpStop && !stopMode
        p.tpPill.visibility = if (tpStop) View.VISIBLE else View.GONE
        if (tpStop) buildPlaysWheel()
        applyPickerMode()
        val dl = p.tpDone.layoutParams as android.widget.LinearLayout.LayoutParams
        if (autoLabel == null) {
            p.tpAuto.visibility = View.GONE
            dl.topMargin = 0
        } else {
            p.tpAuto.visibility = View.VISIBLE
            p.tpAuto.text = autoLabel
            dl.topMargin = (4 * dp).toInt()
        }
        p.tpDone.layoutParams = dl
        tpOnDone = onDone
        tpOnAuto = onAuto
        val wasOpen = p.root.visibility == View.VISIBLE
        if (!wasOpen) expandView(p.root)
        p.root.post {
            tpWheels[0].select((if (h % 12 == 0) 12 else h % 12) - 1)
            tpWheels[1].select(m)
            tpWheels[2].select(sc)
            tpWheels[3].select(if (h < 12) 0 else 1)
            p.root.post { tpLive = true; liveUpdate() }
        }
    }

    private fun expandView(v: View) {
        tpAnim?.cancel()
        val w0 = b.formBox.width - b.formBox.paddingLeft - b.formBox.paddingRight
        val lp = v.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val avail = (if (w0 > 0) w0 else resources.displayMetrics.widthPixels) - lp.leftMargin - lp.rightMargin
        val target = measuredH(v, avail)
        lp.height = 0
        v.alpha = 0f
        v.visibility = View.VISIBLE
        val an = android.animation.ValueAnimator.ofInt(0, target)
        an.duration = 340
        an.interpolator = android.view.animation.DecelerateInterpolator(1.6f)
        an.addUpdateListener {
            lp.height = it.animatedValue as Int
            v.alpha = it.animatedFraction
            v.requestLayout()
        }
        var cancelled = false
        an.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(a: android.animation.Animator) {
                if (!cancelled) {
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    v.alpha = 1f
                    v.requestLayout()
                }
            }
        })
        tpAnim = an
        an.start()
    }

    private fun closeTimePanel(animated: Boolean) {
        tpLive = false
        restoreBoxes()
        if (stopPreview) { stopPreview = false; renderLoopValue() }
        tpStartOpen = false
        applyDim()
        markCell("")
        val v = b.timePanel.root
        tpAnim?.cancel()
        tpAnim = null
        if (v.visibility != View.VISIBLE) return
        val lp = v.layoutParams as android.view.ViewGroup.MarginLayoutParams
        if (!animated || v.height <= 0) {
            v.visibility = View.GONE
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            v.alpha = 1f
            return
        }
        val an = android.animation.ValueAnimator.ofInt(v.height, 0)
        an.duration = 260
        an.interpolator = android.view.animation.DecelerateInterpolator(1.4f)
        an.addUpdateListener {
            lp.height = it.animatedValue as Int
            v.alpha = 1f - it.animatedFraction
            v.requestLayout()
        }
        var cancelled = false
        an.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(a: android.animation.Animator) {
                if (!cancelled) {
                    v.visibility = View.GONE
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    v.alpha = 1f
                }
            }
        })
        tpAnim = an
        an.start()
    }

    /** One place that turns Start / Plays / Stop into what the player needs: stop time, loop. */
    private fun syncModel() {
        val ig = extApp == Ext.INSTAGRAM
        val loopWanted: Boolean
        if (stopMode) {
            stopEnabled = true
            loopWanted = !ig
        } else if (plays == 0) {
            stopEnabled = false
            loopWanted = !ig
        } else if (plays > 1 && trackSec > 0) {
            val x = (hour * 3600 + minute * 60 + second + plays * trackSec) % 86400
            stopHour = x / 3600; stopMinute = (x % 3600) / 60; stopSecond = x % 60
            stopEnabled = true
            loopWanted = !ig
        } else {
            stopEnabled = false
            loopWanted = false
        }
        if (b.switchLoop.isChecked != loopWanted) b.switchLoop.isChecked = loopWanted
    }

    private fun timeSpan(h: Int, m: Int, sc: Int): CharSequence {
        val (t, ap) = Scheduler.fmt12s(h, m, sc)
        val sp = android.text.SpannableString("$t $ap")
        sp.setSpan(android.text.style.RelativeSizeSpan(0.66f), t.length, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    /** A time was chosen: the Name box goes up too (like the link box), so the space under the form stays free for the ad. */
    private fun onTimeChosen() {
        if (actualSrc() == null) return
        b.nameInput.clearFocus()
    }

    /** Seconds between start and stop (overnight aware, equal = 24h). */
    private fun runSecs(): Int {
        val d = ((stopHour * 3600 + stopMinute * 60 + stopSecond) - (hour * 3600 + minute * 60 + second) + 86400) % 86400
        return if (d == 0) 86400 else d
    }

    private fun chooseFiles() {
        picker.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream"))
    }

    private fun askYoutube() {
        askLink("Paste YouTube link", "Video or Shorts", R.drawable.ic_src_youtube, "youtube", { Yt.parseId(it) != null }, "video") { txt ->
            ytId = Yt.parseId(txt) ?: ""
            ytUrl = txt
            uris.clear(); names.clear(); extApp = ""; extUrl = ""
            trackSec = 0
            renderSources()
            fetchSec("youtube", "https://www.youtube.com/watch?v=" + ytId)
        }
    }

    private fun askSpotify() {
        askLink("Paste Spotify link", "Song link", R.drawable.ic_src_spotify, Ext.SPOTIFY, { Ext.valid(Ext.SPOTIFY, it) }, "song") { txt ->
            setExt(Ext.SPOTIFY, txt, 0)
            fetchSec(Ext.SPOTIFY, txt)
        }
    }

    private fun askJio() {
        askLink("Paste JioSaavn link", "Song link", R.drawable.ic_src_jiosaavn, Ext.JIO, { Ext.valid(Ext.JIO, it) }, "song") { txt ->
            setJio(txt)
            fetchSec(Ext.JIO, txt)
        }
    }

    private fun askInstagram() {
        askLink("Paste Instagram Reel link", "Reel or post link", R.drawable.ic_src_instagram, Ext.INSTAGRAM, { Ext.valid(Ext.INSTAGRAM, it) }, "reel") { txt ->
            setExt(Ext.INSTAGRAM, txt, 0)
            fetchSec(Ext.INSTAGRAM, txt)
        }
    }

    private fun clearSource() {
        uris.clear(); names.clear()
        ytId = ""; ytUrl = ""; extApp = ""; extUrl = ""
        trackSec = 0
        renderSources()
        resetNewSchedule()
    }

    /** New schedule only: going back to "Choose a source" puts Start / Stop / Plays / Days back to their defaults. */
    private fun resetNewSchedule() {
        if (editId > 0) return
        if (b.timePanel.root.visibility == View.VISIBLE) closeTimePanel(false)
        val cal = Calendar.getInstance()
        hour = cal.get(Calendar.HOUR_OF_DAY)
        minute = cal.get(Calendar.MINUTE)
        second = 0
        stopHour = (hour + 1) % 24
        stopMinute = minute
        stopSecond = 0
        stopMode = false
        stopEnabled = false
        stopPreview = false
        plays = 1
        startChosen = false
        for (i in 0 until dayHost.childCount) dayHost.getChildAt(i).isSelected = false
        markCell("")
        refreshPills()
    }

    /** "Change" sheet: pick another source. One source per schedule, switching clears the name. */
    private fun showSourceSheet() {
        val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sb = com.tunex.app.databinding.SheetSourcesBinding.inflate(layoutInflater)
        dlg.setContentView(sb.root)
        (sb.root.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        fun tag(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        tag(sb.tagPhone, uris.isNotEmpty())
        tag(sb.tagYt, ytId.isNotBlank())
        tag(sb.tagIg, extApp == Ext.INSTAGRAM)
        tag(sb.tagSp, extApp == Ext.SPOTIFY)
        tag(sb.tagJio, extApp == Ext.JIO)
        sb.subPhone.text = if (uris.isNotEmpty()) "Add more files" else "Choose a file"
        sb.srcPhone.setOnClickListener { dlg.dismiss(); chooseFiles() }
        sb.srcYt.setOnClickListener { dlg.dismiss(); askYoutube() }
        sb.srcIg.setOnClickListener { dlg.dismiss(); askInstagram() }
        sb.srcSp.setOnClickListener { dlg.dismiss(); askSpotify() }
        sb.srcJio.setOnClickListener { dlg.dismiss(); askJio() }
        sb.srcRemove.setOnClickListener { dlg.dismiss(); clearSource() }
        sb.srcCancel.setOnClickListener { dlg.dismiss() }
        dlg.show()
        dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
    }

    private fun replaceNote(): String {
        if (uris.isEmpty() && ytId.isBlank() && extApp.isBlank()) return ""
        val nm = b.nameInput.text.toString().trim()
        return if (nm.isNotEmpty()) "This replaces your current source. Your name \u201C" + nm + "\u201D will be cleared."
        else "This replaces your current source."
    }

    /** Bottom sheet for pasting a link: paste button, live check, title preview, "Use this link". */
    private fun askLink(title: String, sub: String, icon: Int, kind: String, ok: (String) -> Boolean, noun: String, done: (String) -> Unit) {
        val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sb = com.tunex.app.databinding.SheetLinkBinding.inflate(layoutInflater)
        dlg.setContentView(sb.root)
        (sb.root.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        sb.sheetIcon.setImageResource(icon)
        sb.sheetTitle.text = title
        sb.sheetSub.text = sub
        val note = replaceNote()
        sb.sheetNote.text = note
        sb.sheetNote.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        val muted = ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8B92B5.toInt())
        var shownFor = ""
        fun upd() {
            val v = sb.sheetInput.text.toString().trim()
            val txt = if (Ext.isLink(v)) Ext.cleanLink(v) else v
            val good = v.isNotEmpty() && ok(txt)
            sb.sheetUse.isEnabled = good
            sb.sheetPaste.text = if (v.isEmpty()) "Paste" else "Clear"
            when {
                v.isEmpty() -> {
                    sb.sheetHint.setTextColor(muted)
                    sb.sheetHint.text = "Open the $noun in the app, tap Share \u2192 Copy link, then paste it here."
                }
                good -> {
                    sb.sheetHint.setTextColor(0xFF3DDC97.toInt())
                    sb.sheetHint.text = "\u2714 Link detected. This exact $noun will play."
                }
                else -> {
                    sb.sheetHint.setTextColor(0xFFFFB74D.toInt())
                    sb.sheetHint.text = "\u26A0 Only $noun links from this app are supported."
                }
            }
            if (!good) {
                sb.sheetPreview.visibility = View.GONE
                shownFor = ""
            } else if ((kind == "youtube" || kind == Ext.SPOTIFY) && shownFor != txt) {
                shownFor = txt
                sb.sheetPreview.visibility = View.GONE
                val link = if (kind == "youtube") "https://www.youtube.com/watch?v=" + (Yt.parseId(txt) ?: "") else txt
                Thread {
                    val t = Ext.fetchTitle(kind, link)
                    runOnUiThread {
                        if (dlg.isShowing && shownFor == txt && t.isNotEmpty()) {
                            sb.sheetPreview.text = t
                            sb.sheetPreview.visibility = View.VISIBLE
                        }
                    }
                }.start()
            }
        }
        sb.sheetInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = upd()
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        sb.sheetPaste.setOnClickListener {
            if (sb.sheetInput.text.isNotEmpty()) {
                sb.sheetInput.setText("")
                return@setOnClickListener
            }
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (clip.isBlank()) {
                    say("Clipboard is empty")
                } else {
                    sb.sheetInput.setText(if (Ext.isLink(clip)) Ext.cleanLink(clip) else clip.trim())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // A link copied just before opening the sheet is pasted automatically (read once the sheet has focus).
        sb.sheetInput.postDelayed({
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                val cl = Ext.cleanLink(clip)
                if (sb.sheetInput.text.isEmpty() && Ext.isLink(cl) && ok(cl)) sb.sheetInput.setText(cl)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 300)
        sb.sheetUse.setOnClickListener {
            val rawTxt = sb.sheetInput.text.toString().trim()
            val txt = if (Ext.isLink(rawTxt)) Ext.cleanLink(rawTxt) else rawTxt
            if (!ok(txt)) {
                say("This link is not supported")
            } else {
                dlg.dismiss()
                done(txt)
            }
        }
        sb.sheetCancel.setOnClickListener { dlg.dismiss() }
        upd()
        dlg.show()
        dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
    }

    /** Turns the chosen song into its exact Spotify link so it plays reliably; falls back to a name search. */
    private fun pickSpotify(song: Ext.Song) {
        say("Matching on Spotify…")
        Thread {
            val link = Ext.spotifyLink(song.url)
            runOnUiThread {
                if (link != null) {
                    setExt(Ext.SPOTIFY, link, song.sec)
                    say("Matched: " + song.title)
                } else {
                    setExt(Ext.SPOTIFY, song.title + " " + song.artist, song.sec)
                }
            }
        }.start()
    }

    private fun setJio(v: String) = setExt(Ext.JIO, v, 0)

    private fun setExt(app: String, v: String, sec: Int) {
        extApp = app
        extUrl = v
        trackSec = sec
        uris.clear(); names.clear(); ytId = ""; ytUrl = ""
        renderSources()
        renderDur()
    }

    /** Looks up the song length from its link (best effort) and refreshes the warning text. */
    private fun fetchSec(kind: String, link: String) {
        Thread {
            val sec = Ext.fetchSec(kind, link)
            runOnUiThread {
                // screen already closed (never show a dialog on it)
                if (isFinishing || isDestroyed) return@runOnUiThread
                // the user picked another source meanwhile: this answer belongs to the old one
                val same = if (kind == "youtube") ytId.isNotBlank() && link.contains(ytId)
                else extApp == kind && extUrl == link
                if (!same) return@runOnUiThread
                if (sec > 0) {
                    trackSec = sec
                    renderDur()
                } else if (kind == Ext.INSTAGRAM && trackSec <= 0) {
                    askReelLength()
                }
            }
        }.start()
    }

    /** Instagram doesn't always share the reel length: the "Reel length" field in the form takes it. */
    private fun askReelLength(required: Boolean = false) {
        reveal(b.reelRow, true)
        b.reelInput.requestFocus()
        if (required) say("Enter the reel length, or set a stop time instead.", true)
    }

    private fun audio() = getSystemService(AUDIO_SERVICE) as android.media.AudioManager

    private fun sysVolPercent(): Int {
        val am = audio()
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        return if (max <= 0) 70 else am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun setSysVolPercent(p: Int) {
        try {
            val am = audio()
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (max * p / 100).coerceIn(0, max), 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // phone volume buttons -> slider
    private val volReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            if (i?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != android.media.AudioManager.STREAM_MUSIC) return
            val p = sysVolPercent().coerceIn(10, 100)
            if (b.volSeek.progress != p) {
                b.volSeek.progress = p
                b.volText.text = "$p%"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            registerReceiver(volReceiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(volReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onStop()
    }

    private var listenerAsked = false

    private fun renderTime() {
        val (t, ap) = Scheduler.fmt12s(hour, minute, second)
        b.timeText.text = t
        b.ampmText.text = ap
        clashOk = false
        renderDur()
        renderSetLabel()
    }

    /** Save button reads like "Set 11:22 AM \u00B7 Once": the time plus the repeat days. */
    private fun renderSetLabel() {
        val (t, ap) = Scheduler.fmt12s(hour, minute, second)
        var d = 0
        for (i in 0 until dayHost.childCount) {
            val c = dayHost.getChildAt(i)
            if (c.isSelected) d = d or (1 shl (c.tag as Int))
        }
        val rep = Scheduler.repeatText(d)
        b.repeatValue.text = rep
        if (!startChosen) {
            b.btnSave.text = "Set"
            return
        }
        syncModel()
        val sb = StringBuilder("Set ").append(t).append(' ').append(ap)
        if (stopEnabled) {
            val (st, sap) = Scheduler.fmt12s(stopHour, stopMinute, stopSecond)
            sb.append(" \u2013 ").append(st).append(' ').append(sap)
        }
        sb.append(" \u00B7 ").append(rep)
        b.btnSave.text = sb.toString()
    }

    /** PLAYS pill: "3×". Dim while a Stop time decides (then it shows how many plays fit). */
    private fun renderLoopValue() {
        val ink = ThemeStore.attrColor(this, R.attr.txText, 0xFFF4F4FA.toInt())
        val muted = ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8A8AA3.toInt())
        val t: String
        var dim = false
        if (stopMode || stopPreview) {
            dim = true
            t = "\u2014"
        } else if (plays == 0) {
            t = "\u221E"
        } else {
            t = plays.toString() + "\u00D7"
        }
        b.loopValue.text = t
        b.loopValue.setTextColor(if (dim) muted else ink)
    }

    private fun noun() = if (uris.isNotEmpty()) "playlist" else if (extApp == Ext.INSTAGRAM) "reel" else "song"

    private fun mmss(sec: Int) = (sec / 60).toString() + ":" + (sec % 60).toString().padStart(2, '0')

    /** Instagram source: Repeat greyed out and off (the reel loops by itself until the stop time). */
    private fun applyIgLocks() {
        val ig = extApp == Ext.INSTAGRAM
        if (ig && b.switchLoop.isChecked) b.switchLoop.isChecked = false
    }

    /** Duration text for an Instagram reel. */
    private fun renderIgDur() {
        var warnIg = false
        val sb = StringBuilder()
        if (!stopEnabled) {
            sb.append(if (trackSec > 0) "Plays once. Choose a stop time later than the reel to repeat it." else "Set a stop time, or enter the reel length.")
        } else {
            val runSec = runSecs()
            val (st, sap) = Scheduler.fmt12s(stopHour, stopMinute, stopSecond)
            if (trackSec <= 0) {
                sb.append("Reel loops until ").append(st).append(" ").append(sap).append(".")
            } else if (runSec <= trackSec) {
                warnIg = true
                sb.append("Stop time is within the reel\u2019s ").append(mmss(trackSec)).append(" length. Choose a later stop time to repeat it.")
            } else {
                val full = runSec / trackSec
                val rest = runSec % trackSec
                sb.append("Reel loops: ").append(full).append(if (full == 1) " full play" else " full plays")
                if (rest > 15) sb.append(" + ").append(mmss(rest))
                sb.append(", then stops at ").append(st).append(" ").append(sap).append(".")
            }
        }
        b.durText.setTextColor(if (warnIg) 0xFFFFB74D.toInt() else ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8F93B8.toInt()))
        b.durText.text = sb.toString().trim()
    }

    private fun renderDur() {
        syncModel()
        renderLensTag()
        renderCellTexts()
        renderLoopValue()
        syncReel()
        if (extApp == Ext.INSTAGRAM) {
            renderIgDur()
            return
        }
        val loopOn = b.switchLoop.isChecked
        val sb = StringBuilder()
        var warn = false
        if (stopEnabled) {
            val sp = Scheduler.span(hour, minute, stopHour, stopMinute)
            val runSec = runSecs()
            val (st, sap) = Scheduler.fmt12s(stopHour, stopMinute, stopSecond)
            if (trackSec <= 0) {
                sb.append("Runs for ").append(Scheduler.fmtDur(sp))
            } else if (runSec <= trackSec + 30) {
                sb.append("Stops at ").append(st).append(" ").append(sap).append(", before it ends.")
            } else {
                val full = runSec / trackSec
                val rest = runSec % trackSec
                val plan = StringBuilder()
                plan.append(full).append(if (full == 1) " full play" else " full plays")
                if (rest > 15) plan.append(" + ").append(mmss(rest))
                if (loopOn) {
                    sb.append("Loop mode: ").append(plan).append(", then stops at ").append(st).append(" ").append(sap).append(".")
                } else {
                    warn = true
                    sb.append("Plays once (").append(mmss(trackSec)).append(") and stops.\n")
                    sb.append("⚠ Stop time is later than the " + noun() + ". Turn on Loop mode to play ").append(plan)
                        .append(" until ").append(st).append(" ").append(sap).append(".")
                }
            }
        } else if (trackSec > 0 && !loopOn) {
            sb.append("Plays once, then stops.")
        }
        b.durText.setTextColor(if (warn) 0xFFFFB74D.toInt() else ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8F93B8.toInt()))
        b.durText.text = sb.toString().trim()
    }

    /** Two schedules can't play together: ask to create this one after the other ends. */
    /** Two schedules can't play together: ask to create this one after the other ends. */
    /** Premium card explaining why Loop mode is not available for Instagram. */
    private fun showLoopLocked() {
        val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sb = com.tunex.app.databinding.SheetInfoBinding.inflate(layoutInflater)
        dlg.setContentView(sb.root)
        (sb.root.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        sb.infoIcon.setImageResource(R.drawable.ic_src_instagram)
        sb.infoTitle.text = "Loop is unavailable for Instagram"
        sb.infoMsg.text = (if (trackSec > 0) "Reels can\u2019t use Loop mode. This reel is " + mmss(trackSec) + " long. " else "Reels can\u2019t use Loop mode. ") +
            "Choose a stop time later than the reel\u2019s length and it will repeat until then."
        sb.infoAction.text = "Choose stop time"
        sb.infoAction.setOnClickListener { dlg.dismiss(); b.stopRow.performClick() }
        sb.infoClose.setOnClickListener { dlg.dismiss() }
        dlg.show()
        dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
    }

    private fun checkClash(s: Schedule): Boolean {
        if (clashOk) return true
        val cl = Scheduler.conflict(this, s) ?: return true
        val c = cl.other
        val (cs, cap) = Scheduler.fmt12(c.hour, c.minute)
        val q = "\u201C" + c.title() + "\u201D"
        val candLater = (hour * 60 + minute) > (c.hour * 60 + c.minute)
        val ce = Scheduler.endMinutes(this, c)
        val (es, eap) = Scheduler.fmt12(ce / 60, ce % 60)

        // The schedule that starts later takes over; the earlier one stops and does not resume.
        // "After" is only possible when both have a known end and this one starts later.
        val canAfter = candLater && !cl.otherOpen && !cl.candOpen
        val nm = c.title()
        val dm = Scheduler.durationMin(this, c)
        val (ms, mp) = Scheduler.fmt12s(hour, minute, second)
        val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sb = com.tunex.app.databinding.SheetClashBinding.inflate(layoutInflater)
        dlg.setContentView(sb.root)
        (sb.root.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        sb.clashTitle.text = nm
        sb.clashSub.text = if (candLater) "Your $ms $mp start falls inside this schedule."
            else "This schedule runs into it at $cs $cap."

        // Name never pushes the "Already scheduled" pill out of the row.
        sb.clashPill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val dpx = resources.displayMetrics.density
        sb.clashTitle.maxWidth = (resources.displayMetrics.widthPixels - 36 * dpx - 6 * dpx - sb.clashPill.measuredWidth).toInt()

        // "9:30 \u2013 9:42 AM \u00B7 12 min": AM small and muted, duration in accent.
        val mutedC = ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8B92B5.toInt())
        val accC = ThemeStore.attrColor(this, R.attr.txAccent, 0xFF7C5CFF.toInt())
        val sbd = android.text.SpannableStringBuilder()
        fun small(t: String) {
            val st = sbd.length
            sbd.append(t)
            sbd.setSpan(android.text.style.RelativeSizeSpan(0.72f), st, sbd.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sbd.setSpan(android.text.style.ForegroundColorSpan(mutedC), st, sbd.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        when {
            cl.otherOpen -> sbd.append("Loop from $cs").also { small(" $cap") }
            dm != null -> {
                sbd.append(cs)
                if (cap != eap) small(" $cap")
                sbd.append(" \u2013 $es")
                small(" $eap")
            }
            else -> sbd.append("Starts $cs").also { small(" $cap") }
        }
        if (dm != null && !cl.otherOpen) {
            small("  \u00B7  ")
            val st = sbd.length
            sbd.append(Scheduler.fmtDur(dm))
            sbd.setSpan(android.text.style.ForegroundColorSpan(accC), st, sbd.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sbd.setSpan(android.text.style.RelativeSizeSpan(0.82f), st, sbd.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.clashRange.text = sbd

        fun clock(h: Int, m: Int): CharSequence {
            val (t, ap) = Scheduler.fmt12(h, m)
            val sp = android.text.SpannableString("$t $ap")
            sp.setSpan(android.text.style.RelativeSizeSpan(0.62f), t.length, sp.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sp
        }

        sb.tileAfter.visibility = if (canAfter) View.VISIBLE else View.GONE
        sb.tileAfterCap.text = "After"
        sb.tileAfterTime.text = clock(ce / 60, ce % 60)
        val afterDesc = "$nm plays fully, then this one starts."

        sb.tileReplaceCap.text = if (candLater) "Replace" else "Save anyway"
        sb.tileReplaceTime.text = clock(hour, minute)
        val replaceDesc = if (candLater) "$nm stops at $ms $mp and won\u2019t resume."
            else "$nm takes over at $cs $cap. This one won\u2019t resume."
        if (!canAfter) (sb.tileReplace.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart = 0

        val dp = resources.displayMetrics.density
        val base = ScheduleAdapter.base(this)
        val card = ThemeStore.attrColor(this, R.attr.txCard, 0xFF222222.toInt())
        val ink = ThemeStore.attrColor(this, R.attr.txText, 0xFF111111.toInt())
        val muted = ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8B92B5.toInt())
        val danger = ThemeStore.attrColor(this, R.attr.txDanger, 0xFFFF5C7A.toInt())
        val tintA = ThemeStore.attrColor(this, R.attr.txAccent, 0xFF7C5CFF.toInt())
        fun tileBg(tint: Int, selected: Boolean): android.graphics.drawable.GradientDrawable {
            // soft tinted tile; only the chosen one gets the accent edge
            val gd = ScheduleAdapter.squircle(withAlpha(base, 0x14), 18 * dp)
            gd.setStroke(if (selected) (1.5f * dp).toInt().coerceAtLeast(2) else 0, tintA)
            return gd
        }

        var afterSel = canAfter
        fun applySel() {
            sb.tileAfter.background = tileBg(tintA, afterSel)
            sb.tileReplace.background = tileBg(tintA, !afterSel)
            sb.tileAfter.isSelected = afterSel
            sb.tileReplace.isSelected = !afterSel
            sb.clashDesc.text = if (afterSel) afterDesc else replaceDesc
            sb.clashDesc.setTextColor(muted)
            val (t, ap) = if (afterSel) Scheduler.fmt12(ce / 60, ce % 60) else Scheduler.fmt12s(hour, minute, second)
            sb.clashSave.text = "Save at $t $ap"
        }
        applySel()
        sb.tileAfter.setOnClickListener { afterSel = true; applySel() }
        sb.tileReplace.setOnClickListener { afterSel = false; applySel() }

        sb.clashSave.setOnClickListener {
            dlg.dismiss()
            if (afterSel) {
                val keep = if (stopEnabled) Scheduler.span(hour, minute, stopHour, stopMinute) else 0
                hour = ce / 60
                minute = ce % 60
                second = 0
                if (stopEnabled) {
                    val e2 = (ce + keep) % 1440
                    stopHour = e2 / 60
                    stopMinute = e2 % 60
                }
                renderTime()
                renderStop()
                clashOk = false
            } else {
                clashOk = true
            }
            b.btnSave.performClick()
        }
        sb.clashSave.backgroundTintList = android.content.res.ColorStateList.valueOf(tintA)
        sb.clashSave.setTextColor(ThemeStore.attrColor(this, R.attr.txOnAccent, 0xFFFFFFFF.toInt()))
        sb.clashCancel.setOnClickListener { dlg.dismiss() }
        dlg.show()
        dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
        return false
    }

    private fun renderStop() {
        b.stopTime.setTextColor(ThemeStore.attrColor(this, R.attr.txText, 0xFFF4F4FA.toInt()))
        renderDur()
    }

    /** START / STOP / DAYS: same three rows in every box (label, value, small line). STOP shows what was picked first, big. */
    private fun renderCellTexts() {
        // start
        val now = java.util.Calendar.getInstance()
        val nowSec = now.get(java.util.Calendar.HOUR_OF_DAY) * 3600 + now.get(java.util.Calendar.MINUTE) * 60 + now.get(java.util.Calendar.SECOND)
        b.startSub.text = if (hour * 3600 + minute * 60 + second > nowSec) "Today" else "Next time"
        // stop
        val big: CharSequence
        val small: String
        if (stopMode) {
            big = timeSpan(stopHour, stopMinute, stopSecond)
            small = if (trackSec > 0) {
                val raw = runSecs().toDouble() / trackSec
                val n = Math.max(1, Math.round(raw).toInt())
                (if (Math.abs(raw - n) < 0.02) "" else "\u2248") + playsLabel(n)
            } else "stops at this time"
        } else {
            big = if (plays == 0) "Repeat" else playsLabel(plays)
            small = if (plays >= 1 && trackSec > 0) {
                val (h, m, sc) = stopFromPlays(plays)
                "ends " + fullTime(h, m, sc)
            } else if (plays == 1) "plays once" else "length not found"
        }
        b.stopTime.text = big
        b.stopSub.text = small
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(b.stopSub, 7, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        val rep = b.repeatValue.text.toString()
        b.daysSub.text = if (rep.startsWith("Once")) "No repeat" else "Repeats"
    }

    /** Name box: shown only while a source is set; a different source clears the old name. */
    private fun syncName() {
        val key = when {
            ytId.isNotBlank() -> "yt:$ytId"
            extApp.isNotBlank() -> "ext:$extApp:$extUrl"
            uris.isNotEmpty() -> "files"
            else -> ""
        }
        if (srcKey != key) {
            val had = b.nameInput.text.toString().trim()
            if (!srcKey.isNullOrEmpty()) b.nameInput.setText("")
            clearedNote = !srcKey.isNullOrEmpty() && had.isNotEmpty() && had != nameAuto
            nameAuto = ""
            srcKey = key
            autoTitle = ""
            val kind = when {
                key.startsWith("yt:") -> "youtube"
                extApp == Ext.SPOTIFY -> Ext.SPOTIFY
                else -> ""
            }
            if (kind.isNotEmpty()) {
                val link = if (kind == "youtube") "https://www.youtube.com/watch?v=" + ytId else extUrl
                Thread {
                    val t = Ext.fetchTitle(kind, link)
                    runOnUiThread {
                        if (isFinishing || isDestroyed || srcKey != key) return@runOnUiThread
                        autoTitle = t
                        syncName()
                        renderSummary()
                    }
                }.start()
            }
        }
        val igNote = extApp == Ext.INSTAGRAM
        b.nameNote.visibility = if (clearedNote || igNote) View.VISIBLE else View.GONE
        b.nameNote.text = if (clearedNote) "Name cleared for the new source" else "Instagram doesn\u2019t share reel titles, so add your own name if you like."
        b.nameNote.setTextColor(
            if (clearedNote) ThemeStore.attrColor(this, R.attr.txAccent, 0xFF7C5CFF.toInt())
            else ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8B92B5.toInt())
        )
        b.nameInput.hint = "Name"
        applyAutoName()
    }

    /** Short, easy-to-recognise name from the source. Never overwrites a name the person typed. */

    /** Short messages show right above the Set button, never at the bottom where the ad lives. */
    private val msgHide = Runnable { b.inlineMsg.visibility = View.GONE }

    private fun say(msg: String, long: Boolean = false) {
        val v = b.inlineMsg
        if (v.background == null) {
            val d = resources.displayMetrics.density
            v.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 40f * d
                setColor(ThemeStore.attrColor(this@EditActivity, R.attr.txText, 0xFF222222.toInt()))
            }
            v.setTextColor(ThemeStore.attrColor(this@EditActivity, R.attr.txBg, 0xFFFFFFFF.toInt()))
            v.textSize = 13f
            v.setPadding((16 * d).toInt(), (9 * d).toInt(), (16 * d).toInt(), (9 * d).toInt())
        }
        v.text = msg
        v.visibility = View.VISIBLE
        v.removeCallbacks(msgHide)
        v.postDelayed(msgHide, if (long) 3600L else 2400L)
    }

    private fun applyAutoName() {
        val want = when {
            extApp == Ext.INSTAGRAM -> "Instagram Reel"
            ytId.isNotBlank() || extApp == Ext.SPOTIFY -> shortName(autoTitle)
            extApp == Ext.JIO -> shortName(slugTitle(extUrl))
            uris.isNotEmpty() -> {
                val first = shortName(names.firstOrNull() ?: "")
                if (names.size > 1 && first.isNotEmpty()) first + " +" + (names.size - 1) else first
            }
            else -> ""
        }
        val cur = b.nameInput.text.toString().trim()
        if (cur.isEmpty() || cur == nameAuto) {
            if (want != cur) b.nameInput.setText(want)
            nameAuto = want
        }
    }

    private fun shortName(raw: String): String {
        var t = raw.trim().replace(Regex("\\.[A-Za-z0-9]{2,4}$"), "").replace('_', ' ')
        t = t.replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        t = t.split(Regex("\\s[-\u2013\u2014|]\\s|[:|]"))[0].replace(Regex("\\s+"), " ").trim()
        if (t.length > 28) {
            val cut = t.take(28)
            val sp = cut.lastIndexOf(' ')
            t = if (sp >= 8) cut.substring(0, sp) else cut
        }
        return t
    }

    private fun slugTitle(url: String): String {
        val m = Regex("/song/([^/?#]+)").find(url) ?: return ""
        return m.groupValues[1].replace('-', ' ').split(' ').joinToString(" ") { w ->
            w.replaceFirstChar { c -> c.uppercase() }
        }
    }

    private fun actualSrc(): String? = when {
        uris.isNotEmpty() -> "phone"
        ytId.isNotBlank() -> "yt"
        extApp.isNotBlank() -> extApp
        else -> null
    }

    private var settingLink = false
    private var settingReel = false
    private var linkFor: String? = null
    private val applyLinkRun = Runnable { applyTyped() }
    private var linkTucked = false
    private var nameTucked = false   // the Name box is hidden under the window too (after a time was chosen)
    private var tuckInit = false
    private var blkLink = true       // which boxes the running slide moves
    private var blkName = false
    private var nameH = 0
    private var afterTuck: (() -> Unit)? = null   // runs when the boxes finished going up (opens the time panel)
    private var tpWheels: List<WheelView> = emptyList()
    private var tpLive = false          // wheels are settled: moving them updates the Start / Stop boxes at once
    private var tpStop = false          // the open picker is the Stop picker (Plays | Time switch)
    private var tpPickPlays = false     // Stop picker shows the Plays wheel (otherwise the clock wheels)
    private var tpPlaysWheel: WheelView? = null
    private var tpOnDone: ((Int, Int, Int) -> Unit)? = null
    private var tpOnAuto: (() -> Unit)? = null
    private var tpAnim: android.animation.ValueAnimator? = null

    // ---- link box slides under the (fixed) source window and back; only translations move, so it stays smooth ----
    private var linkBusy = false
    private var linkP = 0f          // 0 = box open, 1 = tucked under the window
    private var linkH = 0
    private var lastUriCount = 0
    private var linkAnim: android.animation.ValueAnimator? = null
    private var handleBob: android.animation.ObjectAnimator? = null
    private val tuckRun = Runnable { if (!linkTucked || linkBusy) tuckAnimated() }

    private fun hideKeyboard() {
        b.linkInput.clearFocus()
        b.nameInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(b.linkInput.windowToken, 0)
    }

    private fun measuredH(v: View, widthPx: Int): Int {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return v.measuredHeight
    }

    private fun anyTucked() = linkTucked || nameTucked

    private fun nameTopMargin(): Int = (b.nameCard.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin

    private fun applyP(v: Float) {
        linkP = v
        // The link box also has to clear the form's top padding, and gets a little extra on top:
        // the scroll view clips everything above its edge, so nothing of the box can peek out under the window.
        val ex = 16 * resources.displayMetrics.density
        if (blkLink) b.linkGroup.translationY = -(linkH + b.formBox.paddingTop + ex) * v
        b.restBox.translationY = -(linkH + nameH).toFloat() * v
        if (blkName) b.nameCard.translationY = -(b.formBox.paddingTop + ex) * v
        b.slideHandle.alpha = ((v - 0.85f) / 0.15f).coerceIn(0f, 1f)
    }

    /** Grab handle in the window: shown (and gently bobbing) only while the box is tucked. */
    private fun syncHandle() {
        if (anyTucked() && !linkBusy) {
            b.slideHandle.alpha = 1f
            if (handleBob == null) {
                val d = resources.displayMetrics.density
                val o = android.animation.ObjectAnimator.ofFloat(b.slideHandle, "translationY", 0f, 3f * d, 0f)
                o.duration = 1700
                o.repeatCount = android.animation.ValueAnimator.INFINITE
                o.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                handleBob = o
                o.start()
            }
        } else {
            handleBob?.cancel()
            handleBob = null
            b.slideHandle.translationY = 0f
            if (!linkBusy) b.slideHandle.alpha = 0f
        }
    }

    private fun resetSlide() {
        afterTuck = null
        closeTimePanel(false)
        linkAnim?.cancel()
        linkAnim = null
        b.linkInput.removeCallbacks(tuckRun)
        linkBusy = false
        linkTucked = false
        nameTucked = false
        blkLink = true
        blkName = false
        linkH = 0
        nameH = 0
        linkP = 0f
        b.linkGroup.translationY = 0f
        b.restBox.translationY = 0f
        b.nameCard.translationY = 0f
        syncHandle()
    }

    /** Starts a slide up from the open state. link / name say which boxes go; a box that is not on screen is skipped. */
    private fun prepareFromOpen(link: Boolean, name: Boolean): Boolean {
        val g = b.linkGroup
        val c = b.nameCard
        val useLink = link && g.visibility == View.VISIBLE && g.height > 0
        val useName = name && c.visibility == View.VISIBLE && c.height > 0
        if (!useLink && !useName) return false
        blkLink = useLink
        blkName = useName
        linkH = if (useLink) g.height else 0
        nameH = if (useName) c.height + nameTopMargin() else 0
        linkBusy = true
        linkP = 0f
        syncHandle()
        return true
    }

    /** Starts a slide down: brings back whatever is tucked (link box, and the Name box if it went up too). */
    private fun prepareFromTucked(closePanel: Boolean = true) {
        afterTuck = null
        if (closePanel) closeTimePanel(false)
        val w0 = b.formBox.width - b.formBox.paddingLeft - b.formBox.paddingRight
        val w = if (w0 > 0) w0 else resources.displayMetrics.widthPixels
        blkLink = false          // the old link box is gone for good
        blkName = nameTucked
        linkH = 0
        nameH = 0
        if (blkLink) {
            val g = b.linkGroup
            linkH = measuredH(g, w)
            g.alpha = 1f
            g.visibility = View.VISIBLE
        }
        if (blkName) {
            val c = b.nameCard
            nameH = measuredH(c, w) + nameTopMargin()
            c.alpha = 1f
            c.visibility = View.VISIBLE
        }
        linkBusy = true
        linkTucked = false
        nameTucked = false
        applyP(1f)
        syncHandle()
    }

    private fun finishP(to: Float) {
        linkAnim = null
        b.linkGroup.translationY = 0f
        b.restBox.translationY = 0f
        b.nameCard.translationY = 0f
        if (to >= 1f) {
            if (blkLink) {
                linkTucked = true
                b.linkGroup.visibility = View.GONE
            }
            if (blkName) {
                nameTucked = true
                b.nameCard.visibility = View.GONE
            }
            linkP = 1f
        } else {
            if (blkLink) linkTucked = false
            if (blkName) nameTucked = false
            linkP = 0f
        }
        linkBusy = false
        syncHandle()
        val cb = afterTuck
        afterTuck = null
        if (to >= 1f) cb?.invoke()
    }

    private fun animateP(to: Float, dur: Long, ip: android.animation.TimeInterpolator) {
        linkAnim?.cancel()
        val from = linkP
        val an = android.animation.ValueAnimator.ofFloat(0f, 1f)
        an.duration = dur
        an.interpolator = ip
        an.addUpdateListener { applyP(from + (to - from) * (it.animatedValue as Float)) }
        var cancelled = false
        an.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) finishP(to) }
        })
        linkAnim = an
        an.start()
    }

    /** Box slowly slides up and goes under the window. withName: the Name box goes with it. */
    private fun tuckAnimated(withName: Boolean = false, ms: Long = 720): Boolean {
        val kind = pendingSrc ?: return false
        if (actualSrc() != kind) return false
        if (!linkBusy) {
            val ok = if (linkTucked) {
                if (!withName || nameTucked) return false
                prepareFromOpen(false, true)
            } else {
                prepareFromOpen(true, withName)
            }
            if (!ok) return false
        }
        hideKeyboard()
        animateP(1f, ms, android.view.animation.DecelerateInterpolator(1.8f))
        return true
    }

    /** Name box comes back down after a picker (time or days) closed. fromPanel: the time panel is still sliding shut. */
    private fun nameDown(fromPanel: Boolean = false) {
        if (!fromPanel && b.timePanel.root.visibility == View.VISIBLE) return
        if (b.daysCard.visibility == View.VISIBLE) return
        afterTuck = null
        if (linkBusy) {
            animateP(0f, 560, android.view.animation.DecelerateInterpolator(1.6f))
            return
        }
        if (!anyTucked()) return
        prepareFromTucked(false)
        animateP(0f, 560, android.view.animation.DecelerateInterpolator(1.6f))
    }

    /** Box comes back down with a small spring. Works any number of times. */
    private fun revealAnimated() {
        if (!linkBusy) {
            if (!anyTucked()) return
            prepareFromTucked()
        }
        animateP(0f, 780, android.view.animation.OvershootInterpolator(0.9f))
    }

    private fun scheduleTuck() {
        b.linkInput.removeCallbacks(tuckRun)
        b.linkInput.postDelayed(tuckRun, 700)
    }

    private var tState = 0
    private var tDownX = 0f
    private var tDownY = 0f
    private var tStartY = 0f
    private var tStartP = 0f
    private var vt: android.view.VelocityTracker? = null

    private fun logicalTucked() = linkP > 0.5f || (anyTucked() && !linkBusy)

    private fun rubber(v: Float) = if (v < 0f) v * 0.25f else if (v > 1f) 1f + (v - 1f) * 0.2f else v

    /**
     * Drag with the thumb: swipe down below the window brings the box back, swipe up on the box tucks it again.
     * The box follows the finger, stretches a little at the ends and settles with a spring.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val kind = pendingSrc
        val has = kind != null && actualSrc() == kind
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                tState = 0
                if (has) {
                    val hl = IntArray(2)
                    b.headerBox.getLocationOnScreen(hl)
                    val below = ev.rawY > hl[1] + b.headerBox.height
                    var inGroup = false
                    if (b.linkGroup.visibility == View.VISIBLE) {
                        val gl = IntArray(2)
                        b.linkGroup.getLocationOnScreen(gl)
                        inGroup = ev.rawY >= gl[1] && ev.rawY <= gl[1] + b.linkGroup.height &&
                            ev.rawX >= gl[0] && ev.rawX <= gl[0] + b.linkGroup.width
                    }
                    if (!inGroup && b.nameCard.visibility == View.VISIBLE) {
                        val nl = IntArray(2)
                        b.nameCard.getLocationOnScreen(nl)
                        inGroup = ev.rawY >= nl[1] && ev.rawY <= nl[1] + b.nameCard.height &&
                            ev.rawX >= nl[0] && ev.rawX <= nl[0] + b.nameCard.width
                    }
                    var inPanel = false
                    if (b.timePanel.root.visibility == View.VISIBLE) {
                        val pl = IntArray(2)
                        b.timePanel.root.getLocationOnScreen(pl)
                        inPanel = ev.rawY >= pl[1] && ev.rawY <= pl[1] + b.timePanel.root.height
                    }
                    if (!inPanel && ((logicalTucked() && below) || (!logicalTucked() && inGroup))) {
                        tState = 1
                        tDownX = ev.rawX
                        tDownY = ev.rawY
                        vt?.recycle()
                        vt = android.view.VelocityTracker.obtain()
                        vt?.addMovement(ev)
                    }
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (tState != 0) vt?.addMovement(ev)
                if (tState == 1) {
                    val dy = ev.rawY - tDownY
                    val dx = ev.rawX - tDownX
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        val wantReveal = logicalTucked() && dy > 0 && b.scroll.scrollY == 0
                        val wantTuck = !logicalTucked() && dy < 0
                        if (wantReveal || wantTuck) {
                            val c = android.view.MotionEvent.obtain(ev)
                            c.action = android.view.MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(c)
                            c.recycle()
                            linkAnim?.cancel()
                            linkAnim = null
                            if (!linkBusy) {
                                if (anyTucked()) prepareFromTucked() else if (!prepareFromOpen(true, true)) {
                                    tState = 0
                                    return true
                                }
                            }
                            tState = 2
                            tStartY = ev.rawY
                            tStartP = linkP
                            syncHandle()
                            return true
                        } else {
                            tState = 0
                        }
                    }
                } else if (tState == 2) {
                    applyP(rubber(tStartP - (ev.rawY - tStartY) / Math.max(1, linkH + nameH).toFloat()))
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (tState == 2) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    val target = when {
                        vy > 900f -> 0f
                        vy < -900f -> 1f
                        // an easy, forgiving drag: ~30% of the way is enough to finish it
                        tStartP < 0.5f -> if (linkP > 0.3f) 1f else 0f
                        else -> if (linkP > 0.7f) 1f else 0f
                    }
                    if (target >= 1f) animateP(1f, 500, android.view.animation.DecelerateInterpolator(1.6f))
                    else animateP(0f, 700, android.view.animation.OvershootInterpolator(1.0f))
                    vt?.recycle()
                    vt = null
                    tState = 0
                    return true
                }
                vt?.recycle()
                vt = null
                tState = 0
            }
        }
        if (tState == 2) return true
        return super.dispatchTouchEvent(ev)
    }

    /** Back: if the link box is tucked, first press brings it back down; then un-selects the source (same screen), the next one returns to the list. */
    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.timePanel.root.visibility == View.VISIBLE) {
                    closeTimePanel(true)
                    nameDown(true)
                } else if (pendingSrc != null && actualSrc() == pendingSrc && logicalTucked()) {
                    // link box is hidden under the window: bring it back down first
                    revealAnimated()
                } else if (pendingSrc != null) {
                    b.linkInput.removeCallbacks(applyLinkRun)
                    resetSlide()
                    if (actualSrc() != null) clearSource()
                    pendingSrc = null
                    linkFor = null
                    renderSummary()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun kindNoun(kind: String?) = when (kind) {
        "yt" -> "video"
        Ext.INSTAGRAM -> "reel"
        else -> "song"
    }

    private fun curLink(kind: String): String = when (kind) {
        "yt" -> ytUrl
        Ext.SPOTIFY, Ext.JIO, Ext.INSTAGRAM -> extUrl
        else -> ""
    }

    private fun linkOk(kind: String, txt: String) = if (kind == "yt") Yt.parseId(txt) != null else Ext.valid(kind, txt)

    private fun cleaned(v: String) = if (Ext.isLink(v)) Ext.cleanLink(v) else v.trim()

    private fun setLinkText(t: String) {
        settingLink = true
        b.linkInput.setText(t)
        b.linkInput.setSelection(b.linkInput.text.length)
        settingLink = false
    }

    private fun readClip(): String = try {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Listeners for the inline link field and the Instagram reel length field. */
    private fun setupLinkInputs() {
        b.linkInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {
                if (settingLink) return
                b.linkInput.removeCallbacks(applyLinkRun)
                renderHint()
                b.linkInput.postDelayed(applyLinkRun, 350)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        b.reelInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {
                if (settingReel) return
                val t = s.toString().trim()
                val sec = if (t.contains(":")) {
                    val p = t.split(":")
                    (p[0].toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
                } else (t.toIntOrNull() ?: 0)
                trackSec = sec.coerceAtLeast(0)
                renderDur()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        b.linkInput.setOnClickListener { onLinkRowTap() }
        b.linkHint.setOnClickListener {
            if (pendingSrc == null) {
                pickSrc("phone")
                chooseFiles()
            }
        }
    }

    /** Avatar tap: only selects the source. Tapping it again closes it, unless it already holds this schedule's music. */
    private fun pickSrc(key: String) {
        if (pendingSrc == key) {
            if (actualSrc() != key) {
                pendingSrc = null
                resetSlide()
                renderSummary()
            }
            return
        }
        pendingSrc = key
        resetSlide()
        linkFor = null
        b.linkInput.removeCallbacks(applyLinkRun)
        if (actualSrc() != null && actualSrc() != key) clearSource()
        renderSummary()
    }

    /** Runs when the text in the link field settles: a supported link becomes the source. */
    private fun applyTyped() {
        var kind = pendingSrc
        if (kind == null) {
            val v0 = b.linkInput.text.toString().trim()
            if (v0.isEmpty()) {
                renderHint()
                return
            }
            val t0 = cleaned(v0)
            val found = listOf("yt", Ext.INSTAGRAM, Ext.SPOTIFY, Ext.JIO).firstOrNull { linkOk(it, t0) }
            if (found == null) {
                renderHint()
                return
            }
            pickSrc(found)
            setLinkText(t0)
            kind = found
        }
        if (kind == "phone") return
        val v = b.linkInput.text.toString().trim()
        if (v.isEmpty()) {
            if (actualSrc() == kind) {
                resetSlide()
                clearSource()
            }
            renderHint()
            return
        }
        val txt = cleaned(v)
        if (linkOk(kind, txt)) {
            if (!(actualSrc() == kind && curLink(kind) == txt)) {
                applyLink(kind, txt)
                if (b.linkInput.text.toString() != txt) setLinkText(txt)
                scheduleTuck()
            }
        }
        renderHint()
    }

    /** Chip at the end of the link row: Paste / Clear, or Browse / Add for the phone. */
    private fun onPasteChip() {
        val kind = pendingSrc
        if (kind == null) {
            if (b.linkInput.text.isNotEmpty()) {
                setLinkText("")
                renderHint()
                return
            }
            val clip0 = readClip()
            if (clip0.isBlank()) {
                say("Clipboard is empty. Copy a link first.")
                return
            }
            setLinkText(cleaned(clip0))
            applyTyped()
            return
        }
        if (kind == "phone") {
            chooseFiles()
            return
        }
        if (b.linkInput.text.isNotEmpty()) {
            b.linkInput.removeCallbacks(applyLinkRun)
            resetSlide()
            if (actualSrc() == kind) clearSource()
            setLinkText("")
            renderSummary()
            return
        }
        val clip = readClip()
        if (clip.isBlank()) {
            say("Clipboard is empty. Copy a link first.")
            return
        }
        setLinkText(cleaned(clip))
        applyTyped()
    }

    private fun onLinkRowTap() {
        if (pendingSrc == "phone") {
            chooseFiles()
        } else if (b.linkInput.text.isEmpty()) {
            onPasteChip()
        }
    }

    /** Same steps the old link sheet ran after "Use this link". */
    private fun applyLink(kind: String, txt: String) {
        when (kind) {
            "yt" -> {
                ytId = Yt.parseId(txt) ?: ""
                ytUrl = txt
                uris.clear(); names.clear(); extApp = ""; extUrl = ""
                trackSec = 0
                renderSources()
                fetchSec("youtube", "https://www.youtube.com/watch?v=" + ytId)
            }
            Ext.SPOTIFY -> { setExt(Ext.SPOTIFY, txt, 0); fetchSec(Ext.SPOTIFY, txt) }
            Ext.JIO -> { setJio(txt); fetchSec(Ext.JIO, txt) }
            Ext.INSTAGRAM -> { setExt(Ext.INSTAGRAM, txt, 0); fetchSec(Ext.INSTAGRAM, txt) }
        }
    }

    /** One slim length row for every source: label on the left, mm:ss on the right (Instagram: tap the number to edit). */
    private fun syncReel() {
        val ig = extApp == Ext.INSTAGRAM
        val label = when {
            uris.size > 1 -> "Total length"
            uris.size == 1 -> {
                val n = names.firstOrNull()?.lowercase() ?: ""
                if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".3gp") || n.endsWith(".mov")) "Video length" else "Music length"
            }
            ytId.isNotBlank() -> "Video length"
            ig -> "Reel length"
            else -> "Song length"
        }
        b.lenLabel.text = label.uppercase()
        applyDim()
        b.reelInput.isFocusable = ig
        b.reelInput.isFocusableInTouchMode = ig
        b.reelInput.isCursorVisible = ig
        b.reelInput.isLongClickable = ig
        reveal(b.reelRow, actualSrc() != null && (trackSec > 0 || ig))
        if (b.reelInput.hasFocus()) return
        val want = if (trackSec > 0) mmss(trackSec) else ""
        if (b.reelInput.text.toString() != want) {
            settingReel = true
            b.reelInput.setText(want)
            settingReel = false
        }
    }

    private fun glass(radiusDp: Int, strong: Boolean = false) =
        ScheduleAdapter.glass(this, radiusDp * resources.displayMetrics.density, strong)

    private fun withAlpha(c: Int, alpha: Int) = androidx.core.graphics.ColorUtils.setAlphaComponent(c, alpha)

    /** Only the source window takes the app's colour; everything below stays neutral. */
    private fun srcColor(key: String?): Int = when (key) {
        "yt" -> 0xFFFF3131.toInt()
        Ext.INSTAGRAM -> 0xFFE1306C.toInt()
        Ext.SPOTIFY -> 0xFF1ED760.toInt()
        Ext.JIO -> 0xFF16C2B0.toInt()
        "phone" -> 0xFF6F7CFF.toInt()
        else -> 0xFFE8E8EC.toInt()   // nothing picked yet: the design sheet's light grey hero
    }

    /** Light hero = dark ink. That is "nothing picked yet" (grey hero) and Phone (cream silver). */
    private val lightHdr: Boolean get() = pendingSrc == null
    private val hdrInk = 0xFF22262A.toInt()

    private var headerColor = 0
    private var headerAnim: android.animation.ValueAnimator? = null

    private fun paintHeader(c: Int) {
        headerColor = c
        val d = resources.displayMetrics.density
        if (LayoutStore.isSheet(this)) {
            // Sheet (UI 8): the hero is full-bleed and floods with the source colour,
            // fading into the page so the rounded sheet reads as a separate surface.
            // The hero keeps its colour all the way down, so the sheet's rounded top
            // edge always has something to sit on. Empty state = the grey of the design.
            val deep = if (pendingSrc == null) 0xFFC9CAD3.toInt()
                       else androidx.core.graphics.ColorUtils.blendARGB(c, 0xFF000000.toInt(), 0.40f)
            val g = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(c, deep)
            )
            g.cornerRadius = 0f
            b.headerBox.background = g
            b.headerBox.elevation = 0f
        } else if (pills) {
            // floating tinted glass panel
            val g = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(withAlpha(c, 0xE6), withAlpha(c, 0x99))
            )
            g.cornerRadius = 28 * d
            g.setStroke(Math.max(1, d.toInt()), withAlpha(0xFFFFFFFF.toInt(), 0x40))
            b.headerBox.background = g
        } else {
            // full-width panel with a curved bottom edge
            val r = 30 * d
            val hd = android.graphics.drawable.GradientDrawable()
            hd.setColor(c)
            hd.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            hd.setStroke(Math.max(1, d.toInt()), if (lightHdr) withAlpha(0xFF000000.toInt(), 0x14) else withAlpha(0xFFFFFFFF.toInt(), 0x10))
            b.headerBox.background = hd
        }
    }

    private fun setHeader(target: Int) {
        if (target == headerColor) return
        headerAnim?.cancel()
        val an = android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), headerColor, target)
        an.duration = 300
        an.addUpdateListener { paintHeader(it.animatedValue as Int) }
        headerAnim = an
        an.start()
    }

    private fun reveal(v: View, show: Boolean) {
        if (!show) {
            v.visibility = View.GONE
            return
        }
        if (v.visibility != View.VISIBLE) {
            v.alpha = 0f
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(220).start()
        }
    }

    /** Curve (header panel + 2x2 tiles) or Pills (floating panel + one grouped settings card). Same behaviour in both. */
    private fun applyStyleEdit() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val white = 0xFFFFFFFF.toInt()
        val ink = ThemeStore.attrColor(this, R.attr.txText, white)
        val inkOn = ThemeStore.attrColor(this, R.attr.txBg, 0xFF000000.toInt())
        val muted = ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8B92B5.toInt())
        val base = ScheduleAdapter.base(this)

        // source window
        paintHeader(srcColor(null))
        b.headerBox.elevation = 8 * dp
        b.slideHandle.background = ScheduleAdapter.squircle(withAlpha(white, 0x73), 2 * dp)
        if (LayoutStore.isSheet(this)) {
            val lp = b.headerBox.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.setMargins(0, 0, 0, 0)
            b.headerBox.layoutParams = lp
            b.headerBox.minimumHeight = px(224)
        } else if (pills) {
            val lp = b.headerBox.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.setMargins(px(12), px(12), px(12), px(4))
            b.headerBox.layoutParams = lp
        }
        b.screenTitle.setTextColor(withAlpha(if (lightHdr) hdrInk else white, 0xCC))

        // fields: neutral glass
        val fr = if (pills) 26 else 16
        b.linkRow.background = glass(fr)
        b.nameRow.background = glass(fr)
        b.lenLine.setBackgroundColor(withAlpha(ink, 0x24))
        b.btnPaste.background = glass(if (pills) 15 else 9, true)
        b.nameIcon.imageTintList = android.content.res.ColorStateList.valueOf(muted)
        b.daysCard.background = glass(if (pills) 22 else 16)
        b.volRow.background = glass(fr)
        
        // Start / Repeat / Loop / Stop at
        val cells = listOf(b.timeCard, b.cellRepeat, b.cellLoop, b.stopRow)
        val caps = listOf("Starts", "Repeats", "Plays", "Stops")
        val orient = if (pills) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL
        b.cellRowA.orientation = orient
        b.cellRowB.orientation = orient
        val groups = listOf(b.cellsBox, b.cellRowA, b.cellRowB)
        if (pills) {
            for (g in groups) {
                val hair = android.graphics.drawable.GradientDrawable()
                hair.setColor(withAlpha(base, 0x1C))
                hair.setSize(1, 1)
                g.dividerDrawable = android.graphics.drawable.InsetDrawable(hair, px(52), 0, 0, 0)
                g.showDividers = android.widget.LinearLayout.SHOW_DIVIDER_MIDDLE
            }
            b.cellsBox.background = glass(20)
        } else {
            for (g in groups) g.showDividers = android.widget.LinearLayout.SHOW_DIVIDER_NONE
            b.cellsBox.background = null
        }
        cells.forEachIndexed { i, c ->
            val icon = c.getChildAt(0)
            val label = c.getChildAt(1) as android.widget.TextView
            if (pills) {
                c.background = null
                c.orientation = android.widget.LinearLayout.HORIZONTAL
                c.gravity = android.view.Gravity.CENTER_VERTICAL
                c.setPadding(px(12), px(10), px(16), px(10))
                c.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                icon.visibility = View.VISIBLE
                icon.background = ScheduleAdapter.squircle(withAlpha(base, 0x16), px(11).toFloat())
                val llp = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                llp.marginStart = px(12)
                label.layoutParams = llp
                label.text = caps[i]
                label.textSize = 14f
                label.letterSpacing = 0f
                label.setTextColor(ink)
            } else {
                c.background = glass(16)
                c.orientation = android.widget.LinearLayout.VERTICAL
                c.gravity = android.view.Gravity.START
                c.setPadding(px(12), px(11), px(8), px(11))
                c.minimumHeight = px(80)
                val lp = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(px(4), px(4), px(4), px(4))
                c.layoutParams = lp
                icon.visibility = View.GONE
                val llp = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                llp.bottomMargin = px(6)
                label.layoutParams = llp
                label.text = caps[i].uppercase()
                label.textSize = 10f
                label.letterSpacing = 0.16f
                label.setTextColor(muted)
            }
        }
        listOf(b.repeatValue, b.loopValue, b.stopTime, b.timeText, b.ampmText).forEach {
            it.textSize = if (pills) 14f else 17f
            it.setTextColor(if (pills) withAlpha(ink, 0xCC) else ink)
        }
        // three pills across: slightly smaller values
        b.timeText.textSize = 16f
        b.ampmText.textSize = 10f
        b.stopTime.textSize = 16f
        b.loopValue.textSize = 17f
        // START / PLAYS / STOP / DAYS stay the boldest thing in a cell; the values are lighter and smaller
        if (!pills) {
            cells.forEach { c ->
                (c.getChildAt(1) as android.widget.TextView).apply {
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    letterSpacing = 0.12f
                    setTextColor(ink)
                }
            }
        }
        listOf(b.repeatValue, b.loopValue, b.stopTime, b.timeText).forEach {
            it.textSize = 14f
            it.typeface = android.graphics.Typeface.DEFAULT
        }
        b.ampmText.textSize = 10f
        b.ampmText.typeface = android.graphics.Typeface.DEFAULT
        listOf(b.startSub, b.stopSub, b.daysSub).forEach {
            it.textSize = 10f
            it.setTextColor(muted)
            it.visibility = if (pills) View.GONE else View.VISIBLE
        }
        b.cellLoop.visibility = View.GONE
        b.cellRowB.visibility = View.GONE

        // Save button: the one accent pill on the sheet (UI 8)
        val accSave = ThemeStore.attrColor(this, R.attr.txAccent, 0xFF5B6CFF.toInt())
        val onSave = ThemeStore.attrColor(this, R.attr.txOnAccent, 0xFFFFFFFF.toInt())
        b.btnSave.backgroundTintList = android.content.res.ColorStateList.valueOf(accSave)
        b.btnSave.setTextColor(onSave)
    }

    /** Sheet (UI 8) source tile: one soft rounded square. Picked = accent edge, never a red ring. */
    private fun avatarBg(fillC: Int, picked: Boolean): android.graphics.drawable.Drawable {
        val dp = resources.displayMetrics.density
        val white = if (lightHdr) hdrInk else 0xFFFFFFFF.toInt()
        val tile = ScheduleAdapter.squircle(fillC, 17 * dp)
        tile.setStroke(Math.max(1, dp.toInt()), withAlpha(white, if (picked) 0x8C else 0x26))
        val body = android.graphics.drawable.InsetDrawable(tile, (3 * dp).toInt())
        return body
    }

    /** All five sources are always visible at full strength; the picked one gets a ring. */
    private fun styleAvatars() {
        val white = 0xFFFFFFFF.toInt()
        val items = listOf(
            Triple(b.avPhone, "phone", 0xFF6F7CFF.toInt()),
            Triple(b.avYt, "yt", 0xFFFF3131.toInt()),
            Triple(b.avIg, Ext.INSTAGRAM, 0xFFE1306C.toInt()),
            Triple(b.avSp, Ext.SPOTIFY, 0xFF1ED760.toInt()),
            Triple(b.avJio, Ext.JIO, 0xFF16C2B0.toInt())
        )
        val tiles = listOf(b.tilePhone, b.tileYt, b.tileIg, b.tileSp, b.tileJio)
        items.forEachIndexed { i, (v, key, fillC) ->
            val picked = pendingSrc == key
            v.background = avatarBg(fillC, picked)
            (tiles[i].getChildAt(1) as android.widget.TextView).apply {
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(
                    if (lightHdr) withAlpha(hdrInk, if (picked) 0xFF else 0xB3) else withAlpha(white, if (picked) 0xFF else 0xD9)
                )
            }
        }
    }

    private fun renderHeader() {
        val nm = when (pendingSrc) {
            "phone" -> "PHONE"
            "yt" -> "YOUTUBE"
            Ext.INSTAGRAM -> "INSTAGRAM"
            Ext.SPOTIFY -> "SPOTIFY"
            Ext.JIO -> "JIOSAAVN"
            else -> ""
        }
        b.screenTitle.text = if (LayoutStore.isSheet(this)) {
            // Sheet (UI 8) hero: a plain question while empty, the source name once one is picked.
            if (nm.isEmpty()) "Where should the sound come from?" else nm
        } else if (pills) {
            if (editId > 0) "EDIT SCHEDULE" else "NEW SCHEDULE"
        } else if (nm.isEmpty()) {
            "CHOOSE A SOURCE"
        } else {
            nm + (if (actualSrc() != pendingSrc) " \u00B7 READY" else if (editId > 0) " \u00B7 EDITING" else " \u00B7 NOW SCHEDULING")
        }
        setHeader(srcColor(pendingSrc))
        val dpr = resources.displayMetrics.density
        val ink = if (lightHdr) hdrInk else 0xFFFFFFFF.toInt()
        b.screenTitle.setTextColor(withAlpha(ink, if (lightHdr) 0xB3 else 0xCC))
        if (LayoutStore.isSheet(this)) {
            // hero headline: big and tight while empty, a small label once the source is set
            if (nm.isEmpty()) {
                b.screenTitle.textSize = 25f
                b.screenTitle.letterSpacing = 0f
                b.screenTitle.setTextColor(ink)
                b.screenTitle.maxLines = 2
            } else {
                b.screenTitle.textSize = 11f
                b.screenTitle.letterSpacing = 0.14f
                b.screenTitle.maxLines = 1
            }
        }
        b.slideHandle.background = ScheduleAdapter.squircle(withAlpha(ink, if (lightHdr) 0x59 else 0x73), 2 * dpr)
        renderHeroMark(ink)
        renderSpot()
    }

    /** Sheet (UI 8): the source's own icon, huge and faint, top-right of the hero. */
    private fun renderHeroMark(ink: Int) {
        b.heroMark.setImageResource(if (pendingSrc == null) R.drawable.ic_src_phone else srcIconRes(pendingSrc))
        b.heroMark.imageTintList = android.content.res.ColorStateList.valueOf(withAlpha(ink, 0x2E))
        b.heroMark.alpha = 1f
    }

    // ---- Spotlight Lens (source window): five tiles until a source is picked, then one spotlight row + switch dock ----
    private fun srcFill(key: String?): Int = when (key) {
        "phone" -> 0xFF6F7CFF.toInt()
        "yt" -> 0xFFFF3131.toInt()
        Ext.INSTAGRAM -> 0xFFE1306C.toInt()
        Ext.SPOTIFY -> 0xFF1ED760.toInt()
        else -> 0xFF16C2B0.toInt()
    }

    private fun srcIconRes(key: String?): Int = when (key) {
        "phone" -> R.drawable.ic_src_phone
        "yt" -> R.drawable.ic_src_youtube
        Ext.INSTAGRAM -> R.drawable.ic_src_instagram
        Ext.SPOTIFY -> R.drawable.ic_src_spotify
        else -> R.drawable.ic_src_jiosaavn
    }

    private fun srcLabel(key: String?): String = when (key) {
        "phone" -> "Phone"
        "yt" -> "YouTube"
        Ext.INSTAGRAM -> "Instagram"
        Ext.SPOTIFY -> "Spotify"
        else -> "JioSaavn"
    }

    /** Link found on the clipboard (source key, cleaned link). Read only when the window gets focus, so Android does not nag. */
    private var clipHint: Pair<String, String>? = null

    private fun scanClip() {
        val raw = readClip().trim()
        val t = if (raw.isEmpty()) "" else cleaned(raw)
        val kind = if (t.isEmpty()) null else listOf("yt", Ext.INSTAGRAM, Ext.SPOTIFY, Ext.JIO).firstOrNull { linkOk(it, t) }
        clipHint = if (kind == null) null else Pair(kind, t)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingSrc == null) {
            scanClip()
            renderSpot()
        }
    }

    /** "Use" on the copied-link banner: picks the source and takes the link in one tap. */
    private fun useCopied() {
        val ch = clipHint ?: return
        clipHint = null
        setLinkText(ch.second)
        applyTyped()
    }

    private fun renderLensTag() {
        val ink = if (lightHdr) hdrInk else 0xFFFFFFFF.toInt()
        b.lensTag.setTextColor(withAlpha(ink, if (lightHdr) 0xB3 else 0xCC))
        b.lensTag.text = when {
            pendingSrc == null -> ""
            actualSrc() == pendingSrc && trackSec > 0 -> mmss(trackSec)
            else -> ""
        }
    }

    private fun renderSpot() {
        renderLensTag()
        val key = pendingSrc
        if (key == null) {
            b.spotBox.visibility = View.GONE
            b.avatarRow.visibility = View.VISIBLE
            val ch = clipHint
            if (ch == null) {
                b.clipBox.visibility = View.GONE
            } else {
                val dp0 = resources.displayMetrics.density
                val ink0 = if (lightHdr) hdrInk else 0xFFFFFFFF.toInt()
                b.clipLbl.text = "COPIED " + srcLabel(ch.first).uppercase() + " LINK READY"
                b.clipLbl.setTextColor(withAlpha(ink0, 0xB3))
                b.clipLine.setBackgroundColor(withAlpha(ink0, 0x24))
                b.clipUse.setTextColor(srcColor(null))
                b.clipUse.background = ScheduleAdapter.squircle(ink0, 10 * dp0)
                if (b.clipBox.visibility != View.VISIBLE) {
                    b.clipBox.alpha = 0f
                    b.clipBox.visibility = View.VISIBLE
                    b.clipBox.animate().alpha(1f).setDuration(220).start()
                }
            }
            return
        }
        b.clipBox.visibility = View.GONE
        val dp = resources.displayMetrics.density
        val ink = if (lightHdr) hdrInk else 0xFFFFFFFF.toInt()
        val muted = withAlpha(ink, 0xB3)
        val committed = actualSrc() == key
        val stroke = withAlpha(ink, if (lightHdr) 0x26 else 0x4D)

        b.avatarRow.visibility = View.GONE
        if (b.spotBox.visibility != View.VISIBLE) {
            b.spotBox.alpha = 0f
            b.spotBox.translationY = -4 * dp
            b.spotBox.visibility = View.VISIBLE
            b.spotBox.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }

        // big icon (tap = back to the five tiles, only while nothing is added yet)
        b.spotIcon.setImageResource(srcIconRes(key))
        b.spotIcon.background = ScheduleAdapter.squircle(srcFill(key), 14 * dp).also {
            it.setStroke(Math.max(1, dp.toInt()), stroke)
        }

        val n = uris.size
        val title: String
        val sub: String
        if (committed) {
            title = when {
                key == "phone" -> if (n == 1) shortName(names.firstOrNull() ?: "1 file") else "$n files"
                autoTitle.isNotBlank() -> shortName(autoTitle)
                key == Ext.INSTAGRAM -> "Instagram Reel"
                else -> "Link added"
            }
            sub = if (key == "phone") (if (n == 1) "1 file from Phone" else "$n files from Phone") else curLink(key)
        } else {
            title = if (key == "phone") "Select local files" else "Paste " + srcLabel(key) + " link"
            sub = if (key == "phone") "Audio or video from storage"
            else if (!b.linkInput.text.isNullOrEmpty()) "Only " + kindNoun(key) + " links from this app work. Tap Clear."
            else "Copy link in the app, then tap Paste"
        }
        b.spotTitle.text = title
        b.spotTitle.setTextColor(ink)
        b.spotSub.text = sub
        b.spotSub.setTextColor(muted)

        // action button: same action as the Paste / Browse button of the link box
        b.spotAct.text = if (key == "phone") (if (n == 0) "Browse" else "+ Add")
        else (if (b.linkInput.text.isNullOrEmpty()) "Paste" else "Clear")
        b.spotAct.setTextColor(srcColor(key))
        b.spotAct.background = ScheduleAdapter.squircle(ink, 11 * dp)

        b.spotLine.setBackgroundColor(withAlpha(ink, 0x24))
        b.spotDockLbl.setTextColor(muted)

        // switch dock: the other four sources as small squircles
        b.miniRow.removeAllViews()
        val all = listOf("phone", "yt", Ext.INSTAGRAM, Ext.SPOTIFY, Ext.JIO)
        all.filter { it != key }.forEachIndexed { i, k ->
            val iv = android.widget.ImageView(this)
            val lp = android.widget.LinearLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt())
            if (i > 0) lp.marginStart = (7 * dp).toInt()
            iv.layoutParams = lp
            val pad = (6 * dp).toInt()
            iv.setPadding(pad, pad, pad, pad)
            iv.setImageResource(srcIconRes(k))
            iv.contentDescription = srcLabel(k)
            iv.background = ScheduleAdapter.squircle(srcFill(k), 10 * dp).also {
                it.setStroke(Math.max(1, dp.toInt()), stroke)
            }
            iv.setOnClickListener { pickSrc(k) }
            b.miniRow.addView(iv)
        }
    }

    /** Status line under the link field: what to do, what was detected, or what is wrong. */
    private fun renderHint() {
        val kind = pendingSrc
        b.linkHint.visibility = View.VISIBLE
        val muted = ThemeStore.attrColor(this, R.attr.txMuted, 0xFF8B92B5.toInt())
        val green = 0xFF3DDC97.toInt()
        val amber = 0xFFFFB74D.toInt()
        if (kind == null) {
            // nothing chosen yet: one universal box, link of any supported app or a file from the phone
            val v0 = b.linkInput.text.toString().trim()
            b.btnPaste.text = if (v0.isEmpty()) "Paste" else "Clear"
            if (v0.isEmpty()) {
                b.linkHint.setTextColor(muted)
                b.linkHint.text = "Paste a link, or tap Browse to pick a file from your phone."
            } else {
                b.linkHint.setTextColor(amber)
                b.linkHint.text = "This link will not play here. Use a YouTube, Instagram, Spotify or JioSaavn link."
            }
            return
        }
        if (kind == "phone") {
            b.btnPaste.text = if (uris.isEmpty()) "Browse" else "Add"
            b.linkHint.setTextColor(if (uris.isEmpty()) muted else green)
            b.linkHint.text = if (uris.isEmpty()) "Pick songs, or a whole playlist, from your phone."
            else "\u2714 " + uris.size + (if (uris.size == 1) " file" else " files") + " selected"
            return
        }
        val v = b.linkInput.text.toString().trim()
        b.btnPaste.text = if (v.isEmpty()) "Paste" else "Clear"
        val txt = cleaned(v)
        val good = v.isNotEmpty() && linkOk(kind, txt)
        val cur = actualSrc() == kind && curLink(kind) == txt
        when {
            v.isEmpty() -> {
                b.linkHint.setTextColor(muted)
                b.linkHint.text = "In " + kindNoun(kind) + ", tap Share \u2192 Copy link, then paste it here."
            }
            good -> {
                b.linkHint.setTextColor(green)
                b.linkHint.text = "\u2714 " + (if (cur && autoTitle.isNotBlank()) autoTitle else "Link detected")
            }
            else -> {
                b.linkHint.setTextColor(amber)
                b.linkHint.text = "That is not a " + kindNoun(kind) + " link. Copy it again from the " + kindNoun(kind) + " app."
            }
        }
    }

    /** The link row: Name appears with it, the field is typed/pasted in place, phone just lists the files. */
    private fun renderLink() {
        val kind = pendingSrc
        val picked = kind != null
        val tuckedNow = linkTucked && picked && actualSrc() == kind
        if (actualSrc() == null) nameTucked = false
        if (!tuckedNow && !linkBusy) {
            linkTucked = false
            linkP = 0f
        }
        if (!linkBusy) {
            b.linkGroup.visibility = View.GONE
            syncHandle()
        }
        if (!linkBusy) reveal(b.nameCard, actualSrc() != null && !nameTucked)
        val phone = kind == "phone"
        b.linkInput.isFocusable = false
        b.linkInput.isFocusableInTouchMode = false
        b.linkInput.isCursorVisible = false
        b.linkInput.showSoftInputOnFocus = false
        b.linkInput.hint = if (phone) "Choose files" else if (kind == null) "Choose file or paste link" else "Paste link"
        if (kind == null) {
            linkFor = null
            setLinkText("")
        } else if (phone) {
            linkFor = kind
            setLinkText(if (uris.isEmpty()) "" else if (names.size == 1) names[0] else names.size.toString() + " files")
        } else if (linkFor != kind) {
            linkFor = kind
            setLinkText(if (actualSrc() == kind) curLink(kind) else "")
        }
        renderHint()
    }

    private fun renderSummary() {
        val has = actualSrc() != null
        styleAvatars()
        renderLink()
        renderHeader()
        syncReel()
        if (pendingSrc == "phone" && uris.size > lastUriCount && uris.isNotEmpty()) scheduleTuck()
        lastUriCount = uris.size
        b.btnSave.isEnabled = has
        b.btnSave.alpha = if (has) 1f else 0.5f
    }

    private fun renderSources() {
        if (!tuckInit) {
            tuckInit = true
            linkTucked = false
            linkP = 0f
        }
        actualSrc()?.let { pendingSrc = it }
        syncName()
        renderSummary()
        if (uris.isNotEmpty()) {
            refreshLocalSec()
        } else if (ytId.isBlank() && extApp.isBlank()) {
            trackSec = 0
            renderDur()
        }
        applyIgLocks()
    }

    /** Reads the real length of the chosen phone files so Stop time / Repeat can be checked. */
    private fun refreshLocalSec() {
        val snap = uris.toList()
        Thread {
            val sec = Scheduler.localSec(this, snap)
            runOnUiThread {
                if (uris.isNotEmpty() && uris.toList() == snap) {
                    trackSec = sec
                    renderDur()
                }
            }
        }.start()
    }

    private fun collect(): Schedule? {
        syncModel()
        if (uris.isEmpty() && ytId.isBlank() && extApp.isBlank()) {
            say("Pick a song from your phone, or paste a link first")
            return null
        }
        var days = 0
        for (i in 0 until dayHost.childCount) {
            val c = dayHost.getChildAt(i)
            if (c.isSelected) days = days or (1 shl (c.tag as Int))
        }
        label = b.nameInput.text.toString().trim()
        val id = if (editId > 0) editId else Store.nextId(this)
        return Schedule(
            id = id,
            hour = hour,
            minute = minute,
            days = days,
            label = label,
            uris = uris.toList(),
            names = names.toList(),
            enabled = true,
            volume = b.volSeek.progress.coerceAtLeast(10),
            onceAt = 0L,
            ytId = ytId,
            ytUrl = ytUrl,
            loop = b.switchLoop.isChecked,
            stopEnabled = stopEnabled,
            stopHour = stopHour,
            stopMinute = stopMinute,
            extApp = extApp,
            extUrl = extUrl,
            trackSec = trackSec,
            second = second,
            stopSecond = stopSecond,
            plays = plays,
            stopMode = stopMode
        )
    }

    private fun persist(s: Schedule) {
        editId = s.id
        Store.upsert(this, s)
        Scheduler.cancel(this, s.id)
        Scheduler.arm(this, s)
    }
}


/** Plain 2dp line along the bottom edge: the active Plays / Time tab. */
private class BottomLine(c: Int, private val th: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint().also { it.color = c }
    override fun draw(canvas: android.graphics.Canvas) {
        val r = bounds
        canvas.drawRect(r.left.toFloat(), (r.bottom - th).toFloat(), r.right.toFloat(), r.bottom.toFloat(), paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

