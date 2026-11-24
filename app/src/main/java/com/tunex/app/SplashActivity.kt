package com.tunex.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Launch screen: pure black, "WhenPlay" with a golden dot that starts at the W's top-left corner,
 * lights every letter once as it passes, and ends as a play triangle after the "y". Tap to skip.
 */
class SplashActivity : Activity() {

    private val gold = 0xFFFFB020.toInt()
    private var opened = false
    private var anim: ValueAnimator? = null

    private class TriView(c: Context, col: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        override fun onDraw(canvas: Canvas) {
            val p = Path()
            p.moveTo(0f, 0f)
            p.lineTo(width.toFloat(), height / 2f)
            p.lineTo(0f, height.toFloat())
            p.close()
            canvas.drawPath(p, paint)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val word = LinearLayout(this)
        word.orientation = LinearLayout.HORIZONTAL
        val letters = "WhenPlay".map { ch ->
            TextView(this).apply {
                text = ch.toString()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                includeFontPadding = false
                alpha = 0.16f
            }
        }
        letters.forEach { word.addView(it) }
        word.addView(View(this), LinearLayout.LayoutParams((30 * dp).toInt(), 1))
        root.addView(word, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val haloSize = (30 * dp).toInt()
        val halo = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x66FFB020, 0x00FFB020)).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = haloSize / 2f
                shape = GradientDrawable.OVAL
            }
            alpha = 0f
        }
        val dotSize = (10 * dp).toInt()
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gold) }
            scaleX = 0f; scaleY = 0f
        }
        val triW = (18 * dp).toInt()
        val triH = (20 * dp).toInt()
        val tri = TriView(this, gold).apply { scaleX = 0f; scaleY = 0f }
        root.addView(halo, FrameLayout.LayoutParams(haloSize, haloSize))
        root.addView(dot, FrameLayout.LayoutParams(dotSize, dotSize))
        root.addView(tri, FrameLayout.LayoutParams(triW, triH))

        setContentView(root)
        root.setOnClickListener { openMain() }
        root.post { start(word, letters, halo, dot, tri, dp, haloSize, dotSize, triW, triH) }
    }

    private fun start(
        word: View, letters: List<TextView>, halo: View, dot: View, tri: View,
        dp: Float, haloSize: Int, dotSize: Int, triW: Int, triH: Int
    ) {
        val T = 2100L
        val h = letters[0].height.toFloat()
        val top = word.top + letters[0].top
        val t = top + h * 0.2f
        val b = top + h * 0.78f
        val mid = (t + b) / 2f
        val x0 = (word.left + letters[0].left).toFloat()
        val ex = (word.left + letters.last().right) + 22 * dp
        val span = ex - x0
        val arr = letters.map { l -> 0.12f + ((word.left + (l.left + l.right) / 2f) - x0) / span * 0.68f }
        val overshoot = OvershootInterpolator(2.2f)

        fun clamp(v: Float) = v.coerceIn(0f, 1f)
        fun blend(a: Int, c: Int, k: Float): Int {
            val r = (Color.red(a) + (Color.red(c) - Color.red(a)) * k).toInt()
            val g = (Color.green(a) + (Color.green(c) - Color.green(a)) * k).toInt()
            val bl = (Color.blue(a) + (Color.blue(c) - Color.blue(a)) * k).toInt()
            return Color.rgb(r, g, bl)
        }

        tri.translationX = ex - triW / 2f
        tri.translationY = mid - triH / 2f

        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = T
        an.interpolator = LinearInterpolator()
        an.addUpdateListener { va ->
            val p = va.animatedValue as Float
            val x = x0 + span * clamp((p - 0.12f) / 0.68f)
            val y = if (p < 0.12f) t + (mid - t) * (p / 0.12f) else mid
            val grow = clamp(p / 0.04f)
            val shrink = 1f - clamp((p - 0.8f) / 0.1f)
            val sc = grow * shrink
            dot.translationX = x - dotSize / 2f
            dot.translationY = y - dotSize / 2f
            dot.scaleX = sc; dot.scaleY = sc
            halo.translationX = x - haloSize / 2f
            halo.translationY = y - haloSize / 2f
            halo.alpha = sc

            val q = overshoot.getInterpolation(clamp((p - 0.78f) / 0.22f))
            tri.scaleX = q; tri.scaleY = q
            tri.rotation = -120f * (1f - clamp((p - 0.78f) / 0.22f))

            letters.forEachIndexed { i, tv ->
                val f = clamp((p - (arr[i] - 0.04f)) / 0.33f)
                if (f <= 0f) return@forEachIndexed
                if (f < 0.35f) {
                    val k = f / 0.35f
                    tv.setTextColor(blend(Color.WHITE, gold, k))
                    tv.alpha = 0.16f + 0.84f * k
                    tv.setShadowLayer(22f * k, 0f, 0f, gold)
                } else {
                    val k = (f - 0.35f) / 0.65f
                    tv.setTextColor(blend(gold, Color.WHITE, k))
                    tv.alpha = 1f
                    tv.setShadowLayer(22f * (1f - k), 0f, 0f, gold)
                }
            }
        }
        an.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) word.postDelayed({ openMain() }, 350)
            }
        })
        anim = an
        an.start()
    }

    private fun openMain() {
        if (opened) return
        opened = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        anim?.cancel()
        super.onDestroy()
    }
}

