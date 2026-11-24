package com.tunex.app

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * One scroll wheel (like the time wheels of the HTML design): three rows visible, the middle row is the chosen one,
 * it snaps row by row and the rows above and below fade out.
 */
class WheelView(
    ctx: Context,
    private val labels: List<String>,
    private val itemH: Int,
    private val ink: Int,
    private val textSp: Float = 27f
) : RecyclerView(ctx) {

    private val lm = LinearLayoutManager(ctx)
    private val snap = LinearSnapHelper()
    private var cur = 0
    private var lastIdx = -1

    /** Called whenever the row in the middle changes (also while the wheel is still moving). */
    var onChange: (() -> Unit)? = null

    init {
        layoutManager = lm
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        itemAnimator = null
        setPadding(0, itemH, 0, itemH)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = labels.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context)
                tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH)
                tv.gravity = Gravity.CENTER
                tv.includeFontPadding = false
                tv.textSize = textSp
                tv.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                tv.setTextColor(ink)
                // tap a row to bring it to the middle
                tv.setOnClickListener { v ->
                    val dy = (v.top + v.bottom) / 2 - height / 2
                    if (dy != 0) smoothScrollBy(0, dy)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as TextView).text = labels[position]
            }
        }
        snap.attachToRecyclerView(this)
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                fade()
                val i = index()
                if (i != lastIdx) {
                    lastIdx = i
                    onChange?.invoke()
                }
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) cur = index()
            }
        })
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fade() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val pad = ((h - itemH) / 2).coerceAtLeast(0)
        if (paddingTop != pad) {
            setPadding(0, pad, 0, pad)
            scrollToPosition(cur)
        }
    }

    /** Row sitting in the middle right now (also right while the wheel is still moving). */
    fun index(): Int {
        val v = snap.findSnapView(lm)
        if (v != null) {
            val p = getChildAdapterPosition(v)
            if (p != RecyclerView.NO_POSITION) return p.coerceIn(0, labels.size - 1)
        }
        return cur
    }

    fun select(i: Int) {
        cur = i.coerceIn(0, labels.size - 1)
        lm.scrollToPositionWithOffset(cur, 0)
        post { fade() }
    }

    private fun fade() {
        val c = height / 2f
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val d = Math.abs((v.top + v.bottom) / 2f - c) / itemH
            v.alpha = (1f - 0.72f * d).coerceIn(0.26f, 1f)
        }
    }

    // keep the bottom sheet from stealing the drag
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(e)
    }
}

