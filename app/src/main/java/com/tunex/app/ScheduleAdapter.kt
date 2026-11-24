package com.tunex.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tunex.app.databinding.ItemRowBinding

class ScheduleAdapter(
    private val onToggle: (Schedule, Boolean) -> Unit,
    private val onEdit: (Schedule) -> Unit,
    private val onDelete: (Schedule) -> Unit
) : RecyclerView.Adapter<ScheduleAdapter.RowVH>() {

    private var items: List<Schedule> = emptyList()

    /** 0 = Curve, 1 = Pills. Only changes how the rows look. */
    var style = 0
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    fun submit(l: List<Schedule>) {
        items = l
        notifyDataSetChanged()
    }

    class RowVH(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH =
        RowVH(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    class Src(val label: String, val color: Int, val ini: String)

    companion object {
        /** Source badge: short name, colour and the two-letter initials shown in the circle. */
        fun src(ctx: Context, s: Schedule): Src = when {
            s.extApp == Ext.SPOTIFY -> Src("Spotify", 0xFF1ED760.toInt(), "Sp")
            s.extApp == Ext.JIO -> Src("JioSaavn", 0xFF16C2B0.toInt(), "Jio")
            s.extApp == Ext.INSTAGRAM -> Src("Instagram", 0xFFE1306C.toInt(), "IG")
            s.ytId.isNotBlank() -> Src("YouTube", 0xFFFF3131.toInt(), "YT")
            else -> Src("Phone", ThemeStore.attrColor(ctx, R.attr.txAccent, 0xFF7C5CFF.toInt()), "Ph")
        }

        /** "reel \u00B7 Once": the typed name (or the source) plus when it repeats. */
        fun subText(ctx: Context, s: Schedule): String {
            val name = s.customName().ifEmpty { src(ctx, s).label }
            return name + " \u00B7 " + Scheduler.repeatText(s.days)
        }

        /** Source icon for the schedule's tile. */
        fun iconRes(s: Schedule): Int = when {
            s.extApp == Ext.SPOTIFY -> R.drawable.ic_src_spotify
            s.extApp == Ext.JIO -> R.drawable.ic_src_jiosaavn
            s.extApp == Ext.INSTAGRAM -> R.drawable.ic_src_instagram
            s.ytId.isNotBlank() -> R.drawable.ic_src_youtube
            else -> R.drawable.ic_src_phone
        }

        /** Sheet (UI 8) thumbnail: source colour fading to black, rounded 16dp. */
        fun thumb(ctx: Context, color: Int, radiusPx: Float): GradientDrawable {
            val deep = androidx.core.graphics.ColorUtils.blendARGB(color, 0xFF000000.toInt(), 0.40f)
            val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, deep))
            gd.cornerRadius = radiusPx
            return gd
        }

        fun circle(color: Int): GradientDrawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(color)
            return gd
        }

        /** Tint the glass is made of: white on dark themes, black on light ones. */
        fun base(ctx: Context): Int {
            val bg = ThemeStore.attrColor(ctx, R.attr.txBg, 0xFF000000.toInt())
            return if (androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        /** Frosted-glass surface: faint diagonal sheen and a hairline edge. Neutral, so it works on any theme. */
        fun glass(ctx: Context, radiusPx: Float, strong: Boolean = false): GradientDrawable {
            val base = base(ctx)
            val light = base == 0xFF000000.toInt()
            val hi = if (strong) 0x16 else 0x0B
            val lo = if (strong) 0x08 else 0x02
            val gd = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(base, hi),
                    androidx.core.graphics.ColorUtils.setAlphaComponent(base, lo)
                )
            )
            gd.cornerRadius = radiusPx
            val d = ctx.resources.displayMetrics.density
            gd.setStroke(Math.max(1, d.toInt()), androidx.core.graphics.ColorUtils.setAlphaComponent(base, if (light) 0x1A else 0x12))
            return gd
        }

        /** Soft rounded square used for avatars in the Curve style. */
        fun squircle(color: Int, radiusPx: Float): GradientDrawable {
            val gd = GradientDrawable()
            gd.cornerRadius = radiusPx
            gd.setColor(color)
            return gd
        }
    }

    override fun onBindViewHolder(h: RowVH, position: Int) {
        val s = items[position]
        val ctx = h.b.root.context
        val dp = ctx.resources.displayMetrics.density
        val (t, ap) = Scheduler.fmt12s(s.hour, s.minute, s.second)
        h.b.time.text = t
        h.b.ampm.text = ap
        h.b.sub.text = subText(ctx, s)
        val sr = src(ctx, s)
        h.b.avatar.text = ""
        h.b.avatar.background = thumb(ctx, sr.color, 16 * dp)
        h.b.thumbIcon.setImageResource(iconRes(s))
        h.b.thumbIcon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())

        // Curve = tidy rounded tiles, Pills = fully rounded pills
        h.b.row.background = glass(ctx, (if (style == 1) 32 else 16) * dp)
        h.b.row.alpha = if (s.enabled) 1f else 0.55f
        h.b.time.alpha = if (s.enabled) 1f else 0.7f

        h.b.toggle.setOnCheckedChangeListener(null)
        h.b.toggle.isChecked = s.enabled
        h.b.toggle.setOnCheckedChangeListener { _, c -> onToggle(s, c) }
        h.b.row.setOnClickListener { onEdit(s) }
        h.b.row.setOnLongClickListener { onDelete(s); true }
    }
}

