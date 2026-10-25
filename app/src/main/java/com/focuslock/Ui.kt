package com.focuslock

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Colours for light and dark mode. Minimal: near-black/near-white surfaces with one mint accent (matches the icon). */
class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF0E0F13.toInt() else 0xFFF3F4F6.toInt()
    val card = if (dark) 0xFF181A20.toInt() else 0xFFFFFFFF.toInt()
    val raised = if (dark) 0xFF22252D.toInt() else 0xFFEEF0F3.toInt()
    val line = if (dark) 0xFF2A2D36.toInt() else 0xFFE2E5EA.toInt()
    val text = if (dark) 0xFFF2F3F5.toInt() else 0xFF15161A.toInt()
    val muted = if (dark) 0xFF9399A6.toInt() else 0xFF6A6F7C.toInt()
    val accent = if (dark) 0xFF5EE6B5.toInt() else 0xFF0E9F75.toInt()
    val onAccent = if (dark) 0xFF0B0C10.toInt() else 0xFFFFFFFF.toInt()
    val accentSoft = if (dark) 0x245EE6B5 else 0x1C0E9F75
    val danger = if (dark) 0xFFFF6B6B.toInt() else 0xFFD93F3F.toInt()
    val dangerSoft = if (dark) 0x24FF6B6B else 0x1CD93F3F
    val ripple = if (dark) 0x33FFFFFF else 0x22000000

    companion object {
        fun of(c: Context) = Palette(
            (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        )
    }
}

/** Small view factory so MainActivity reads like a layout, with no extra libraries. */
class Ui(private val ctx: Context, val p: Palette) {
    enum class Style { PRIMARY, SECONDARY, DANGER }

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * ctx.resources.displayMetrics.density

    fun shape(color: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radius.toFloat())
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun pressable(bg: Drawable, radius: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(p.ripple), bg, shape(0xFFFFFFFF.toInt(), radius))

    fun text(s: CharSequence, size: Float = 15f, color: Int = p.text, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    fun title(s: String) = text(s, 13f, p.muted, bold = true).apply {
        isAllCaps = true
        letterSpacing = 0.08f
        setPadding(0, 0, 0, dp(10))
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    fun weighted(weight: Float = 1f, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginStart = dp(start); marginEnd = dp(end) }

    fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun row() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(parent: LinearLayout, tint: Int = p.card): LinearLayout {
        val c = column().apply {
            background = shape(tint, 22)
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }
        parent.addView(c, lp(bottom = 14))
        return c
    }

    fun button(label: String, style: Style = Style.PRIMARY, onClick: (View) -> Unit): TextView {
        val (bg, fg) = when (style) {
            Style.PRIMARY -> p.accent to p.onAccent
            Style.SECONDARY -> p.raised to p.text
            Style.DANGER -> p.dangerSoft to p.danger
        }
        return text(label, 15f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = pressable(shape(bg, 16), 16)
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick(it)
            }
        }
    }

    fun chip(label: String, selected: Boolean, round: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 14f, if (selected) p.onAccent else p.text, bold = selected).apply {
            gravity = Gravity.CENTER
            if (round) {
                minWidth = dp(40); minHeight = dp(40)
            } else {
                setPadding(dp(16), dp(9), dp(16), dp(9))
            }
            background = pressable(
                if (selected) shape(p.accent, 100) else shape(p.card, 100, p.line), 100
            )
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }

    fun pill(label: String, on: Boolean) = text(label, 12f, if (on) p.accent else p.muted, bold = true).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = shape(if (on) p.accentSoft else p.raised, 100)
    }

    fun switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val r = row().apply { setPadding(0, dp(8), 0, dp(8)) }
        val texts = column()
        texts.addView(text(title, 15f))
        if (sub != null) texts.addView(text(sub, 13f, p.muted))
        r.addView(texts, weighted(end = 12))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        @Suppress("UseSwitchCompatOrMaterialCode")
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(states, intArrayOf(p.accent, p.muted))
            trackTintList = ColorStateList(states, intArrayOf(p.accentSoft, p.line))
            setOnCheckedChangeListener { v, b ->
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onChange(b)
            }
        }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        return r
    }

    fun divider(parent: LinearLayout) {
        parent.addView(View(ctx).apply { setBackgroundColor(p.line) }, lp(h = dp(1), top = 10, bottom = 10))
    }
}
