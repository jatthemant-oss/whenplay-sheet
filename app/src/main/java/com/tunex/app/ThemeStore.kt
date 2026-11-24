package com.tunex.app

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.core.view.WindowCompat

/** Colour themes. Only look & feel - no behaviour is touched. */
object ThemeStore {
    data class T(val name: String, val style: Int, val top: Int, val bg: Int, val accent: Int, val light: Boolean)

    val all = listOf(
        T("Sheet", R.style.Theme_TuneX_Midnight, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE61E4D.toInt(), true)
    )

    /** One look only. */
    fun index(@Suppress("UNUSED_PARAMETER") ctx: Context): Int = 0

    fun set(@Suppress("UNUSED_PARAMETER") ctx: Context, @Suppress("UNUSED_PARAMETER") i: Int) {}

    /** Call BEFORE super.onCreate(). */
    fun apply(a: Activity) = a.setTheme(all[index(a)].style)

    /** Call AFTER setContentView(): dark/light status & navigation icons. */
    fun styleBars(a: Activity) {
        val light = all[index(a)].light
        val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
        c.isAppearanceLightStatusBars = light
        c.isAppearanceLightNavigationBars = light
    }

    fun attrColor(ctx: Context, attr: Int, def: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else def
    }
}

