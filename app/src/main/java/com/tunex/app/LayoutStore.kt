package com.tunex.app

import android.content.Context

/**
 * One look only: Sheet (UI 8).
 *  - a full-bleed hero that floods with the source colour
 *  - a 28dp sheet that overlaps the hero and holds hairline rows
 * The old Curve / Pills switch is gone; both flags stay so older calls still compile.
 */
object LayoutStore {
    fun index(@Suppress("UNUSED_PARAMETER") ctx: Context): Int = 1
    fun isPills(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = true
    fun isSheet(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = true
}
