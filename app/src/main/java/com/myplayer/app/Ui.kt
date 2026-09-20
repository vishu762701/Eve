package com.myplayer.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ImageView

object Ui {
    val ORANGE = 0xFFF58A32.toInt()
    val BG = 0xFF141414.toInt()
    val GRAY = 0xFF9A9A9A.toInt()
    val RED = 0xFFE53935.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun icon(c: Context, res: Int, pad: Int = 9, color: Int = Color.WHITE): ImageView {
        val v = ImageView(c)
        v.setImageResource(res)
        v.setColorFilter(color)
        val p = dp(c, pad)
        v.setPadding(p, p, p, p)
        return v
    }

    fun shape(c: Context, color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(c, radiusDp).toFloat()
        return g
    }

    fun circle(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    fun wordmark(): CharSequence {
        val s = SpannableString("eve")
        s.setSpan(ForegroundColorSpan(Color.WHITE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(RED), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(Color.WHITE), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
}
